import {DocumentNode} from 'graphql';
import {createSite, deleteSite, createUser, deleteUser} from '@jahia/cypress';

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
 *
 * Identity note (as in 21-permissions): GraphQL identity is the apollo client's Basic-auth header,
 * so an ordinary-user run uses a client built with that user's credentials.
 *
 * NOTE for Stage 6: this spec is authored offline (no local Cypress tooling). Confirm the
 * access-denied surfacing (errorPolicy 'all' → non-empty errors / null data) against the live node.
 */
describe('forge GraphQL — authorization negatives', () => {
    const siteKey = 'forgeAuthzNeg';
    const ORDINARY = 'ordinary';
    const ORDINARY_PWD = 'Ordinary#1234';

    const getForgeSettings: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/query/getForgeSettings.graphql');
    const updateForgeSettings: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/updateForgeSettings.graphql');
    const grantSiteRole: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/grantSiteRole.graphql');
    const setRootCategory: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/setRootCategory.graphql');
    const updateForgeCategoryTitles: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/updateForgeCategoryTitles.graphql');
    const addNodeWithProperties: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/addNodeWithProperties.graphql');

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
        createUser(ORDINARY, ORDINARY_PWD); // global user, no site roles -> no siteAdminForgeSettings
    });

    after(() => {
        cy.login();
        deleteSite(siteKey);
        deleteUser(ORDINARY);
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
        // Root category + a sibling ("foreign") category that is NOT under the root subtree.
        cy.apollo({
            mutation: addNodeWithProperties,
            variables: {
                parentPath: `/sites/${siteKey}`,
                name: 'rootCat',
                primaryNodeType: 'jnt:category',
                properties: [],
                mixins: []
            }
        })
            .its('data.jcr.addNode.node.uuid')
            .then((rootUuid: string) => {
                cy.apollo({mutation: setRootCategory, variables: {siteKey, rootCategoryUuid: rootUuid}})
                    .its('data.forge.setRootCategory')
                    .should('equal', true);
            });
        cy.apollo({
            mutation: addNodeWithProperties,
            variables: {
                parentPath: `/sites/${siteKey}`,
                name: 'foreignCat',
                primaryNodeType: 'jnt:category',
                properties: [],
                mixins: []
            }
        })
            .its('data.jcr.addNode.node.uuid')
            .then((foreignUuid: string) => {
                // ForeignCat is a real jnt:category but lives OUTSIDE rootCat's subtree -> must be refused.
                expectDenied(
                    cy.apollo({
                        mutation: updateForgeCategoryTitles,
                        variables: {siteKey, uuid: foreignUuid, titles: [{language: 'en', title: 'Hijacked'}]},
                        errorPolicy: 'all'
                    })
                );
            });
    });
});
