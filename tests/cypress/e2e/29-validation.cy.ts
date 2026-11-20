import {DocumentNode} from 'graphql';
import {createSite, createUser, deleteSite, deleteUser, grantRoles, publishAndWaitJobEnding, setNodeProperty} from '@jahia/cypress';
import {approveVersion, setApprovalRequired, validationOf} from '../support/validation';
import {ModulePage} from '../page-object/ModulePage';
import {ValidationDashboardPage} from '../page-object/ValidationDashboardPage';
import {Catalogue} from '../page-object/Catalogue';

/**
 * The validation of a version (docs/validation.md): a version reaches the public once every
 * check approves it. The owner sees the status of the checks on the version and cannot publish
 * it before; a store administrator answers the checks that wait for a human on the dashboard
 * and invalidates a version at any time.
 *
 * Requires the JS build of jahia-store-template.
 */
describe('A version is validated before it reaches the public', () => {
    const siteKey = 'validation';
    const repo = `/sites/${siteKey}/contents/modules-repository`;
    const moduleName = 'checked-module';
    const modulePath = `${repo}/${moduleName}`;
    const version = '1.0.0';
    const developer = {username: 'valdev', password: 'Valdev#1234'};
    const admin = {username: 'valadmin', password: 'Valadmin#1234'};

    const createForgeModule: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/createForgeModule.graphql');
    const addNodeWithProps: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/addNodeWithProperties.graphql');
    const islandBundle = '/modules/jahia-store-template/dist/client/components/forge/RequestValidationButton.client.tsx.js';

    const asRoot = () => cy.apolloClient();

    before(function () {
        cy.request({url: islandBundle, failOnStatusCode: false}).then(res => {
            if (res.status !== 200) {
                cy.log('jahia-store-template JS module not deployed — skipping validation spec');
                this.skip();
            }
        });
        cy.login();
        try {
            deleteSite(siteKey);
        } catch {
            // Ignore — first run.
        }

        createSite(siteKey, {languages: 'en', templateSet: 'jahia-store-template', serverName: 'validation.local', locale: 'en'});
        createUser(developer.username, developer.password);
        createUser(admin.username, admin.password);
        grantRoles(`/sites/${siteKey}`, ['store-developer'], developer.username, 'USER');
        grantRoles(`/sites/${siteKey}`, ['store-administrator'], admin.username, 'USER');
        // The module of the developer, with one version that is not published yet, as an upload
        // leaves it: created by root, owned by the developer through the owner role the upload grants.
        cy.apollo({mutation: createForgeModule, variables: {parentPath: repo, name: moduleName, title: 'Checked Module'}});
        setNodeProperty(modulePath, 'published', 'true', 'en');
        setNodeProperty(modulePath, 'groupId', 'org.cypress.validation', 'en');
        cy.apollo({
            mutation: addNodeWithProps,
            variables: {
                parentPath: modulePath,
                name: `${moduleName}-${version}`,
                primaryNodeType: 'jnt:forgeModuleVersion',
                properties: [{name: 'versionNumber', value: version}, {name: 'published', value: 'false'}]
            }
        });
        grantRoles(modulePath, ['owner'], developer.username, 'USER');
        publishAndWaitJobEnding(`/sites/${siteKey}`, ['en']);
        setApprovalRequired(true);
    });

    after(() => {
        cy.login();
        asRoot().apollo({mutationFile: 'graphql/mutation/setValidationRequireApproval.graphql', variables: {value: 'false'}});
        deleteSite(siteKey);
        deleteUser(developer.username);
        deleteUser(admin.username);
    });

    it('the owner sees the version is not validated yet, and cannot publish it', () => {
        cy.login(developer.username, developer.password);
        ModulePage.visit(siteKey, moduleName).openVersions().version(version).expectValidation('none').expectPublishBlocked();
    });

    it('the owner asks for the validation: the quality check approves, the two assistant checks wait for a human', () => {
        cy.login(developer.username, developer.password);
        ModulePage.visit(siteKey, moduleName).openVersions().version(version).validate();
        ModulePage.visit(siteKey, moduleName)
            .openVersions()
            .version(version)
            .expectValidation('review')
            .expectCheck('quality', 'approved', 'Quality gate passed')
            .expectCheck('code-review', 'review')
            .expectCheck('security', 'review')
            .expectPublishBlocked();
    });

    it('the dashboard is for store administrators only', () => {
        cy.login(developer.username, developer.password);
        ValidationDashboardPage.visit(siteKey).expectForbidden();
    });

    it('the administrator finds the version waiting, with the prompt of each assistant check, and records their answers', () => {
        cy.login(admin.username, admin.password);
        const dashboard = ValidationDashboardPage.visit(siteKey).expectInSection('waiting', moduleName, version);
        dashboard
            .record(moduleName, version)
            .expectStatus('review')
            .expectPromptOf('code-review', `\`${moduleName}\` version \`${version}\``)
            .expectPromptOf('security', 'security scan')
            .answerCheck('code-review', '{"status": "approved", "comments": ["Manifest and definitions consistent"]}');
        ValidationDashboardPage.visit(siteKey)
            .record(moduleName, version)
            .expectCheck('code-review', 'approved', 'Manifest and definitions consistent')
            .expectStatus('review')
            .answerCheck('security', 'approved: no finding above low severity');
        ValidationDashboardPage.visit(siteKey).expectInSection('approved', moduleName, version);
    });

    it('once approved, the owner publishes the version and a visitor can download it', () => {
        cy.login(developer.username, developer.password);
        ModulePage.visit(siteKey, moduleName).openVersions().version(version).expectValidation('approved').expectPublishControl().publish();
        cy.logout();
        ModulePage.visit(siteKey, moduleName).openVersions().version(version).expectValidation('approved');
        Catalogue.of(siteKey).expectVersions(moduleName, [version]);
    });

    it('a version whose assistant check rejects it stays out of the public, with the finding', () => {
        cy.login();
        asRoot().apollo({
            mutation: addNodeWithProps,
            variables: {
                parentPath: modulePath,
                name: `${moduleName}-1.1.0`,
                primaryNodeType: 'jnt:forgeModuleVersion',
                properties: [{name: 'versionNumber', value: '1.1.0'}, {name: 'published', value: 'false'}]
            }
        });
        publishAndWaitJobEnding(modulePath, ['en']);
        cy.login(admin.username, admin.password);
        cy.apolloClient(admin);
        approveVersion(siteKey, moduleName, '1.1.0');
        ValidationDashboardPage.visit(siteKey)
            .record(moduleName, '1.1.0')
            .expectStatus('approved');
        cy.apollo({
            mutationFile: 'graphql/mutation/forgeAnswerValidationCheck.graphql',
            variables: {siteKey, moduleName, version: '1.1.0', check: 'security', answer: 'rejected: high severity, a.jsp, reflected XSS, escape the parameter'}
        });
        ValidationDashboardPage.visit(siteKey).expectInSection('rejected', moduleName, '1.1.0').record(moduleName, '1.1.0')
            .expectCheck('security', 'rejected', 'reflected XSS');
        cy.logout();
        ModulePage.visit(siteKey, moduleName).openVersions().expectAbsent('1.1.0');
        cy.apolloClient();
    });

    it('the administrator invalidates a published version: it leaves the public with the reason', () => {
        cy.login(admin.username, admin.password);
        ValidationDashboardPage.visit(siteKey).record(moduleName, version).invalidate('Withdrawn after a report');
        ValidationDashboardPage.visit(siteKey).record(moduleName, version).expectStatus('rejected').expectReason('Withdrawn after a report');
        cy.logout();
        ModulePage.visit(siteKey, moduleName).expectNoVersions();
        Catalogue.of(siteKey).expectVersions(moduleName, []);
        cy.login();
        validationOf(siteKey, moduleName, version).its('status').should('eq', 'rejected');
    });
});
