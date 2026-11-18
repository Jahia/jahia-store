import {DocumentNode} from 'graphql';
import {createSite, deleteSite, setNodeProperty, publishAndWaitJobEnding} from '@jahia/cypress';

/**
 * S39 / F13 / U15 / D6 / D7 — LIVE verdict on whether {@code PublishedModuleFilter} actually RUNS, and on what
 * it answers.
 *
 * <p>An UNPUBLISHED ({@code published=false}) forge element must not be anonymously readable at its predictable
 * live URL (SECURITY-571 #54: the filter once never registered because {@code filters.*} was missing from the
 * pom's {@code <_dsannotations>}). This spec creates a draft module AND a draft package and requests each
 * anonymously in live mode.
 *
 * <p>JSTORE-SEC-005 (S3): the filter used to REDIRECT a draft to the site home, while a missing node answers
 * 404, so the redirect told anyone that a draft existed at that URL. A draft now answers exactly like a
 * missing node: the spec asserts a 404 AND the same status as a sibling path that does not exist. A 200 means
 * the filter did not run; a 3xx means the existence oracle is back. The unit-level counterpart is
 * {@code FiltersPackageRegistrationTest} (the _dsannotations guard) + {@code PublishedModuleFilterTest}
 * (the filter logic).
 */
describe('PublishedModuleFilter runs in live and answers a draft like a missing node', () => {
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

    /** Anonymous live request for a draft: 404, and the very status a missing sibling node gets. */
    const assertAnsweredLikeAMissingNode = (path: string) => {
        cy.logout();
        cy.request({url: liveUrl(`${repo}/no-such-node-cy`), failOnStatusCode: false, followRedirect: false}).then(missing => {
            cy.request({url: liveUrl(path), failOnStatusCode: false, followRedirect: false}).then(res => {
                // 200 = the filter never ran (the draft rendered); 3xx = the old redirect, an existence oracle.
                expect(res.status, `anonymous live view of draft ${path} must answer 404`).to.equal(404);
                expect(res.status, 'a draft must answer like a node that does not exist').to.equal(missing.status);
            });
        });
    };

    it('answers an anonymous request for a DRAFT module like a missing node (404, no redirect)', () => {
        assertAnsweredLikeAMissingNode(modulePath);
    });

    it('answers an anonymous request for a DRAFT package like a missing node (404, no redirect)', () => {
        assertAnsweredLikeAMissingNode(packagePath);
    });

    it('still renders the DRAFT module to an author with write access (preview)', () => {
        cy.login();
        cy.request({url: liveUrl(modulePath), failOnStatusCode: false, followRedirect: false}).then(res => {
            expect(res.status, 'root previews the draft in live').to.equal(200);
        });
    });
});
