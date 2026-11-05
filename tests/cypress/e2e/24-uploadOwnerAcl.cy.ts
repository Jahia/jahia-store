import {DocumentNode} from 'graphql';
import {createSite, deleteSite, createUser, deleteUser, grantRoles, publishAndWaitJobEnding} from '@jahia/cypress';

/**
 * Guards the APPS-14 regression that the SEC-366 fix could have introduced.
 *
 * `store-developer` shipped `jcr:modifyAccessControl_live` for a reason: commit `25db344`
 * ("APPS-14 fixed issue when new node is created with just upload rights") added it so that
 * `CreateEntryFromJar.grantOwnerRole` — `node.grantRoles(...)` on the caller's own session —
 * would succeed. Removing the permission without moving that grant would have broken every
 * upload *after* the artifact was already deployed, so the advisory's "one attribute, one file"
 * remediation was not safe on its own. The grant now runs in a system session
 * (`OwnerRoleGrants`), and this spec is what proves the swap actually works.
 *
 * The existing coverage in `11-moduleUpload.cy.ts` uploads as **root**, whose `jcr:all` would
 * mask exactly this regression. So this spec uploads as a real `store-developer`, in the **live**
 * workspace — the workspace the store front-end renders in, and the one the role's `_live`
 * permissions were always aimed at.
 *
 * What it asserts, and why not `hasPermission`: the role still carries site-scoped
 * `jcr:write_live`, so a write-permission probe on the new node answers "yes" whether or not the
 * owner grant was applied. The ACE under `j:acl` is the only unambiguous evidence that the
 * elevated grant landed.
 */
const getAclEntries: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/query/getAclEntries.graphql');

const getNodePropertyInWorkspace: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/query/getNodePropertyInWorkspace.graphql');

