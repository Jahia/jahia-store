import {DocumentNode} from 'graphql';
import {createSite, deleteSite, publishAndWaitJobEnding, setNodeProperty, uploadFile} from '@jahia/cypress';

/**
 * Storefront read views (jahia-store-template JS module): the module list grid and
 * the module detail page.
 *
 * Seeds a jahia-store-template site with a modules-repository, two published modules
 * (one with a version) and one unpublished draft, plus a home page hosting a
 * forgeModulesList, then asserts:
 *   - the grid lists published entries and hides the unpublished draft
 *   - a module detail page renders title, description, version + download
 *
 * Requires the JS build of jahia-store-template (provides the forge views/templates).
 */
describe('Storefront read views (JS module)', () => {
    const siteKey = 'storefront';
    const contents = `/sites/${siteKey}/contents`;
    const repo = `${contents}/modules-repository`;
    const homeRender = `/cms/render/default/en/sites/${siteKey}/home.html`;
    const detailRender = `/cms/render/default/en${repo}/analytics.html`;

    const createForgeModule: DocumentNode =
        require('graphql-tag/loader!../fixtures/graphql/mutation/createForgeModule.graphql');

    const addNodeWithProps: DocumentNode =
        require('graphql-tag/loader!../fixtures/graphql/mutation/addNodeWithProperties.graphql');

    const updateForgeBranding: DocumentNode =
        require('graphql-tag/loader!../fixtures/graphql/mutation/updateForgeBranding.graphql');

    const deleteNode: DocumentNode =
        require('graphql-tag/loader!../fixtures/graphql/mutation/deleteNode.graphql');

    const islandBundle = '/modules/jahia-store-template/dist/client/components/forge/ModuleEditor.client.tsx.js';

    const addNode = (parentPath: string, name: string, primaryNodeType: string, properties: object[] = []) =>
        cy.apollo({mutation: addNodeWithProps, variables: {parentPath, name, primaryNodeType, properties}});

    /** Seeded release (upload) date of analytics 1.0.0, and the day the storefront must show. */
    const releasedOn = '2024-05-06T10:00:00.000+02:00';
    const releasedDay = '2024-05-06';

    /**
     * Clear the header search via its native clear gesture (the `search` event) and wait for the
     * programmatic-submit reload it triggers. `.trigger('search')` causes a requestSubmit()
     * navigation that Cypress doesn't track as a page-load like a click/{enter} does, so a bare
     * assertion straight after would straddle the reload (and inherit the empty subject the
     * @jahia/cypress console-error logger injects on window:load). Stamping window and waiting for
     * the stamp to vanish is reload-safe — a window object doesn't detach mid-navigation like a DOM
     * node — so later assertions run on the settled, reloaded page. Mirrors 17-authoring's
     * saveAndWaitReload.
     */
    const clearHeaderSearchAndWaitReload = (): void => {
        cy.window().then(w => {
            (w as unknown as {__preClear?: boolean}).__preClear = true;
        });
        cy.get('header [role="search"] input[name="src_terms"]:visible', {timeout: 20000})
            .clear()
            .trigger('search');
        cy.window({timeout: 20000}).should(w => {
            expect((w as unknown as {__preClear?: boolean}).__preClear).to.be.undefined;
        });
    };

    before(function () {
        cy.request({url: islandBundle, failOnStatusCode: false}).then(res => {
            if (res.status !== 200) {
                cy.log('jahia-store-template JS module not deployed — skipping storefront spec');
                this.skip();
            }
        });
        cy.login();
        try {
            deleteSite(siteKey);
        } catch {
            // ignore
        }

        // The jahia-store-template import.xml seeds the home page (+ modules list),
        // the My-modules sub-page and contents/modules-repository.
        createSite(siteKey, {
            languages: 'en,fr',
            templateSet: 'jahia-store-template',
            serverName: 'storefront.local',
            locale: 'en'
        });

        // Published module with a version (into the imported modules-repository)
        cy.apollo({
            mutation: createForgeModule,
            variables: {parentPath: repo, name: 'analytics', title: 'Analytics Dashboard'}
        });
        setNodeProperty(`${repo}/analytics`, 'description', '<p>Real-time charts and KPI widgets.</p>', 'en');
        setNodeProperty(`${repo}/analytics`, 'status', 'supported', 'en');
        setNodeProperty(`${repo}/analytics`, 'published', 'true', 'en');
        // GroupId is intrinsic to a JAR module; the version download URL is GENERATED
        // from it (+ name/version/site), not stored on the version node.
        setNodeProperty(`${repo}/analytics`, 'groupId', 'org.cypress.test', 'en');
        // Seeded in the past so the assertions can tell it apart from jcr:lastModified.
        addNode(`${repo}/analytics`, 'v100', 'jnt:forgeModuleVersion', [
            {name: 'versionNumber', value: '1.0.0'},
            {name: 'published', value: 'true'},
            {name: 'uploadDate', value: releasedOn},
            {name: 'changeLog', value: '<ul><li>Initial release</li></ul>'}
        ]);
        addNode(`${repo}/analytics`, 'video', 'jnt:videostreaming', [
            {name: 'provider', value: 'youtube'},
            {name: 'identifier', value: 'dQw4w9WgXcQ'}
        ]);

        // A second published module with a different status (for filtering)
        cy.apollo({
            mutation: createForgeModule,
            variables: {parentPath: repo, name: 'seo', title: 'SEO Toolkit'}
        });
        setNodeProperty(`${repo}/seo`, 'description', '<p>Meta tags and sitemaps.</p>', 'en');
        setNodeProperty(`${repo}/seo`, 'status', 'community', 'en');
        setNodeProperty(`${repo}/seo`, 'published', 'true', 'en');

        // Unpublished draft — must NOT appear in the grid
        cy.apollo({
            mutation: createForgeModule,
            variables: {parentPath: repo, name: 'draft', title: 'Draft Module'}
        });
    });

    after(() => {
        deleteSite(siteKey);
    });

    beforeEach(() => {
        cy.login();
    });

    it('lists published modules and hides the unpublished draft', () => {
        cy.visit(homeRender);
        cy.contains('Analytics Dashboard').should('be.visible');
        cy.contains('SEO Toolkit').should('be.visible');
        cy.contains('Real-time charts').should('be.visible');
        cy.contains('Draft Module').should('not.exist');
    });

    it('filters the grid by status (left-rail facet, case-insensitive)', () => {
        // Store the status capitalized, as migrated 4.3.0 data can be ("Supported"), while the
        // facet submits the lowercase choicelist key ("supported"): matching must be
        // case-insensitive. Restored to lowercase afterwards for the later tests.
        setNodeProperty(`${repo}/analytics`, 'status', 'Supported', 'en');
        cy.visit(homeRender);
        // Status/Category filtering lives in the modules-list LEFT rail. The FilterAutoSubmit
        // island submits the GET form on change once hydrated (data-filter-ready), so checking a
        // Status facet reloads the page showing only matching modules.
        cy.get('[data-forge-filter][data-filter-ready] input[name="status"][value="supported"]', {timeout: 20000})
            .check();
        cy.contains('[data-forge-card]', 'Analytics Dashboard').should('be.visible');
        cy.contains('[data-forge-card]', 'SEO Toolkit').should('not.exist');
        setNodeProperty(`${repo}/analytics`, 'status', 'supported', 'en');
    });

    it('lists the status facets in alphabetical order', () => {
        cy.visit(homeRender);
        // The left-rail Status facet is sorted alphabetically to ease scanning.
        cy.get('[data-forge-filter] input[name="status"]', {timeout: 20000})
            .then($els => $els.toArray().map(el => el.value))
            .should('deep.equal', ['community', 'labs', 'legacy', 'supported']);
    });

    it('shows a loading indicator while applying a filter', () => {
        cy.visit(homeRender);
        // The bar is global header chrome, hidden until a filter/search/pagination navigation starts.
        cy.get('[data-nav-progress]', {timeout: 20000}).should('exist').and('not.have.attr', 'data-loading');
        // Suppress the actual page navigation so the in-flight indicator is observable: the island
        // reveals the bar on the capture-phase submit, before this bubble-phase preventDefault runs.
        cy.get('[data-forge-filter][data-filter-ready]', {timeout: 20000}).then($f => {
            $f[0].addEventListener('submit', e => e.preventDefault(), {once: true});
        });
        cy.get('[data-forge-filter] input[name="status"][value="community"]').check();
        cy.get('[data-nav-progress]').should('have.attr', 'data-loading');
        // The accessible status region (an <output>, implicit role=status) announces loading.
        cy.get('[data-nav-status]').should('contain.text', 'Loading');
    });

    it('filters the grid by status in a non-default language (French)', () => {
        // Regression: `status` is indexed=no, so a JCR WHERE on it only resolves in the site's
        // default language (en) and returned nothing in fr. Status is now filtered in-app on the
        // shared property, so it must work in fr too: seo (community) shows, analytics (supported)
        // does not. Cards fall back to the node name when there is no fr title, so assert by count.
        const frHome = `/cms/render/default/fr/sites/${siteKey}/home.html`;
        cy.visit(frHome);
        cy.get('[data-forge-list]', {timeout: 20000});
        cy.get('[data-forge-card]').should('have.length', 2);
        cy.visit(`${frHome}?status=community`);
        cy.get('[data-forge-list]', {timeout: 20000});
        cy.get('[data-forge-card]').should('have.length', 1);
    });

    it('filters the grid by text (server-side, via the global header search)', () => {
        cy.visit(homeRender);
        // The in-rail search box was removed: the header's global search is the single
        // search entry point. Submitting it navigates to the grid with ?src_terms and
        // filters server-side. Scope to the visible desktop form (the mobile-nav island
        // renders a duplicate hidden at this viewport).
        cy.get('header [role="search"] input[name="src_terms"]:visible', {timeout: 20000})
            .type('seo{enter}');
        cy.contains('[data-forge-card]', 'SEO Toolkit').should('be.visible');
        cy.contains('[data-forge-card]', 'Analytics Dashboard').should('not.exist');
    });

    it('searches the description too, not only the title (advanced-search scope)', () => {
        cy.visit(homeRender);
        // "sitemaps" appears only in SEO Toolkit's description, never in any title — so a
        // hit proves the search now spans the description (title + module id + description).
        cy.get('header [role="search"] input[name="src_terms"]:visible', {timeout: 20000})
            .type('sitemaps{enter}');
        cy.contains('[data-forge-card]', 'SEO Toolkit').should('be.visible');
        cy.contains('[data-forge-card]', 'Analytics Dashboard').should('not.exist');
    });

    it('keeps the active status/category facets when searching from the header', () => {
        cy.visit(`${homeRender}?status=community`);
        // The header is cached chrome, so the AdvancedSearchSync island injects the active facets
        // as hidden inputs on the search form from the live URL — wait for that before submitting,
        // else the keyword would drop them. seo is community + matches "toolkit"; analytics is
        // supported, so it is excluded by the carried status facet even though both are published.
        cy.get('header [role="search"] input[type="hidden"][name="status"][value="community"]', {timeout: 20000})
            .should('exist');
        cy.get('header [role="search"] input[name="src_terms"]:visible').type('toolkit{enter}');
        cy.location('search').should('include', 'src_terms=toolkit').and('include', 'status=community');
        cy.contains('[data-forge-card]', 'SEO Toolkit').should('be.visible');
        cy.contains('[data-forge-card]', 'Analytics Dashboard').should('not.exist');
    });

    it('re-applies the filter when the search is cleared, dropping the keyword', () => {
        cy.visit(`${homeRender}?src_terms=seo`);
        cy.get('[data-forge-list]', {timeout: 20000});
        cy.contains('[data-forge-card]', 'SEO Toolkit').should('be.visible');
        cy.contains('[data-forge-card]', 'Analytics Dashboard').should('not.exist');
        // The native type=search "×" empties the field but does not submit; clearing fires a
        // `search` event and the AdvancedSearchSync island submits the now-empty form, so the
        // keyword filter lifts without the user pressing Enter. (Cypress can't click the native
        // clear pseudo-element, so we reproduce the gesture: clear the value + emit `search`.)
        clearHeaderSearchAndWaitReload();
        // On the settled, reloaded page the keyword is gone and the previously filtered-out module
        // is back.
        cy.location('search').should('not.contain', 'src_terms=seo');
        cy.contains('[data-forge-card]', 'Analytics Dashboard').should('be.visible');
    });

    it('keeps the active status facet when the search is cleared', () => {
        cy.visit(`${homeRender}?status=community&src_terms=toolkit`);
        // Wait for the island to inject the carried facet as a hidden input before clearing,
        // else the cleared submit would drop it.
        cy.get('header [role="search"] input[type="hidden"][name="status"][value="community"]', {timeout: 20000})
            .should('exist');
        clearHeaderSearchAndWaitReload();
        // On the settled, reloaded page the keyword is gone but the carried status facet survives.
        cy.location('search').should('include', 'status=community').and('not.contain', 'src_terms=toolkit');
        // The community filter still excludes the supported module.
        cy.contains('[data-forge-card]', 'SEO Toolkit').should('be.visible');
        cy.contains('[data-forge-card]', 'Analytics Dashboard').should('not.exist');
    });

    it('orders the grid by release date, most recent first', () => {
        // A module's release date lives on its version nodes (the grid sorts modules by their
        // newest published version's date, descending). analytics's only release (1.0.0) carries
        // a 2024 uploadDate; give seo a release NOW so it is the newest in the catalogue and
        // must lead the grid. Cleaned up afterwards so later specs see seo version-less again.
        addNode(`${repo}/seo`, 'v100', 'jnt:forgeModuleVersion', [
            {name: 'versionNumber', value: '1.0.0'},
            {name: 'published', value: 'true'}
        ]);
        cy.visit(homeRender);
        cy.get('[data-forge-card]', {timeout: 20000}).should('have.length', 2);
        cy.get('[data-forge-card]').first().should('contain.text', 'SEO Toolkit');
        cy.apollo({mutation: deleteNode, variables: {path: `${repo}/seo/v100`}});
    });

    it('paginates the grid when modules exceed the page size', () => {
        const listNode = `/sites/${siteKey}/home/main/store-modules`;
        // Two published modules; shrink the page size to 1 so there are two pages.
        setNodeProperty(listNode, 'nbOfModulePerPage', '1', 'en');
        cy.visit(homeRender);
        cy.get('[data-forge-pagination]', {timeout: 20000}).should('exist');
        cy.get('[data-page-info]').should('contain.text', 'Page 1 of 2');
        cy.get('[data-forge-card]').should('have.length', 1);
        cy.get('[data-page-next]').click();
        cy.get('[data-page-info]').should('contain.text', 'Page 2 of 2');
        cy.get('[data-forge-card]').should('have.length', 1);
        // Restore the default so later tests see the full grid.
        setNodeProperty(listNode, 'nbOfModulePerPage', '9', 'en');
    });

    it('opens a module detail page with version, video + download', () => {
        cy.visit(detailRender);
        cy.contains('h1', 'Analytics Dashboard').should('be.visible');
        // Video is in the default Overview tab.
        cy.get('iframe[src*="youtube.com/embed/dQw4w9WgXcQ"]').should('exist');
        // Versions now open in a popup (from the header button), not a tab. Wait for the
        // island to hydrate (data-versions-ready) before clicking, else the handler is not yet wired.
        cy.get('[data-versions-open][data-versions-ready="true"]', {timeout: 20000}).click();
        cy.get('[data-versions-dialog][open]').within(() => {
            cy.contains('1.0.0').should('be.visible');
            cy.contains('a', 'Download')
                .should('have.attr', 'href')
                .and('contain', 'analytics-1.0.0.jar');
            // The per-version footer surfaces the release date (uploadDate) — regression for the
            // dropped "Released" / "Requires Jahia" version metadata.
            cy.get('[data-forge-version]').contains(/Released/i).should('be.visible');
            cy.get('[data-forge-version]').should('contain.text', releasedDay);
        });
    });

    it('shows a breadcrumb back to the home view on a module detail page', () => {
        cy.visit(detailRender);
        // The current module is plain text; only "Home" is a link back to the listing.
        cy.get('[data-breadcrumb]').within(() => {
            cy.contains('Analytics Dashboard').should('be.visible');
            cy.get('[data-back-home]').should('have.attr', 'href').and('include', '/home');
        });
        // Following it lands back on the storefront home grid.
        cy.get('[data-breadcrumb] [data-back-home]').click();
        cy.get('[data-forge-list]', {timeout: 20000}).should('exist');
        cy.get('[data-forge-card]').should('have.length.greaterThan', 0);
    });

    it('shows the store.jahia.com-style Information rail + header download', () => {
        cy.visit(detailRender);
        // The latest-version download CTA is always in the title area.
        cy.get('[data-latest-download]')
            .should('have.attr', 'href')
            .and('contain', 'analytics-1.0.0.jar');
        // Module metadata lives in the always-visible Information rail (no longer behind a tab).
        cy.get('[data-detail-info]').within(() => {
            cy.contains('dt', 'Module ID').next('dd').should('contain.text', 'analytics');
            cy.contains('dt', /status/i).next('dd').should('contain.text', 'supported');
            // "Released" is the newest PUBLISHED version's upload date — NOT the module node's
            // jcr:lastModified, which the seeding writes above moved to "now".
            cy.contains('dt', /released/i).next('dd').should('have.text', releasedDay);
        });
    });

    it('keeps the release date fixed when the module and its version are edited', () => {
        // Touch both nodes; neither date may move.
        setNodeProperty(`${repo}/analytics/v100`, 'changeLog', '<ul><li>Edited changelog</li></ul>', 'en');
        setNodeProperty(`${repo}/analytics`, 'description', '<p>Edited description.</p>', 'en');

        // ...and the dates must not have moved, in the rail or on the version card.
        cy.visit(detailRender);
        cy.get('[data-detail-info]').within(() => {
            cy.contains('dt', /released/i).next('dd').should('have.text', releasedDay);
        });
        cy.get('[data-versions-open][data-versions-ready="true"]', {timeout: 20000}).click();
        cy.get('[data-versions-dialog][open]').within(() => {
            cy.get('[data-forge-version]').should('contain.text', releasedDay);
        });

        // Restore the seeded content so later specs see the original fixture.
        setNodeProperty(`${repo}/analytics/v100`, 'changeLog', '<ul><li>Initial release</li></ul>', 'en');
        setNodeProperty(`${repo}/analytics`, 'description', '<p>Real-time charts and KPI widgets.</p>', 'en');
    });

    it('omits the release date while every version is still a draft', () => {
        // No published version: the rail shows no date rather than the module's own.
        cy.visit(`/cms/render/default/en${repo}/draft.html`);
        cy.get('[data-detail-info]', {timeout: 20000}).should('be.visible');
        cy.get('[data-detail-info]').contains('dt', /released/i).should('not.exist');
    });

    it('the "My modules" list shows the user own modules, including drafts', () => {
        cy.visit(`/cms/render/default/en/sites/${siteKey}/home/my-modules.html`);
        cy.contains('[data-forge-card]', 'Analytics Dashboard').should('be.visible');
        cy.contains('[data-forge-card]', 'SEO Toolkit').should('be.visible');
        // Owners see their own unpublished drafts here (unlike the public grid).
        cy.contains('[data-forge-card]', 'Draft Module').should('be.visible');
    });

    it('renders the configured footer (copyright + privacy link) from forge settings', () => {
        // Configure branding via the same mutation the Settings screen uses.
        cy.apollo({
            mutation: updateForgeBranding,
            variables: {
                siteKey,
                copyright: '© 2026 ACME Store',
                privacyUrl: 'https://acme.example.com/privacy'
            }
        });
        cy.visit(homeRender);
        cy.contains('footer', '© 2026 ACME Store').should('be.visible');
        cy.get('footer')
            .contains('a', /privacy/i)
            .should('have.attr', 'href', 'https://acme.example.com/privacy');
        // The RSS link points at the clean /feed alias (SEO rewrite), not the internal
        // .moduleList.rss render URL.
        cy.get('footer [data-rss-feed]')
            .should('have.attr', 'href')
            .and('match', /\/feed$/)
            .and('not.contain', 'moduleList.rss');
        // Social links are icon-only (brand glyphs); the platform name is the accessible label.
        cy.get('footer [aria-label="Facebook"]').find('svg').should('exist');
    });

    it('shows a language selector (globe disclosure; current marked, other links to that language)', () => {
        // The site is en,fr — the header language switcher is a globe disclosure that lists both,
        // marks the current one, and each link points the current page at that language.
        cy.visit(homeRender);
        cy.get('header [data-lang-toggle]', {timeout: 20000}).click();
        cy.get('header [aria-label="Language"]').within(() => {
            cy.contains('a', 'EN').should('have.attr', 'aria-current', 'true');
            cy.contains('a', 'FR').should('not.have.attr', 'aria-current');
            cy.contains('a', 'FR').should('have.attr', 'href').and('include', '/fr/');
        });
    });

    it('keeps the filter/search params when switching language', () => {
        cy.visit(`${homeRender}?status=community`);
        cy.get('[data-forge-list]', {timeout: 20000});
        // Open the globe disclosure; the sync island appends the current query string to the links.
        cy.get('header [data-lang-toggle]', {timeout: 20000}).click();
        cy.get('header [data-lang-switch][hreflang="fr"]')
            .should('have.attr', 'href')
            .and('include', 'status=community');
        cy.get('header [data-lang-switch][hreflang="fr"]').click();
        cy.location('pathname').should('include', '/fr/');
        cy.location('search').should('include', 'status=community');
        // The FR grid is still status-filtered: community → seo only.
        cy.get('[data-forge-card]').should('have.length', 1);
    });

    it('signs in via the header login form (posts to /cms/login)', () => {
        // Publish the site so the LIVE home exists (anonymous can only see LIVE),
        // then go anonymous — the header then shows the sign-in form.
        publishAndWaitJobEnding(`/sites/${siteKey}`, ['en']);
        cy.logout();
        cy.visit(`/cms/render/live/en/sites/${siteKey}/home.html`);
        // Open the login panel (the toggle button), then fill + submit the form.
        // Wait for the login island to hydrate (data-login-ready) before clicking the
        // SSR trigger, else the panel-toggle handler may not be wired yet.
        cy.contains('button[data-login-ready="true"]', /log in/i, {timeout: 20000}).click();
        cy.get('#login-username').type('root');
        cy.get('#login-password').type(`${Cypress.env('SUPER_USER_PASSWORD')}{enter}`);
        // A successful form login redirects back to the page, now authenticated: the header
        // shows the account menu (the username). Open it to reveal the Log out *button*.
        cy.contains('root', {timeout: 20000}).should('be.visible');
        cy.get('header [data-account-toggle]').click();
        cy.get('header').contains('button', /log out/i).should('be.visible');
    });

    it('shows an inline error on bad credentials (no /cms/login dead-end)', () => {
        publishAndWaitJobEnding(`/sites/${siteKey}`, ['en']);
        cy.logout();
        cy.visit(`/cms/render/live/en/sites/${siteKey}/home.html`);
        // Wait for the login island to hydrate (data-login-ready) before clicking the
        // SSR trigger, else the panel-toggle handler may not be wired yet.
        cy.contains('button[data-login-ready="true"]', /log in/i, {timeout: 20000}).click();
        cy.get('#login-username').type('root');
        cy.get('#login-password').type('definitely-not-the-password{enter}');
        // Jahia redirects back to the page with ?loginError=…; the login island surfaces an
        // inline message and reopens the form instead of stranding the user on /cms/login.
        cy.contains(/invalid username or password/i, {timeout: 20000}).should('be.visible');
        cy.location('pathname').should('not.contain', '/cms/login');
        // The error param is stripped from the URL so a refresh is clean.
        cy.location('search').should('not.contain', 'loginError');
    });

    it('gates the "My modules" account-menu entry by login + Store role', () => {
        cy.login();
        publishAndWaitJobEnding(`/sites/${siteKey}`, ['en']);
        // Root holds every permission → My modules is in the account menu (not the main nav).
        cy.visit(homeRender);
        cy.get('header [data-account-toggle]', {timeout: 20000}).click();
        cy.contains('header [data-my-modules]', /my modules/i).should('be.visible');
        cy.contains('nav a', /my modules/i).should('not.exist');
        // Anonymous visitor on the live site → no account menu, no My modules anywhere.
        cy.logout();
        cy.visit(`/cms/render/live/en/sites/${siteKey}/home.html`);
        cy.get('header', {timeout: 20000}).should('exist');
        cy.get('header [data-account-toggle]').should('not.exist');
        cy.get('[data-my-modules]').should('not.exist');
    });

    it('renders the configured logo in the header (DAM reference)', () => {
        // Upload an image into the site media library, then point the logo at it.
        uploadFile('../../assets/icon.png', `/sites/${siteKey}/files`, 'store-logo.png', 'image/png');
        cy.apollo({
            mutation: updateForgeBranding,
            variables: {siteKey, logo: `/sites/${siteKey}/files/store-logo.png`}
        });
        cy.visit(homeRender);
        cy.get('header img', {timeout: 20000})
            .should('have.attr', 'src')
            .and('include', 'store-logo.png');
    });

    // Destructive (removes the My-modules list node), so it runs LAST. The site is
    // torn down in after(), so nothing else depends on the deletion.
    it('keeps "My modules" hidden from anonymous (no account menu when logged out)', () => {
        // My modules now lives in the account menu, gated by login + Store role. An
        // anonymous visitor gets the sign-in form, not the account menu, so the entry is
        // absent regardless of the list content. (Deleting the list node also exercises the
        // detail-detection path that drops the page from the main nav.)
        cy.login();
        cy.apollo({mutation: deleteNode, variables: {path: `/sites/${siteKey}/home/my-modules/main/mine`}});
        publishAndWaitJobEnding(`/sites/${siteKey}`, ['en']);
        cy.logout();
        cy.visit(`/cms/render/live/en/sites/${siteKey}/home.html`);
        cy.get('header', {timeout: 20000}).should('exist');
        cy.get('header [data-account-toggle]').should('not.exist');
        cy.get('[data-my-modules]').should('not.exist');
    });
});
