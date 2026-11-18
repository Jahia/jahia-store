import {DocumentNode} from 'graphql';
import {createSite, deleteSite, createUser, deleteUser} from '@jahia/cypress';
import {createSharedCategory, deleteSharedCategory} from '../support/sharedCategories';

/**
 * Negative authorization coverage for the {@code forge} GraphQL namespace — the NEW halves of the
 * PARTIAL specs S33/S35/S36/S37 (the admin happy paths live in 05–10):
 *
 *   S33 — an ordinary authenticated user WITHOUT {@code siteAdminForgeSettings} is denied on both a
 *         read ({@code forge.settings}) and a write ({@code forge.updateSettings}).
 *   S35 / D13 — the settings payload exposes {@code passwordSet} only; the password value is never
 *         returned to a client (there is no such field on the schema).
 *   S37 / U10 / D8 — a role outside the store allow-list ({@code owner}, {@code site-administrator})
 *         cannot be granted through {@code forge.grantRole}.
 *   S36 / S25 — a category UUID OUTSIDE the site's root-category subtree is refused by a category
 *         mutation (the system session bypasses ACLs, so the UUID is re-validated).
 *   JSTORE-SEC-001 — the root category must be a category of the shared tree
 *         {@code /sites/systemsite/categories}: {@code forge.setRootCategory} refuses a category of
 *         another site and one of the store's own site, and keeps the root it had.
 *   JSTORE-SEC-001 S4 — {@code forge.addCategory} refuses a name that is not one JCR name segment
 *         ({@code ..}, {@code ../x}, {@code a/b}, {@code a[1]}), which would otherwise create the
 *         node outside the root category in a system session.
 *
 * Identity note (as in 21-permissions): GraphQL identity is the apollo client's Basic-auth header,
 * so an ordinary-user run uses a client built with that user's credentials.
 *
 * NOTE for Stage 6: this spec is authored offline (no local Cypress tooling). Confirm the
 * access-denied surfacing (errorPolicy 'all' → non-empty errors / null data) against the live node.
 */