describe('A store developer can still upload, and owns what they upload (SEC-366 / APPS-14)', () => {
    const siteKey = 'uploadOwnerSite';
    const sitePath = `/sites/${siteKey}`;
    const repositoryPath = `${sitePath}/contents/modules-repository`;
    // The .tgz fixture declares groupId org.cypress.test and name cy-js-module at 1.0.0, so the
    // action lays it down at groupId-as-folders + name (see 11-moduleUpload).
    const modulePath = `${repositoryPath}/org/cypress/test/cy-js-module`;
    const versionPath = `${modulePath}/cy-js-module-1.0.0`;

    const DEV = 'storedev';
    const DEV_PWD = 'Storedev#1234';
    // A SECOND developer, holding the same one role. The upload it makes reuses DEV's module node,
    // which is the case the elevated grant has to refuse.
    const DEV2 = 'storedev2';
    const DEV2_PWD = 'Storedev2#1234';
    const versionPath110 = `${modulePath}/cy-js-module-1.1.0`;

    /** The roles each ACE on `path` grants, keyed by ACE node name, read in LIVE as root. */
    const aclRolesByEntry = (path: string) =>
        cy
            .apollo({
                query: getAclEntries,
                variables: {workspace: 'LIVE', path: `${path}/j:acl`},
                fetchPolicy: 'no-cache'
            })
            .then(res => {
                const nodes =
                    (
                        res as {
                            data?: {
                                jcr?: {
                                    nodeByPath?: {
                                        children?: {
                                            nodes?: { name: string; properties?: { name: string; values?: string[] }[] }[]
                                        }
                                    }
                                }
                            }
                        }
                    ).data?.jcr?.nodeByPath?.children?.nodes ?? [];
                const out: Record<string, string[]> = {};
                for (const n of nodes) {
                    out[n.name] = n.properties?.find(pr => pr.name === 'j:roles')?.values ?? [];
                }

                return out;
            });

    /**
     * True when the uploader's own GRANT ACE carries the owner role. The ACE name is matched
     * exactly (Jahia writes "GRANT_u_<username>"): DEV is a prefix of DEV2, so a substring match
     * would let DEV2's entry pass for DEV's.
     */
    const ownsNode = (entries: Record<string, string[]>, user: string) =>
        (entries[`GRANT_u_${user}`] ?? []).includes('owner');

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
            serverName: 'uploadowner.local',
            locale: 'en'
        });
        createUser(DEV, DEV_PWD);
        grantRoles(sitePath, ['store-developer'], DEV, 'USER');
        createUser(DEV2, DEV2_PWD);
        grantRoles(sitePath, ['store-developer'], DEV2, 'USER');
        // The developer uploads from the live store front-end, so the site (and the grant that
        // lets them see it) has to be published.
        publishAndWaitJobEnding(sitePath, ['en']);
    });

    after(() => {
        cy.login();
        deleteSite(siteKey);
        deleteUser(DEV);
        deleteUser(DEV2);
    });

    it('completes the upload and records the uploader as owner', function () {
        // Needs the JS island bundle, as in 11-moduleUpload — the XHR submit is what carries the
        // CSRF token that a plain form post would lose.
        cy.request({
            url: '/modules/jahia-store-template/dist/client/components/forge/ModuleEditor.client.tsx.js',
            failOnStatusCode: false
        }).then(res => {
            if (res.status !== 200) {
                this.skip();
            }
        });

        cy.login(DEV, DEV_PWD);
        cy.visit(`/cms/render/live/en/sites/${siteKey}/home/my-modules.html`);
        cy.get('[data-upload-ready="true"]', {timeout: 20000}).should('exist');

        cy.intercept('POST', /createEntryFromJar\.do/).as('upload');
        cy.get('input[type="file"][name="file"]').selectFile('assets/cy-js-module.tgz', {force: true});
        cy.get('[data-upload-ready] button[type="submit"]').click();
        cy.wait('@upload', {timeout: 60000});

        // 1. The upload completed. Pre-fix-with-the-permission-removed, this is where an
        //    AccessDeniedException would have surfaced - after the content was written, leaving a
        //    half-committed upload.
        cy.login();
        // Read back in LIVE: the store front-end renders live, so the action created these nodes
        // in the live workspace and nothing published them into EDIT.
        cy.apollo({
            query: getNodePropertyInWorkspace,
            variables: {workspace: 'LIVE', path: modulePath, name: 'jcr:primaryType', language: null},
            fetchPolicy: 'no-cache'
        })
            .its('data.jcr.nodeByPath.properties[0].value')
            .should('equal', 'jnt:forgeModule');

        // 2. The elevated grant landed on BOTH nodes the upload creates. Checking only the module
        //    node would miss a flush that stopped after the first identifier.
        aclRolesByEntry(modulePath).then(entries => {
            expect(ownsNode(entries, DEV), `owner ACE for ${DEV} on the module node, got ${JSON.stringify(entries)}`).to.equal(true);
        });
        aclRolesByEntry(versionPath).then(entries => {
            expect(ownsNode(entries, DEV), `owner ACE for ${DEV} on the version node, got ${JSON.stringify(entries)}`).to.equal(true);
        });
    });

    it('gives a second developer their own version, and not the module somebody else owns', function () {
        // Runs after the upload above, which is what creates the module this one adds a version to.
        cy.request({
            url: '/modules/jahia-store-template/dist/client/components/forge/ModuleEditor.client.tsx.js',
            failOnStatusCode: false
        }).then(res => {
            if (res.status !== 200) {
                this.skip();
            }
        });

        cy.login(DEV2, DEV2_PWD);
        cy.visit(`/cms/render/live/en/sites/${siteKey}/home/my-modules.html`);
        cy.get('[data-upload-ready="true"]', {timeout: 20000}).should('exist');

        cy.intercept('POST', /createEntryFromJar\.do/).as('upload2');
        // 1.1.0 of the SAME module: upsertModuleNode returns DEV's stored node rather than creating one.
        cy.get('input[type="file"][name="file"]').selectFile('assets/cy-js-module-1.1.0.tgz', {force: true});
        cy.get('[data-upload-ready] button[type="submit"]').click();
        cy.wait('@upload2', {timeout: 60000});

        cy.login();
        // The version node is new, so the second developer owns it.
        aclRolesByEntry(versionPath110).then(entries => {
            expect(ownsNode(entries, DEV2), `owner ACE for ${DEV2} on its own version node, got ${JSON.stringify(entries)}`).to.equal(true);
        });

        // The module node is not, so the second developer must NOT own it, and the first still must.
        // A system session writes these entries, so nothing else refuses this one.
        aclRolesByEntry(modulePath).then(entries => {
            expect(ownsNode(entries, DEV2), `no owner ACE for ${DEV2} on ${DEV}'s module node, got ${JSON.stringify(entries)}`).to.equal(false);
            expect(ownsNode(entries, DEV), `owner ACE for ${DEV} still on its own module node, got ${JSON.stringify(entries)}`).to.equal(true);
        });
    });
});
