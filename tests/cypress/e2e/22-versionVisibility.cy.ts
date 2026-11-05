import {DocumentNode} from 'graphql';
import {createSite, deleteSite, setNodeProperty, publishAndWaitJobEnding} from '@jahia/cypress';

/**
 * Guards SUPPORT-659: a draft (unpublished) version of an otherwise-published module must
 * never reach a guest. The module-level `published` flag is gated twice (PublishedModuleFilter.java
 * on the "live" render mode, and the JS-side redirect in ForgeEntryDetail), but neither gate looks
 * at a VERSION's own `published` flag — sortedVersionNodes() returned every child version
 * unconditionally, so once a module cleared the module-level gate, any additional draft version
 * underneath it (changelog, release metadata, and a working download link for the unreleased
 * artifact) was fully visible to anonymous guests. Worse, ForgeEntryDetail picks versions[0] (the
 * highest version number) for the prominent header "latest" download CTA, so a higher-numbered
 * draft would eclipse the real release there too — not just in the Versions popup.
 *
 * The fix filters at the ForgeEntryDetail call site (`versions = sortedVersionNodes(node).filter(
 * (v) => canEdit || bool(v, "published"))`): owners keep seeing/managing drafts, everyone else
 * only ever sees released versions.
 *
 * Requires the JS build of jahia-store-template.
 */
describe('Draft versions are hidden from non-owners (SUPPORT-659)', () => {
    const siteKey = 'versionvis';
    const repo = `/sites/${siteKey}/contents/modules-repository`;
    const modulePath = `${repo}/released`;
    const defaultRender = `/cms/render/default/en${modulePath}.html`;
    const liveRender = `/cms/render/live/en${modulePath}.html`;

    const createForgeModule: DocumentNode =
        require('graphql-tag/loader!../fixtures/graphql/mutation/createForgeModule.graphql');

    const addNodeWithProps: DocumentNode =
        require('graphql-tag/loader!../fixtures/graphql/mutation/addNodeWithProperties.graphql');

    const islandBundle = '/modules/jahia-store-template/dist/client/components/forge/ModuleEditor.client.tsx.js';

    const addVersion = (name: string, properties: object[]) =>
        cy.apollo({
            mutation: addNodeWithProps,
            variables: {parentPath: modulePath, name, primaryNodeType: 'jnt:forgeModuleVersion', properties}
        });

    /** Mirrors 16-storefront/17-authoring's helper: open the header "Versions" popup. */
    const openVersionsPopup = (): void => {
        cy.get('[data-versions-open][data-versions-ready="true"]', {timeout: 20000}).click();
        cy.get('[data-versions-dialog][open]', {timeout: 20000}).should('be.visible');
    };

    before(function () {
        cy.request({url: islandBundle, failOnStatusCode: false}).then(res => {
            if (res.status !== 200) {
                cy.log('jahia-store-template JS module not deployed — skipping version-visibility spec');
                this.skip();
            }
        });
        cy.login();
        try {
            deleteSite(siteKey);
        } catch {
            // ignore — first run.
        }

        createSite(siteKey, {
            languages: 'en',
            templateSet: 'jahia-store-template',
            serverName: 'versionvis.local',
            locale: 'en'
        });

        cy.apollo({
            mutation: createForgeModule,
            variables: {parentPath: repo, name: 'released', title: 'Released Module'}
        });
        setNodeProperty(modulePath, 'published', 'true', 'en');
        // GroupId is intrinsic to a JAR module; the version download URL is GENERATED
        // from it (+ name/version/site), not stored on the version node.
        setNodeProperty(modulePath, 'groupId', 'org.cypress.test', 'en');

        // The released, public version.
        addVersion('v100', [
            {name: 'versionNumber', value: '1.0.0'},
            {name: 'published', value: 'true'}
        ]);
        // A draft of the NEXT version — higher-numbered than the release, so it would
        // wrongly win the "latest" header CTA pre-fix, not just the versions popup list.
        addVersion('v200', [
            {name: 'versionNumber', value: '2.0.0'},
            {name: 'published', value: 'false'},
            {name: 'changeLog', value: '<p>Internal QA build - do not distribute.</p>'}
        ]);

        // The guest assertions render the LIVE workspace — publish the whole site (module +
        // both versions). The draft's own `published` property stays false; publishing the
        // JCR node just makes that false value visible in LIVE, exactly like the real scenario
        // where an owner publishes a module and later uploads/edits a not-yet-released version.
        publishAndWaitJobEnding(`/sites/${siteKey}`, ['en']);
    });

    after(() => {
        cy.login();
        deleteSite(siteKey);
    });

    it('owner sees both the released version and the draft in the versions popup', () => {
        cy.login();
        cy.visit(defaultRender);
        openVersionsPopup();
        cy.get('[data-versions-dialog][open]').within(() => {
            cy.contains('1.0.0').should('be.visible');
            cy.contains('2.0.0').should('be.visible');
        });
    });

    it('hides the draft version from an anonymous guest - list, changelog, and the header download CTA', () => {
        cy.logout();
        cy.visit(liveRender);
        // The prominent header CTA must point at the released version, never the
        // higher-numbered draft (ForgeEntryDetail picks versions[0] for it).
        cy.get('[data-latest-download]', {timeout: 20000})
            .should('have.attr', 'href')
            .and('contain', 'released-1.0.0.jar')
            .and('not.contain', '2.0.0');
        openVersionsPopup();
        cy.get('[data-versions-dialog][open]').within(() => {
            cy.contains('1.0.0').should('be.visible');
            cy.contains('2.0.0').should('not.exist');
        });
        // Not merely hidden by CSS — the draft's changelog and download link must be absent
        // from the HTML entirely (the pre-fix bug leaked a live, working download URL).
        cy.get('body').should($b => {
            expect($b.html()).not.to.contain('Internal QA build');
            expect($b.html()).not.to.contain('released-2.0.0.jar');
        });
    });
});
