import {DocumentNode} from 'graphql';
import {
    createSite,
    deleteSite,
    createUser,
    deleteUser,
    createGroup,
    deleteGroup,
    addUserToGroup,
    grantRoles,
    publishAndWaitJobEnding
} from '@jahia/cypress';

/**
 * Regression coverage for GHSA-g6wp-ghxm-mx76 / SEC-375.
 *
 * jahia-store-template rendered per-user data into fragments that declared no cache properties,
 * so Jahia cached them under a key that models what the viewer may DO (an ACL component, which
 * also separates anonymous from authenticated) but never who the viewer IS. Two users holding the
 * same role therefore shared the slot:
 *
 *   1. `Page/default` renders Layout -> Header, which writes the viewer's own username into the
 *      account widget. One logged-in user was served another's username while browsing normally.
 *   2. `ForgeMyModulesList` selects rows on `jcr:createdBy = <the viewer's username>` and
 *      deliberately shows unpublished drafts, so one store-developer was served another's module
 *      list - disclosing unreleased modules.
 *
 * Both are now fixed by a `cache.perUser` declaration, and this spec is what proves it.
 *
 * ## Three things are load-bearing in how this spec is written
 *
 * 1. **The two users in the header arm hold NO role at all.** This is the subtlest part of the
 *    fixture and it is not a style preference - it is what makes the arm capable of failing.
 *    Jahia does not cache a fragment for a user who can edit the resource, so any elevated role
 *    immunises its holder against this defect. Measured, not theorised: the first draft of this
 *    spec gave both users `store-developer` (which carries site-scoped `jcr:write_live`) for
 *    "symmetry", and went green against a fully vulnerable build. Two plain accounts then
 *    reproduced the leak on the first attempt - `plainbob` was served `plainalice`. The advisory
 *    says the same thing in its PR:L reasoning: the reporter's first step is to log in with an
 *    ordinary account. Do not "harmonise" these users with the ones in the second test.
 *
 * 2. **Nothing flushes the cache and nothing varies the URL between the two users.** The
 *    advisory's own warning: a cache-busting query parameter enters the fragment key, the page
 *    then renders fresh, every user correctly sees their own name, and a still-broken build reads
 *    as fixed. The warm-then-check ORDER *is* the exploit - the first user populates the
 *    fragment, the second reads it - so each arm below visits a byte-identical URL, and the whole
 *    sequence stays inside one `it` so it cannot be reordered or run alone against a cold cache.
 *
 * 3. **Every arm answers HTTP 200**, exactly as in 23-storeDeveloperAcl. The status code is never
 *    the discriminator; only the rendered body is. And each arm asserts the positive case (this
 *    user's own name / own module IS present) as well as the negative, because
 *    `should('not.contain', ...)` passes trivially against a header that failed to render at all.
 *
 * Rendering is against `live` throughout: edit-mode rendering does not exercise the fragment
 * cache, so an EDIT-workspace version of this spec would prove nothing.
 *
 * Identity note: as in 21-permissions and 23-storeDeveloperAcl, GraphQL identity is the apollo
 * client's Basic-auth header (`cy.apolloClient`); browser identity is the session (`cy.login`).
 */

const createForgeModuleInWorkspace: DocumentNode =
    require('graphql-tag/loader!../fixtures/graphql/mutation/createForgeModuleInWorkspace.graphql');

