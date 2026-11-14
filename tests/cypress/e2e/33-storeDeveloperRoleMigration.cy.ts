import {DocumentNode} from 'graphql';
import {createSite, deleteSite, createUser, deleteUser, grantRoles, publishAndWaitJobEnding} from '@jahia/cypress';

/**
 * The SEC-366 migration (GHSA-f882-3xwv-3439) removes the stale grant from an installation that still has it.
 *
 * `store-developer` used to carry `jcr:modifyAccessControl_live`, so a store developer could rewrite the site's
 * live ACL (see `23-storeDeveloperAcl.cy.ts`). `roles.xml` no longer lists it, and a normal upgrade re-imports
 * `roles.xml`. `StoreDeveloperRoleMigration` covers the installs where that import did not run (a redeployed
 * snapshot, a failed import, a role changed after the import): at module start it removes the permission from
 * the role.
 *
 * Jahia's access manager reads a role's `j:permissionNames` first and falls back to its `j:permissions`
 * references only when `j:permissionNames` is absent (`AccessManagerUtils.getPrivileges`). An imported role has
 * `j:permissionNames`, so this spec puts the vulnerable state back THERE, the way an install that kept the old
 * role has it.
 *
 * The spec measures the effect, not only the stored list:
 *   1. positive control: in the vulnerable state, the store developer's own session reports
 *      `hasPermission(jcr:modifyAccessControl_live)` = true on the site in live. Without it, a false at the end
 *      would prove nothing (a probe that always answers false would pass);
 *   2. the module is stopped and started, so the migration runs at activation;
 *   3. the role lists neither the stale permission nor fewer of the others, and the same probe now answers false
 *      while an allowed permission still answers true.
 *
 * The migration runs at every activation (idempotent, silent when the role is clean), so the restart triggers
 * it although this module version already started once.
 *
 * Identity note: as in `23-storeDeveloperAcl.cy.ts`, GraphQL identity is the apollo client's Basic-auth header
 * (`cy.apolloClient`), not the browser session.
 */

const nodeHasPermission: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/query/nodeHasPermission.graphql');
const getForgeSettings: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/query/getForgeSettings.graphql');