describe('forge GraphQL — authorization negatives', () => {
    const siteKey = 'forgeAuthzNeg';
    // A second site whose category the store must not be able to take as its root (JSTORE-SEC-001).
    const otherSiteKey = 'forgeAuthzNegOther';
    // Created in the shared tree, which deleteSite does not clean: after() deletes them.
    const rootCategoryName = 'cy-29-root';
    const siblingCategoryName = 'cy-29-sibling';
    const ORDINARY = 'ordinary';
    const ORDINARY_PWD = 'Ordinary#1234';

    const getForgeSettings: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/query/getForgeSettings.graphql');
    const updateForgeSettings: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/updateForgeSettings.graphql');
    const grantSiteRole: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/grantSiteRole.graphql');
    const setRootCategory: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/setRootCategory.graphql');
    const updateForgeCategoryTitles: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/updateForgeCategoryTitles.graphql');
    const addNodeWithProperties: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/addNodeWithProperties.graphql');
    const getCategorySettings: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/query/getCategorySettings.graphql');
    const addForgeCategory: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/addForgeCategory.graphql');

    // Assert a GraphQL op was denied: with errorPolicy 'all' the guard surfaces as a GraphQL error
    // (GqlAccessDeniedException / ForgeSettingsException) rather than data.
    const expectDenied = (chainable: Cypress.Chainable) =>
        chainable.then((res: { errors?: unknown[]; data?: Record<string, unknown> }) => {
            const errors = res?.errors ?? [];
            expect(errors.length, 'operation should be rejected').to.be.greaterThan(0);
        });

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
            serverName: 'forgeauthzneg.local',
            locale: 'en'
        });
        try {
            deleteSite(otherSiteKey);
        } catch {
            // First run
        }

        createSite(otherSiteKey, {
            languages: 'en',
            templateSet: 'jahia-store-template',
            serverName: 'forgeauthznegother.local',
            locale: 'en'
        });
        createUser(ORDINARY, ORDINARY_PWD); // global user, no site roles -> no siteAdminForgeSettings
    });

    after(() => {
        cy.login();
        deleteSite(siteKey);
        deleteSite(otherSiteKey);
        deleteUser(ORDINARY);
        deleteSharedCategory(rootCategoryName);
        deleteSharedCategory(siblingCategoryName);
    });

    it('S33: an ordinary user is DENIED reading forge.settings', () => {
        // Chain .apollo() directly off cy.apolloClient() (a Cypress chainable) rather than assigning
        // its return value to a variable — matches the 21-permissions convention and the
        // cypress/no-assigning-return-values rule.
        expectDenied(
            cy
                .apolloClient({username: ORDINARY, password: ORDINARY_PWD}, {setCurrentApolloClient: false})
                .apollo({
                    query: getForgeSettings,
                    variables: {siteKey},
                    errorPolicy: 'all',
                    fetchPolicy: 'no-cache'
                })
        );
    });

    it('S33: an ordinary user is DENIED updating forge settings', () => {
        expectDenied(
            cy
                .apolloClient({username: ORDINARY, password: ORDINARY_PWD}, {setCurrentApolloClient: false})
                .apollo({
                    mutation: updateForgeSettings,
                    variables: {siteKey, url: 'https://evil.example', id: 'x', user: 'x', password: 'x'},
                    errorPolicy: 'all'
                })
        );
    });

    it('S35/D13: the settings payload never carries the password value (only passwordSet)', () => {
        cy.login();
        cy.apollo({
            mutation: updateForgeSettings,
            variables: {siteKey, url: 'https://s.example', id: 'i', user: 'u', password: 'secret'}
        });
        cy.apollo({query: getForgeSettings, variables: {siteKey}, fetchPolicy: 'no-cache'})
            .its('data.forge.settings')
            .should((settings: Record<string, unknown>) => {
                expect(settings).to.have.property('passwordSet', true);
                expect(settings).to.not.have.property('password');
            });
    });

    it('S37/D8: a role outside the store allow-list (owner / site-administrator) cannot be granted', () => {
        cy.login();
        expectDenied(
            cy.apollo({
                mutation: grantSiteRole,
                variables: {siteKey, role: 'owner', principalName: ORDINARY, principalType: 'USER'},
                errorPolicy: 'all'
            })
        );
        expectDenied(
            cy.apollo({
                mutation: grantSiteRole,
                variables: {siteKey, role: 'site-administrator', principalName: ORDINARY, principalType: 'USER'},
                errorPolicy: 'all'
            })
        );
    });

    it('S36/S25: a category outside the site root-category subtree is refused', () => {
        cy.login();
        // Root category + a sibling category of the shared tree that is NOT under the root subtree.
        createSharedCategory(rootCategoryName).then(rootUuid => {
            cy.apollo({mutation: setRootCategory, variables: {siteKey, rootCategoryUuid: rootUuid}})
                .its('data.forge.setRootCategory')
                .should('equal', true);
        });
        createSharedCategory(siblingCategoryName).then(siblingUuid => {
            // The sibling is a real jnt:category but lives OUTSIDE the root's subtree -> must be refused.
            expectDenied(
                cy.apollo({
                    mutation: updateForgeCategoryTitles,
                    variables: {siteKey, uuid: siblingUuid, titles: [{language: 'en', title: 'Hijacked'}]},
                    errorPolicy: 'all'
                })
            );
        });
    });

    it('JSTORE-SEC-001: setRootCategory refuses a category of another site and of the store site', () => {
        cy.login();
        let sharedRootUuid: string;
        createSharedCategory(rootCategoryName).then(uuid => {
            sharedRootUuid = uuid;
            cy.apollo({mutation: setRootCategory, variables: {siteKey, rootCategoryUuid: uuid}})
                .its('data.forge.setRootCategory')
                .should('equal', true);
        });

        // A category of ANOTHER site, then one of the store's OWN site: neither is in the shared
        // tree /sites/systemsite/categories, so both are refused.
        [otherSiteKey, siteKey].forEach(parentSite => {
            cy.apollo({
                mutation: addNodeWithProperties,
                variables: {
                    parentPath: `/sites/${parentSite}`,
                    name: 'siteLocalCat',
                    primaryNodeType: 'jnt:category',
                    properties: [],
                    mixins: []
                }
            })
                .its('data.jcr.addNode.node.uuid')
                .then((siteLocalUuid: string) => {
                    expectDenied(
                        cy.apollo({
                            mutation: setRootCategory,
                            variables: {siteKey, rootCategoryUuid: siteLocalUuid},
                            errorPolicy: 'all'
                        })
                    );
                });
        });

        // The refused calls stored nothing: the store keeps its shared-tree root.
        cy.then(() => {
            cy.apollo({query: getCategorySettings, variables: {siteKey}, fetchPolicy: 'no-cache'})
                .its('data.forge.categorySettings.rootCategoryUuid')
                .should('equal', sharedRootUuid);
        });
    });

    it('JSTORE-SEC-001: addCategory refuses a name that is not a single JCR name segment', () => {
        cy.login();
        ['..', '../escaped', 'a/b', 'a[1]'].forEach(name => {
            expectDenied(
                cy.apollo({mutation: addForgeCategory, variables: {siteKey, name}, errorPolicy: 'all'})
            );
        });
    });
});