describe('A cached fragment never serves one user the identity of another (GHSA-g6wp-ghxm-mx76)', () => {
    const siteKey = 'cacheIdentity';
    const sitePath = `/sites/${siteKey}`;
    const repositoryPath = `${sitePath}/contents/modules-repository`;

    // Header arm: two PLAIN accounts, no role anywhere - see note 1. Usernames must not be
    // substrings of one another, or the `not.contain` assertions would false-fail.
    const ALICE = 'plainalice';
    const ALICE_PWD = 'Plainalice#1234';
    const BOB = 'plainbob';
    const BOB_PWD = 'Plainbob#1234';

    // My-modules arm: the view renders nothing but a role prompt without jahiaForgeUploadModule,
    // so that arm needs developers. See the comment on that test for what it can and cannot prove.
    const DEV_A = 'devalice';
    const DEV_A_PWD = 'Devalice#1234';
    const DEV_B = 'devbob';
    const DEV_B_PWD = 'Devbob#1234';
    const DEV_GROUP = 'storedevs';

    const ALICE_MODULE = 'alice-widget';
    const ALICE_TITLE = 'Alice Widget';
    const BOB_MODULE = 'bob-gadget';
    const BOB_TITLE = 'Bob Gadget';

    // One URL per page for every arm - no query string, ever. See note 2 above.
    const homeLive = `/cms/render/live/en${sitePath}/home.html`;
    const myModulesLive = `/cms/render/live/en${sitePath}/home/my-modules.html`;

    const islandBundle = '/modules/jahia-store-template/dist/client/components/forge/ModuleEditor.client.tsx.js';

    /** Create a forge module in LIVE as `creds`, so the node's jcr:createdBy is that user. */
    const createModuleAs = (creds: { username: string; password: string }, name: string, title: string) =>
        cy
            .apolloClient(creds, {log: true, setCurrentApolloClient: false})
            .apollo({
                mutation: createForgeModuleInWorkspace,
                variables: {workspace: 'LIVE', parentPath: repositoryPath, name, title}
            });

    /** Switch the browser session to `user`, leaving no cookie from the previous arm. */
    const browseAs = (user: string, password: string) => {
        cy.logout();
        cy.login(user, password);
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
            serverName: 'cacheidentity.local',
            locale: 'en'
        });

        // Plain accounts for the header arm: created and then left alone. No role, no group.
        createUser(ALICE, ALICE_PWD);
        createUser(BOB, BOB_PWD);

        // Developers for the my-modules arm. One grant to a group both belong to, so their ACL
        // fingerprints match; store-developer also carries j:privilegedAccess, which is what lets
        // an uploader reach /modules/graphql at all (see 23-storeDeveloperAcl).
        createUser(DEV_A, DEV_A_PWD);
        createUser(DEV_B, DEV_B_PWD);
        createGroup(DEV_GROUP, false, siteKey);
        addUserToGroup(DEV_A, DEV_GROUP, siteKey);
        addUserToGroup(DEV_B, DEV_GROUP, siteKey);
        grantRoles(sitePath, ['store-developer'], DEV_GROUP, 'GROUP');

        // The pages and the modules-repository have to exist in live before anything is created
        // there, and before the front-end can be rendered at all.
        publishAndWaitJobEnding(sitePath, ['en']);

        // Seed one module per user, in LIVE and under each user's own session, so that
        // ForgeMyModulesList's `jcr:createdBy` filter has something to discriminate on.
        createModuleAs({username: DEV_A, password: DEV_A_PWD}, ALICE_MODULE, ALICE_TITLE);
        createModuleAs({username: DEV_B, password: DEV_B_PWD}, BOB_MODULE, BOB_TITLE);
    });

    after(() => {
        cy.login();
        deleteSite(siteKey);
        deleteUser(ALICE);
        deleteUser(BOB);
        deleteUser(DEV_A);
        deleteUser(DEV_B);
        deleteGroup(DEV_GROUP, siteKey);
    });

    it('serves each user their own username in the header, never the previous visitor\'s', function () {
        cy.request({url: islandBundle, failOnStatusCode: false}).then(res => {
            if (res.status !== 200) {
                cy.log('jahia-store-template JS module not deployed — skipping');
                this.skip();
            }
        });

        // Arm 1 — ALICE populates the fragment for this exact path.
        browseAs(ALICE, ALICE_PWD);
        cy.visit(homeLive);
        cy.get('[data-account-name]', {timeout: 20000}).should('contain', ALICE);

        // Arm 2 — BOB reads the SAME path. No flush, no query string: if the fragment is keyed
        // without identity, this is where he is handed ALICE's name. The positive assertion is
        // the control - without it an empty header would satisfy the negative one.
        browseAs(BOB, BOB_PWD);
        cy.visit(homeLive);
        cy.get('[data-account-name]', {timeout: 20000})
            .should('contain', BOB)
            .and('not.contain', ALICE);

        // Arm 3 — back to ALICE. Catches a "fix" that merely serves the most recent visitor to
        // everybody, which would satisfy arm 2 on its own.
        browseAs(ALICE, ALICE_PWD);
        cy.visit(homeLive);
        cy.get('[data-account-name]', {timeout: 20000})
            .should('contain', ALICE)
            .and('not.contain', BOB);
    });

    /**
     * The second instance, which the published advisory does not mention.
     *
     * `ForgeMyModulesList` selects its rows on `jcr:createdBy = <the viewer's username>` and
     * deliberately includes unpublished drafts, so a shared fragment hands one developer another
     * developer's module list - the titles of unreleased work. Measured red against the
     * vulnerable build: devbob was served devalice's card and never saw his own.
     *
     * Unlike the header arm this one needs elevated users, because the view renders nothing but a
     * role prompt without `jahiaForgeUploadModule`. Grant that role through the GROUP below, not
     * per user: two per-user grants write two different ACL entries, which can split the two
     * developers into separate cache slots and mask the defect.
     */
    it('serves each user their own modules on my-modules, never the previous visitor\'s', function () {
        cy.request({url: islandBundle, failOnStatusCode: false}).then(res => {
            if (res.status !== 200) {
                cy.log('jahia-store-template JS module not deployed — skipping');
                this.skip();
            }
        });

        // Arm 1 — DEV_A populates the my-modules fragment.
        browseAs(DEV_A, DEV_A_PWD);
        cy.visit(myModulesLive);
        cy.contains('[data-forge-card]', ALICE_TITLE, {timeout: 20000}).should('be.visible');
        cy.contains('[data-forge-card]', BOB_TITLE).should('not.exist');

        // Arm 2 — DEV_B reads the same path.
        browseAs(DEV_B, DEV_B_PWD);
        cy.visit(myModulesLive);
        cy.contains('[data-forge-card]', BOB_TITLE, {timeout: 20000}).should('be.visible');
        cy.contains('[data-forge-card]', ALICE_TITLE).should('not.exist');
    });
});