describe('The SEC-366 migration removes the stale live ACL grant from store-developer (GHSA-f882-3xwv-3439)', () => {
    const MODULE = 'jahia-store';
    const ROLE = 'store-developer';
    const siteKey = 'devRoleMigrationSite';
    const sitePath = `/sites/${siteKey}`;
    const DEV = 'devmigrationuser';
    const DEV_PWD = 'Devmigration#1234';
    const devCreds = {username: DEV, password: DEV_PWD};

    const STALE = 'jcr:modifyAccessControl_live';
    const UPLOAD = 'jahiaForgeUploadModule';
    const WRITE_LIVE = 'jcr:write_live';
    // What roles.xml ships now, and what after() puts back.
    const FIXED_NAMES = [UPLOAD, WRITE_LIVE];
    // What roles.xml shipped before SEC-366 (commit 25db344).
    const VULNERABLE_NAMES = [UPLOAD, WRITE_LIVE, STALE];
    const RESTART_TIMEOUT = 120000;
    const MIGRATION_TIMEOUT = 30000;

    const groovyResult = (result: unknown): string => (typeof result === 'string' ? result : JSON.stringify(result));

    const setPermissionNames = (names: string[]) =>
        cy
            .executeGroovy('groovy/setRolePermissionNames.groovy', {
                ROLE_PLACEHOLDER: ROLE,
                NAMES_PLACEHOLDER: names.join(' ')
            })
            .then(result => {
                expect(groovyResult(result), 'the role permission names were written').to.equal(
                    `SET=${names.join(' ')}`
                );
            });

    /** The role's j:permissionNames, in the edit workspace (roles exist there only). */
    const readPermissionNames = (): Cypress.Chainable<string[]> =>
        cy.executeGroovy('groovy/readRolePermissionNames.groovy', {ROLE_PLACEHOLDER: ROLE}).then(result => {
            const text = groovyResult(result);
            expect(text, 'the role has a j:permissionNames list').to.match(/^NAMES=/);
            return text.substring('NAMES='.length).split(' ').filter(Boolean);
        });

    /** The capability probe, in the store developer's own session, on the site node in live. */
    const devHasPermission = (permission: string): Cypress.Chainable<boolean> =>
        cy
            .apolloClient(devCreds, {log: true, setCurrentApolloClient: false})
            .apollo({
                query: nodeHasPermission,
                variables: {workspace: 'LIVE', path: sitePath, permission},
                fetchPolicy: 'no-cache'
            })
            .its('data.jcr.nodeByPath.hasPermission');

    /** True while the module's `forge` GraphQL namespace answers: the module is started. */
    const forgeAnswers = (): Cypress.Chainable<boolean> =>
        cy
            .apollo({query: getForgeSettings, variables: {siteKey}, fetchPolicy: 'no-cache', errorPolicy: 'all'})
            .then((result: unknown) => {
                const answer = result as {
                    data?: { forge?: unknown }
                    errors?: readonly unknown[]
                    graphQLErrors?: readonly unknown[]
                };
                const errors = answer.graphQLErrors ?? answer.errors ?? [];
                return errors.length === 0 && Boolean(answer.data?.forge);
            });

    const bundle = (operation: 'stopBundle' | 'startBundle') => {
        cy.runProvisioningScript({script: [{[operation]: MODULE}]});
    };

    const restartModule = () => {
        bundle('stopBundle');
        // Positive signal that the module really stopped: its GraphQL namespace is gone.
        cy.waitUntil(() => forgeAnswers().then(up => !up), {
            timeout: RESTART_TIMEOUT,
            interval: 2000,
            errorMsg: 'the module never stopped'
        });
        bundle('startBundle');
        cy.waitUntil(() => forgeAnswers(), {
            timeout: RESTART_TIMEOUT,
            interval: 2000,
            errorMsg: 'the module never started again'
        });
    };

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
            serverName: 'devrolemigration.local',
            locale: 'en'
        });
        createUser(DEV, DEV_PWD);
        // One role, granted at the site, as in the advisory: store-developer carries j:privilegedAccess, which
        // lets the developer reach /modules/graphql for the probe.
        grantRoles(sitePath, [ROLE], DEV, 'USER');
        // The probe reads the site's ACL in live.
        publishAndWaitJobEnding(sitePath, ['en']);
    });

    after(() => {
        // Whatever happened above: leave the module started and the role as roles.xml ships it.
        bundle('startBundle');
        cy.waitUntil(() => forgeAnswers(), {timeout: RESTART_TIMEOUT, interval: 2000});
        setPermissionNames(FIXED_NAMES);
        cy.login();
        deleteSite(siteKey);
        deleteUser(DEV);
    });

    it('positive control: with the pre-SEC-366 role, the store developer can change the live ACL', () => {
        setPermissionNames(VULNERABLE_NAMES);
        readPermissionNames().should('deep.equal', VULNERABLE_NAMES);

        // The vulnerable state is really in effect, in the developer's own session.
        devHasPermission(STALE).should('eq', true);
        devHasPermission(UPLOAD).should('eq', true);
    });

    it('the migration removes the grant from j:permissionNames at module start, and only that grant', () => {
        restartModule();

        // The migration runs during the activation, which may finish after the GraphQL namespace is back: poll.
        cy.waitUntil(() => readPermissionNames().then(names => !names.includes(STALE)), {
            timeout: MIGRATION_TIMEOUT,
            interval: 2000,
            errorMsg: `${STALE} is still in j:permissionNames of /roles/${ROLE} after the module restarted`
        });

        readPermissionNames().then(names => {
            expect(names, `j:permissionNames of /roles/${ROLE} after the migration`).not.to.include(STALE);
            // The rest of the role is kept, in order: removing more would break uploads on every install.
            expect(names, 'the permissions the role keeps').to.deep.equal(FIXED_NAMES);
        });

        // The effect, measured in the developer's own session.
        devHasPermission(STALE).should('eq', false);
        devHasPermission(UPLOAD).should('eq', true);
    });
});
