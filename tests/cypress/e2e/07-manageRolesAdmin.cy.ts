import {DocumentNode} from 'graphql';
import {createSite, deleteSite} from '@jahia/cypress';

/**
 * Verifies the React + GraphQL replacement for manageRoles.store.flow:
 *   - manageRolesSettings returns the curated forge role list with empty members
 *   - searchForgePrincipals finds the built-in "root" user
 *   - grantSiteRole adds a principal to a role, surfaced by manageRolesSettings
 *   - revokeSiteRole removes them again
 *   - The admin route /jahia/administration/{siteKey}/storeRoles responds
 */
describe('Manage roles admin (React + GraphQL)', () => {
    const siteKey = 'manageRolesSite';
    const rootPrincipal = 'root';

    const getManageRolesSettings: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/query/getManageRolesSettings.graphql');

    const searchForgePrincipals: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/query/searchForgePrincipals.graphql');

    const grantSiteRole: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/grantSiteRole.graphql');

    const revokeSiteRole: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/revokeSiteRole.graphql');

    before(() => {
        cy.login();
        try {
            deleteSite(siteKey);
        } catch {
            // ignore
        }

        createSite(siteKey, {
            languages: 'en',
            templateSet: 'jahia-store-template',
            serverName: 'localhost',
            locale: 'en'
        });
    });

    after(() => {
        deleteSite(siteKey);
    });

    it('returns the curated role list with no direct grants on a fresh site', () => {
        cy.apollo({query: getManageRolesSettings, variables: {siteKey}})
            .its('data.forge.manageRolesSettings')
            .should((s: { roles: Array<{ role: string; members: Array<{ name: string }> }> }) => {
                const roleNames = s.roles.map(r => r.role);
                expect(roleNames).to.deep.equal(['store-administrator', 'store-developer', 'reader']);
                const members = (role: string) => s.roles.find(r => r.role === role)!.members.map(m => m.name);
                expect(members('store-administrator')).to.deep.equal([]);
                expect(members('store-developer')).to.deep.equal([]);
                // Jahia itself grants reader to guest on every new site (GRANT u:guest reader, 8.2.3.x):
                // that is the only direct grant a fresh site may carry.
                expect(members('reader').filter(name => name !== 'guest')).to.deep.equal([]);
            });
    });

    it('finds the root user via searchForgePrincipals', () => {
        cy.apollo({
            query: searchForgePrincipals,
            variables: {siteKey, searchTerm: rootPrincipal, type: 'USER'}
        })
            .its('data.forge.searchPrincipals')
            .should((results: Array<{ name: string; type: string }>) => {
                expect(results.length).to.be.greaterThan(0);
                expect(results.some(p => p.name === rootPrincipal && p.type === 'USER')).to.be.true;
            });
    });

    it('grants store-administrator to root, sees the grant, then revokes it', () => {
        cy.apollo({
            mutation: grantSiteRole,
            variables: {
                siteKey,
                role: 'store-administrator',
                principalName: rootPrincipal,
                principalType: 'USER'
            }
        })
            .its('data.forge.grantRole')
            .should('equal', true);

        cy.apollo({query: getManageRolesSettings, variables: {siteKey}})
            .its('data.forge.manageRolesSettings.roles')
            .should((roles: Array<{ role: string; members: Array<{ name: string; type: string }> }>) => {
                const admin = roles.find(r => r.role === 'store-administrator');
                expect(admin, 'store-administrator role present').to.not.be.undefined;
                expect(admin!.members.some(m => m.name === rootPrincipal && m.type === 'USER')).to.be.true;
            });

        cy.apollo({
            mutation: revokeSiteRole,
            variables: {
                siteKey,
                role: 'store-administrator',
                principalName: rootPrincipal,
                principalType: 'USER'
            }
        })
            .its('data.forge.revokeRole')
            .should('equal', true);

        cy.apollo({query: getManageRolesSettings, variables: {siteKey}})
            .its('data.forge.manageRolesSettings.roles')
            .should((roles: Array<{ role: string; members: unknown[] }>) => {
                const admin = roles.find(r => r.role === 'store-administrator');
                expect(admin!.members).to.have.length(0);
            });
    });

    it('serves the admin route', () => {
        cy.login();
        cy.request({
            url: `/jahia/administration/${siteKey}/storeRoles`,
            failOnStatusCode: false
        })
            .its('status')
            .should('be.oneOf', [200, 302]);
    });
});
