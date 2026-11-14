import {DocumentNode} from 'graphql';
import {createSite, createUser, deleteSite, deleteUser, grantRoles, publishAndWaitJobEnding} from '@jahia/cypress';

/**
 * Owner-declared security information of a module version (jahia-store-template VersionSecurityEditor),
 * end to end: the owner flags a version on the live storefront, visitors see the notice, and
 * securityUpdates.json lists the version under flaggedVersions.
 *
 * Driven as a store developer in LIVE, the way real owners work (the storefront renders in live, and the
 * developer creates the module there), not as root: root bypasses ACLs and would prove nothing about
 * who may write these properties.
 *
 * Requires jahia-store-template 5.1.0 or later (the sibling build staged by ci.build.sh).
 */
describe('Version security information (storefront)', () => {
    const siteKey = 'secUiSite';
    const sitePath = `/sites/${siteKey}`;
    const repo = `${sitePath}/contents/modules-repository`;
    const moduleName = 'secmod';
    const modulePath = `${repo}/${moduleName}`;
    const versionPath = `${modulePath}/${moduleName}-1.0.0`;
    const liveRender = `/cms/render/live/en${modulePath}.html`;
    const feedUrl = `/en/sites/${siteKey}/contents/modules-repository.securityUpdates.json`;

    const DEV = 'secuidev';
    const DEV_PWD = 'Secuidev#1234';
    const devCreds = {username: DEV, password: DEV_PWD};

    const islandBundle = '/modules/jahia-store-template/dist/client/components/forge/VersionSecurityEditor.client.tsx.js';

    const createForgeModuleInWorkspace: DocumentNode =
        require('graphql-tag/loader!../fixtures/graphql/mutation/createForgeModuleInWorkspace.graphql');
    const addNodeInWorkspace: DocumentNode =
        require('graphql-tag/loader!../fixtures/graphql/mutation/addNodeInWorkspace.graphql');
    const setPropertyInWorkspace: DocumentNode =
        require('graphql-tag/loader!../fixtures/graphql/mutation/setPropertyInWorkspace.graphql');
    const getNodePropertyInWorkspace: DocumentNode =
        require('graphql-tag/loader!../fixtures/graphql/query/getNodePropertyInWorkspace.graphql');

    const asDeveloper = () => cy.apolloClient(devCreds, {log: true, setCurrentApolloClient: false});

    type FlaggedVersion = {name: string; version: string; severity?: string; references: string[]; note?: string};

    const flaggedVersionsOfModule = () => {
        cy.clearCookies();
        return cy.request(feedUrl).then(res => {
            const body = typeof res.body === 'string' ? JSON.parse(res.body) : res.body;
            return (body.flaggedVersions as FlaggedVersion[]).filter(v => v.name === moduleName);
        });
    };

    /** Opens the versions popup and the security editor of the only version card. */
    const openSecurityEditor = () => {
        cy.get('[data-versions-open][data-versions-ready="true"]', {timeout: 20000}).click();
        cy.get('[data-versions-dialog][open]', {timeout: 20000}).should('be.visible');
        cy.get('[data-versions-dialog][open] [data-forge-version] [data-security-editor][data-security-ready="true"]', {timeout: 20000})
            .as('editor')
            .contains('button', 'Edit security information')
            .click();
    };

    before(function () {
        cy.request({url: islandBundle, failOnStatusCode: false}).then(res => {
            if (res.status !== 200) {
                cy.log('jahia-store-template 5.1.0+ not deployed - skipping the version security spec');
                this.skip();
            }
        });
        cy.login();
        try {
            deleteSite(siteKey);
        } catch {
            // First run.
        }

        try {
            deleteUser(DEV);
        } catch {
            // First run.
        }

        createSite(siteKey, {languages: 'en', templateSet: 'jahia-store-template', serverName: 'secui.local', locale: 'en'});
        createUser(DEV, DEV_PWD);
        grantRoles(sitePath, ['store-developer'], DEV, 'USER');
        publishAndWaitJobEnding(sitePath, ['en']);

        // The developer creates and publishes their module in LIVE, as the storefront does.
        asDeveloper().apollo({
            mutation: createForgeModuleInWorkspace,
            variables: {workspace: 'LIVE', parentPath: repo, name: moduleName, title: 'Security Module'}
        });
        asDeveloper().apollo({
            mutation: addNodeInWorkspace,
            variables: {
                workspace: 'LIVE', parentPath: modulePath, name: `${moduleName}-1.0.0`,
                primaryNodeType: 'jnt:forgeModuleVersion',
                properties: [{name: 'versionNumber', value: '1.0.0'}, {name: 'published', value: 'true'}]
            }
        });
        [['groupId', 'org.cypress.secui'], ['published', 'true']].forEach(([name, value]) => {
            asDeveloper().apollo({mutation: setPropertyInWorkspace, variables: {workspace: 'LIVE', pathOrId: modulePath, name, value}});
        });
    });

    after(() => {
        cy.login();
        deleteSite(siteKey);
        deleteUser(DEV);
    });

    it('the owner flags the version; invalid references are refused before saving', () => {
        cy.login(DEV, DEV_PWD);
        cy.visit(liveRender);
        openSecurityEditor();

        cy.get('@editor').contains('label', 'This version contains security fixes').find('input').check();
        cy.get('@editor').find('select').select('high');
        cy.get('@editor').find('input[id$="-references"]').type('CVE-2026-1234, bogus');
        cy.get('@editor').find('input[id$="-references"]').should('have.attr', 'aria-invalid', 'true');
        cy.get('@editor').contains('Not a JSA, CVE or GHSA identifier: bogus').should('be.visible');
        cy.get('@editor').contains('button', 'Save').should('be.disabled');

        cy.get('@editor').find('input[id$="-references"]').clear().type('CVE-2026-1234');
        cy.get('@editor').find('textarea').type('Fixes a stored XSS in the settings page');
        cy.get('@editor').contains('button', 'Save').should('be.enabled').click();

        // The island reloads the page: the server-rendered notice now shows the saved values.
        cy.get('[data-versions-open][data-versions-ready="true"]', {timeout: 20000}).click();
        cy.get('[data-versions-dialog][open] [data-version-security]')
            .should('contain.text', 'Security fix')
            .and('contain.text', 'reported by the module owner')
            .and('contain.text', 'high')
            .and('contain.text', 'CVE-2026-1234')
            .and('contain.text', 'Fixes a stored XSS in the settings page');
    });

    it('securityUpdates.json lists the flagged version', () => {
        flaggedVersionsOfModule().should('deep.equal', [{
            groupId: 'org.cypress.secui',
            name: moduleName,
            version: '1.0.0',
            severity: 'high',
            references: ['CVE-2026-1234'],
            note: 'Fixes a stored XSS in the settings page'
        }] as unknown as FlaggedVersion[]);
    });

    it('an anonymous visitor sees the notice but no editor', () => {
        cy.clearCookies();
        cy.visit(liveRender);
        cy.get('[data-versions-open][data-versions-ready="true"]', {timeout: 20000}).click();
        cy.get('[data-versions-dialog][open] [data-version-security]').should('contain.text', 'Security fix');
        cy.get('[data-security-editor]').should('not.exist');
    });

    it('turning the flag off removes every detail, the notice and the feed entry', () => {
        cy.login(DEV, DEV_PWD);
        cy.visit(liveRender);
        openSecurityEditor();
        cy.get('@editor').contains('label', 'This version contains security fixes').find('input').uncheck();
        cy.get('@editor').contains('button', 'Save').click();

        cy.get('[data-versions-open][data-versions-ready="true"]', {timeout: 20000}).click();
        cy.get('[data-versions-dialog][open] [data-forge-version]').should('exist');
        cy.get('[data-version-security]').should('not.exist');
        flaggedVersionsOfModule().should('have.length', 0);

        cy.login();
        ['securitySeverity', 'securityReferences', 'securityNote'].forEach(name => {
            cy.apollo({
                query: getNodePropertyInWorkspace,
                variables: {workspace: 'LIVE', path: versionPath, name},
                fetchPolicy: 'no-cache'
            }).its('data.jcr.nodeByPath.properties').should('have.length', 0);
        });
    });
});
