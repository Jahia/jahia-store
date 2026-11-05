import {createSite, deleteSite, publishAndWaitJobEnding} from '@jahia/cypress';

/**
 * SUPPORT-687 — regression coverage for the edge lockdown of store.jahia.com.
 *
 * Covers this module's own behaviour: the cache key component, the header-driven
 * rendering and the authentication valve. Reverse-proxy configuration is out of scope
 * here and is validated with the infrastructure that owns it.
 *
 *  1. jahia-store-template omits the header sign-in trigger when the request carries
 *     X-Jahia-Edge: public, and renders it otherwise.
 *  2. EdgeOriginCacheKeyPartGenerator keeps the two variants in SEPARATE fragment cache
 *     entries. This is the part that matters: templates/Page is cache.perUser, whose key
 *     for an anonymous visitor is "guest" either way, so without the extra key component
 *     the first render would be cached and handed to the other audience.
 *
 * The arms are interleaved deliberately (public, vpn, public). A run that only ever asks
 * in one order would pass even if the cache were cross-serving.
 *
 * cy.request is used rather than cy.visit because the decision is server-side: this reads
 * the SSR output directly and needs to set a request header, which cy.visit cannot.
 */
describe('SUPPORT-687 — edge origin drives the sign-in trigger, per cache entry', () => {
    const siteKey = 'edgeorigin';
    const sitePath = `/sites/${siteKey}`;
    const homeLive = `/cms/render/live/en${sitePath}/home.html`;

    /** Stable server-rendered marker for the Login island trigger (see Login.client.tsx). */
    const SIGN_IN_MARKER = 'aria-controls="login-panel"';

    /** Anonymous GET of the live home page, optionally claiming an edge origin. */
    const fetchHome = (edge?: string, url: string = homeLive) =>
        cy.request({
            url,
            headers: edge ? {'X-Jahia-Edge': edge} : {},
            failOnStatusCode: false
        });

    before(() => {
        cy.login();
        try {
            deleteSite(siteKey);
        } catch {
            // Ignore — first run.
        }

        createSite(siteKey, {
            languages: 'en',
            templateSet: 'jahia-store-template',
            serverName: 'edgeorigin.local',
            locale: 'en'
        });
        publishAndWaitJobEnding(sitePath, ['en']);
        // Every arm below is anonymous; a leftover root cookie would render the account
        // menu instead of the sign-in trigger and make all three arms agree for the wrong reason.
        cy.logout();
    });

    after(() => {
        cy.login();
        deleteSite(siteKey);
    });

    it('renders the sign-in trigger for a VPN visitor and hides it for a CDN visitor', () => {
        // Control first: the page must actually contain the trigger, or the negative
        // assertion below would pass on an empty/error page.
        fetchHome('vpn').its('body').should('include', SIGN_IN_MARKER);
        fetchHome('public').its('body').should('not.include', SIGN_IN_MARKER);
    });

    it('keeps the two variants in separate cache entries when interleaved', () => {
        // Arm 1 — populate the public entry.
        fetchHome('public').its('body').should('not.include', SIGN_IN_MARKER);
        // Arm 2 — same URL, VPN. If the key ignored the edge, this is where the cached
        // public fragment (no trigger) would be handed to an operator.
        fetchHome('vpn').its('body').should('include', SIGN_IN_MARKER);
        // Arm 3 — back to public. Symmetrically, this is where a public visitor would be
        // handed the operator's fragment.
        fetchHome('public').its('body').should('not.include', SIGN_IN_MARKER);
    });

    it('treats an absent header as privileged, so no-proxy environments are unchanged', () => {
        fetchHome().its('body').should('include', SIGN_IN_MARKER);
    });

    /**
     * PublicEdgeGuestValve: a request marked as coming from the public CDN must be
     * anonymous even when it carries a fully authenticated session.
     *
     * The probe is an endpoint that REQUIRES authentication, not a "who am I" query -
     * GraphQL's currentUser is refused by the security profile regardless of who asks,
     * so it cannot tell the two apart. /jahia/administration/ answers 200 to an
     * authenticated request and 401 to a guest, which is exactly the distinction under
     * test. Measured on 8.2.3.2: auth=200, auth+public=401, anonymous=401.
     */
    const ADMIN_PROBE = '/jahia/administration/';
    const probeAs = (edge?: string) =>
        cy.request({
            url: ADMIN_PROBE,
            headers: edge ? {'X-Jahia-Edge': edge} : {},
            failOnStatusCode: false
        });

    it('treats an authenticated session as guest when the request came from the public edge', () => {
        cy.login();
        // Control: the same session, unmarked, still reaches the back office.
        probeAs().its('status').should('eq', 200);
        // Marked public, the valve terminates the auth pipeline before the session valve
        // runs, so the request is guest and the back office refuses it.
        probeAs('public').its('status').should('eq', 401);
        // An explicit vpn marking behaves like no marking at all.
        probeAs('vpn').its('status').should('eq', 200);
    });

    it('leaves the session intact, so the same browser is still authenticated afterwards', () => {
        cy.login();
        // Stripping the cookie at the edge, or removing the session user in a filter,
        // would have logged the operator out everywhere. The valve mutates nothing.
        probeAs('public').its('status').should('eq', 401);
        probeAs().its('status').should('eq', 200);
    });
});
