import {DocumentNode} from 'graphql';
import {createSite, deleteSite, setNodeProperty, publishAndWaitJobEnding} from '@jahia/cypress';

/**
 * S39 / F13 / U15 / D6 / D7 — LIVE verdict on whether {@code PublishedModuleFilter} actually RUNS.
 *
 * <p><b>EXPECTED RED until the Stage-7 product fix.</b> The filter carries
 * {@code @Component(service = RenderFilter.class)} but {@code org.jahia.modules.forge.filters.*} is
 * ABSENT from the pom's {@code <_dsannotations>} (which lists only graphql/actions/proxy/settings),
 * and there is no blueprint fallback — so the filter never registers. As shipped, an UNPUBLISHED
 * ({@code published=false}) forge element is anonymously readable at its predictable live URL
 * (SECURITY-571 #54 regression). This spec creates a draft module AND a draft package and requests
 * each anonymously in live mode: it asserts the filter redirected them away from the draft. While
 * the packaging bug is present these assertions FAIL (the draft renders); once Stage 7 adds the
 * filters package to {@code _dsannotations}, they pass. The unit-level counterpart is
 * {@code FiltersPackageRegistrationTest} (the _dsannotations guard) + {@code PublishedModuleFilterTest}
 * (the filter logic).
 */
describe('PublishedModuleFilter runs in live (packaging-bug gate — expected RED until Stage 7)', () => {
    const siteKey = 'publishedFilter';
    const repo = `/sites/${siteKey}/contents/modules-repository`;
    const modulePath = `${repo}/draftWidget`;
    const packagePath = `${repo}/packages/draftPack`;

    const createForgeModule: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/createForgeModule.graphql');
    const addNodeWithProperties: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/addNodeWithProperties.graphql');

    const liveUrl = (path: string) => `/cms/render/live/en${path}.html`;

    before(() => {
        cy.login();
        try {
            deleteSite(siteKey);
        } catch {
            // First run
        }

        createSite(siteKey, {
            languages: 'en',
            templateSet: 'jahia-store-template',
            serverName: 'publishedfilter.local',
            locale: 'en'
        });

        // A DRAFT module (published=false) and a DRAFT package, both jmix:forgeElement.
        cy.apollo({
            mutation: createForgeModule,
            variables: {parentPath: repo, name: 'draftWidget', title: 'Draft Widget'}
        });
        setNodeProperty(modulePath, 'published', 'false', 'en');

        cy.apollo({
            mutation: addNodeWithProperties,
            variables: {
                parentPath: repo,
                name: 'packages',
                primaryNodeType: 'jnt:contentFolder',
                properties: [],
                mixins: []
            }
        });
        cy.apollo({
            mutation: addNodeWithProperties,
            variables: {
                parentPath: `${repo}/packages`,
                name: 'draftPack',
                primaryNodeType: 'jnt:forgePackage',
                properties: [{name: 'jcr:title', value: 'Draft Pack', language: 'en'}],
                mixins: []
            }
        });
        setNodeProperty(packagePath, 'published', 'false', 'en');

        // Publish the site so the draft nodes exist in LIVE (their published flag stays false).
        publishAndWaitJobEnding(`/sites/${siteKey}`, ['en']);
    });

    after(() => {
        cy.login();
        deleteSite(siteKey);
    });

    const assertRedirectedAway = (path: string) => {
        cy.logout();
        cy.request({url: liveUrl(path), failOnStatusCode: false, followRedirect: false}).then(res => {
            // Filter fired -> redirect to site home (3xx). If it renders (200 with the draft), the
            // filter never registered -> the confirmed packaging bug (expected RED until Stage 7).
            expect(res.status, `anonymous live view of draft ${path} must be redirected, not rendered`).to.be.oneOf([
                301, 302, 303, 307, 308
            ]);
        });
    };

    it('redirects an anonymous request for a DRAFT module away from the draft', () => {
        assertRedirectedAway(modulePath);
    });

    it('redirects an anonymous request for a DRAFT package away from the draft', () => {
        assertRedirectedAway(packagePath);
    });
});
