import {DocumentNode} from 'graphql';
import {
    createSite,
    deleteSite,
    createUser,
    deleteUser,
    grantRoles,
    revokeRoles,
    publishAndWaitJobEnding
} from '@jahia/cypress';

/**
 * Regression coverage for GHSA-f882-3xwv-3439 / SEC-366.
 *
 * The `store-developer` role — granted to everyone allowed to upload a module — shipped
 * `jcr:modifyAccessControl_live` at site scope. A store developer could therefore rewrite the
 * site's LIVE access-control list: promote themselves to `store-administrator`, promote an
 * unrelated third party, or remove the site administrators' own entry. Because the write landed
 * in `live` it never entered a publication queue, so an administrator inspecting the ACL through
 * the editing UI saw an intact list.
 *
 * The advisory's proof-of-concept is reused here as the test design, with the expectation of the
 * attack arm inverted. Two things it established are load-bearing for how this spec asserts:
 *
 *   1. **Every arm answered HTTP 200.** The status code is never the discriminator, so nothing
 *      here asserts on one.
 *   2. **The escalation was measured, not inferred**, via `hasPermission` in the developer's own
 *      session — a 0 -> 1 -> 0 across grant and cleanup. This spec keeps that discipline: the
 *      refusals are only meaningful alongside the positive-control arm below, which proves the
 *      mutation and the probe both still work. Without it, a spec broken for an unrelated reason
 *      (a renamed schema field, say) would report two reassuring `false`s and pass.
 *
 * Identity note: as in `21-permissions.cy.ts`, GraphQL identity is the apollo client's Basic-auth
 * header (`cy.apolloClient`), not the browser session.
 */

const grantNodeRoles: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/grantNodeRoles.graphql');

const nodeHasPermission: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/query/nodeHasPermission.graphql');

describe('A store developer cannot rewrite the site ACL in LIVE (GHSA-f882-3xwv-3439)', () => {
    const siteKey = 'devAclSite';
    const sitePath = `/sites/${siteKey}`;
    const DEV = 'devuser';
    const DEV_PWD = 'Devuser#1234';
    // The permission gating whether an uploaded module is published to the store.
    const MODERATE = 'jahiaForgeModerateModule';
    // The role the advisory names as the escalation prize, and the only role that confers
    // MODERATE. Jahia's own site-administrator does NOT carry this module's permissions, so
    // probing MODERATE after granting site-administrator reads false whether the grant landed or
    // not - which would make the positive-control arm prove nothing.
    const ESCALATION_ROLE = 'store-administrator';

    const devCreds = {username: DEV, password: DEV_PWD};

    /** Run the ACL rewrite as `devuser`, in the given workspace. */
    const attemptSelfPromotionAs = (workspace: 'LIVE' | 'EDIT', creds?: { username: string; password: string }) => {
        const variables = {
            workspace,
            pathOrId: sitePath,
            principalType: 'USER',
            principalName: DEV,
            roleNames: [ESCALATION_ROLE]
        };
        if (creds) {
            return cy
                .apolloClient(creds, {log: true, setCurrentApolloClient: false})
                .apollo({mutation: grantNodeRoles, variables});
        }

        // Default client → root.
        return cy.apollo({mutation: grantNodeRoles, variables});
    };

    /**
     * The advisory's capability probe, read in devuser's own session. This is the assertion
     * surface rather than the mutation's return value: an access-denied mutation is reported as a
     * GraphQL error, and what actually matters is whether the developer's rights moved.
     */
    const devHasModeratePermission = (workspace: 'LIVE' | 'EDIT') =>
        cy
            .apolloClient(devCreds, {log: true, setCurrentApolloClient: false})
            .apollo({
                query: nodeHasPermission,
                variables: {workspace, path: sitePath, permission: MODERATE},
                fetchPolicy: 'no-cache'
            })
            .its('data.jcr.nodeByPath.hasPermission');

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
            serverName: 'devacl.local',
            locale: 'en'
        });
        createUser(DEV, DEV_PWD);
        // Devuser holds exactly one role, granted at the site — the same starting state as the
        // advisory's proof. store-developer carries j:privilegedAccess, which is what lets an
        // ordinary uploader reach /modules/graphql at all.
        grantRoles(sitePath, ['store-developer'], DEV, 'USER');
        // The attack targets the LIVE ACL, so the site's ACL has to exist in live.
        publishAndWaitJobEnding(sitePath, ['en']);
    });

    after(() => {
        cy.login();
        deleteSite(siteKey);
        deleteUser(DEV);
    });

    it('baseline: the developer does not hold the moderation permission', () => {
        devHasModeratePermission('LIVE').should('eq', false);
        devHasModeratePermission('EDIT').should('eq', false);
    });

    it('refuses the self-promotion in LIVE — the arm that used to succeed', () => {
        attemptSelfPromotionAs('LIVE', devCreds);

        // Pre-fix this read returned true: the grant applied, and the store's own moderation gate
        // opened for the developer who had just written their own ACL entry.
        devHasModeratePermission('LIVE').should('eq', false);
    });

    it('refuses the self-promotion in EDIT — the advisory control arm, still refused', () => {
        // The same operation, same actor, same node, one word changed. It was already denied
        // before the fix (AccessDeniedException, "Not sufficient privileges for permissions: 128");
        // it must stay denied, or the fix has merely moved the hole.
        attemptSelfPromotionAs('EDIT', devCreds);

        devHasModeratePermission('EDIT').should('eq', false);
        devHasModeratePermission('LIVE').should('eq', false);
    });

    it('positive control: the probe does move when the role is granted legitimately', () => {
        // Proves the two refusals above are the permission model discriminating by caller, rather
        // than a broken mutation or a probe wired to a field that always reads false. This is the
        // advisory's measured 0 -> 1 -> 0.
        //
        // The grant goes through the SUPPORTED route - an administrator writing the default
        // workspace, then publishing - rather than straight into live. That is deliberate: a
        // live-only ACE is precisely what the editing UI cannot see and what revokeRoles cannot
        // reach, so cleaning one up is unreliable. Granting through publication also demonstrates
        // the review step the vulnerability bypassed.
        cy.login();
        grantRoles(sitePath, [ESCALATION_ROLE], DEV, 'USER');
        publishAndWaitJobEnding(sitePath, ['en']);
        devHasModeratePermission('LIVE').should('eq', true);

        revokeRoles(sitePath, [ESCALATION_ROLE], DEV, 'USER');
        publishAndWaitJobEnding(sitePath, ['en']);
        devHasModeratePermission('LIVE').should('eq', false);
    });
});
