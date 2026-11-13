import {DocumentNode} from 'graphql';
import {
    addUserToGroup,
    createSite,
    createUser,
    deleteSite,
    deleteUser,
    grantRoles,
    markForDeletion,
    publishAndWaitJobEnding,
    setNodeProperty,
    unpublishNode,
    waitAllJobsFinished
} from '@jahia/cypress';

/**
 * SecurityUpdates.json: the feed Jahia instances read to warn administrators about updates that fix
 * security issues (contentFolder.securityUpdates.jsp + SecurityFeed).
 *
 * Model under test:
 *   - advisories live in <site>/contents/security-advisories (created by the jahia-store-template site
 *     import). The feed reads them from the LIVE workspace and lists one only when it is published with
 *     Jahia's publication AND its "Published in the security feed" box (`published`) is checked in the
 *     published version. An edit in the edit workspace changes nothing until the advisory is published
 *     again; unpublishing it, or unchecking the box and publishing, withdraws it;
 *   - store developers cannot create, modify or delete anything in the advisories folder, in either
 *     workspace (AdvisoriesLock: a DENY of store-developer on the folder for every granted principal),
 *     whether they were granted before the folder was published, through the store's own
 *     forge { grantRole } mutation, or directly in live. They keep their write access elsewhere;
 *   - second line of defence: an advisory written directly in live (Jahia's jmix:liveProperties or
 *     j:originWS=live markers) is ignored, even when the writer is allowed to write there (root here);
 *   - owner-flagged versions (securityFix) follow moduleList.json visibility: published modules and
 *     published versions only.
 *
 * Every refusal is paired with a positive control, so a missing folder or a broken fixture cannot make
 * a refusal test pass for the wrong reason. The view is not cached: each fetch sees the current state.
 * The tests run in order and share the site's state.
 */
describe('Security updates feed (securityUpdates.json)', () => {
    const siteKey = 'secFeedSite';
    const sitePath = `/sites/${siteKey}`;
    const advisoriesPath = `${sitePath}/contents/security-advisories`;
    const repositoryPath = `${sitePath}/contents/modules-repository`;
    const feedUrl = `/en/sites/${siteKey}/contents/modules-repository.securityUpdates.json`;
    const moduleListUrl = `/en/sites/${siteKey}/contents/modules-repository.moduleList.json`;

    const DEV = 'secfeeddev';
    const DEV_PWD = 'Secfeeddev#1234';
    const devCreds = {username: DEV, password: DEV_PWD};
    const DEV2 = 'secfeeddev2';
    const DEV2_PWD = 'Secfeeddev2#1234';
    const dev2Creds = {username: DEV2, password: DEV2_PWD};
    const DEV3 = 'secfeeddev3';
    const DEV3_PWD = 'Secfeeddev3#1234';
    const dev3Creds = {username: DEV3, password: DEV3_PWD};
    const STORE_ADMIN = 'secfeedadmin';
    const STORE_ADMIN_PWD = 'Secfeedadmin#1234';
    const storeAdminCreds = {username: STORE_ADMIN, password: STORE_ADMIN_PWD};

    const moduleName = 'cy-sec-module';
    const modulePath = `${repositoryPath}/${moduleName}`;
    const advisoryPath = (name: string) => `${advisoriesPath}/${name}`;

    const addNodeInWorkspace: DocumentNode =
        require('graphql-tag/loader!../fixtures/graphql/mutation/addNodeInWorkspace.graphql');
    const setPropertyInWorkspace: DocumentNode =
        require('graphql-tag/loader!../fixtures/graphql/mutation/setPropertyInWorkspace.graphql');
    const deleteNodeInWorkspace: DocumentNode =
        require('graphql-tag/loader!../fixtures/graphql/mutation/deleteNodeInWorkspace.graphql');
    const grantNodeRoles: DocumentNode =
        require('graphql-tag/loader!../fixtures/graphql/mutation/grantNodeRoles.graphql');
    const grantSiteRole: DocumentNode =
        require('graphql-tag/loader!../fixtures/graphql/mutation/grantSiteRole.graphql');
    const publishNodeWithOptions: DocumentNode =
        require('graphql-tag/loader!../fixtures/graphql/mutation/publishNodeWithOptions.graphql');
    const createForgeModule: DocumentNode =
        require('graphql-tag/loader!../fixtures/graphql/mutation/createForgeModule.graphql');
    const getNodeByPath: DocumentNode =
        require('graphql-tag/loader!../fixtures/graphql/query/getNodeByPath.graphql');

    type Advisory = {
        id: string; title: string; severity: string; cvss?: number; cves: string[]; url?: string;
        published?: string; summary?: string;
        fixes: Array<{ component: string; groupId?: string; fixedVersions: string[] }>;
    };
    type Feed = {
        schemaVersion: number;
        advisories: Advisory[];
        flaggedVersions: Array<{ groupId?: string; name: string; version: string; severity?: string; references: string[]; note?: string }>;
    };
    type Creds = { username: string; password: string };
    type Workspace = 'EDIT' | 'LIVE';

    const advisoryProps = (id: string, severity: string, fixedIn: string[], published = true) => [
        {name: 'jcr:title', value: `Advisory ${id}`, language: 'en'},
        {name: 'advisoryId', value: id},
        {name: 'severity', value: severity},
        {name: 'fixedIn', values: fixedIn},
        {name: 'published', value: String(published)}
    ];

    const addAdvisoryVars = (workspace: Workspace, name: string, properties: object[]) => ({
        workspace, parentPath: advisoriesPath, name, primaryNodeType: 'jnt:forgeSecurityAdvisory', properties
    });

    const addVersion = (version: string, properties: object[]) =>
        cy.apollo({
            mutation: addNodeInWorkspace,
            variables: {
                workspace: 'EDIT',
                parentPath: modulePath,
                name: `${moduleName}-${version}`,
                primaryNodeType: 'jnt:forgeModuleVersion',
                properties: [{name: 'versionNumber', value: version}, ...properties]
            }
        });

    /** Runs a mutation with the given account (null = root) and yields its GraphQL errors ([] = accepted). */
    const errorsAs = (creds: Creds | null, mutation: DocumentNode, variables: object) => {
        if (!creds) {
            cy.login();
        }

        const client = creds ? cy.apolloClient(creds, {log: true, setCurrentApolloClient: false}) : cy;
        return client.apollo({mutation, variables, errorPolicy: 'all'}).then(res => res.errors || []);
    };

    /** Sets `property` of advisory `name` to `value` in `workspace` as the given account; yields the errors. */
    const setAdvisoryProperty = (creds: Creds | null, workspace: Workspace, name: string, [property, value]: [string, string]) =>
        errorsAs(creds, setPropertyInWorkspace, {workspace, pathOrId: advisoryPath(name), name: property, value});

    /** Jahia publication of one advisory (and its translations), as root. */
    const publishAdvisory = (name: string) => {
        cy.login();
        publishAndWaitJobEnding(advisoryPath(name), ['en']);
    };

    const fetchFeedAnonymously = () => {
        cy.clearCookies();
        return cy.request({url: feedUrl, failOnStatusCode: false}).then(res => {
            expect(res.status, 'feed status').to.equal(200);
            const body = typeof res.body === 'string' ? JSON.parse(res.body) : res.body;
            return body as Feed;
        });
    };

    const advisoryIds = () => fetchFeedAnonymously().then(feed => feed.advisories.map(a => a.id).sort());

    const severityOf = (id: string) => fetchFeedAnonymously()
        .then(feed => feed.advisories.find(a => a.id === id)?.severity);

    /** The store developer's positive control: a write in live outside the advisories folder is accepted. */
    const writesInLiveElsewhere = (creds: Creds, value: string) =>
        errorsAs(creds, setPropertyInWorkspace, {
            workspace: 'LIVE', pathOrId: `${modulePath}/${moduleName}-1.4.1`, name: 'securityNote', value
        }).should('have.length', 0);

    /** A store developer can neither create, nor modify, nor delete an advisory, in the given workspace. */
    const cannotWriteAdvisories = (creds: Creds, workspace: Workspace, newName: string) => {
        errorsAs(creds, addNodeInWorkspace, addAdvisoryVars(workspace, newName, advisoryProps('JSA-2026-9099', 'critical', ['jahia-core@9.9.9.9'])))
            .should('have.length.greaterThan', 0);
        setAdvisoryProperty(creds, workspace, 'jsa-2026-9001', ['severity', 'low']).should('have.length.greaterThan', 0);
        setAdvisoryProperty(creds, workspace, 'jsa-2026-9001', ['published', 'false']).should('have.length.greaterThan', 0);
        errorsAs(creds, deleteNodeInWorkspace, {workspace, pathOrId: advisoryPath('jsa-2026-9001')})
            .should('have.length.greaterThan', 0);
    };

    /** A is untouched: listed, with its original severity. */
    const advisoryAIsIntact = () => {
        advisoryIds().should('include', 'JSA-2026-9001').and('not.include', 'JSA-2026-9099');
        severityOf('JSA-2026-9001').should('equal', 'high');
    };

    before(() => {
        cy.login();
        try {
            deleteSite(siteKey);
        } catch {
            // First run.
        }

        [DEV, DEV2, DEV3, STORE_ADMIN].forEach(user => {
            try {
                deleteUser(user);
            } catch {
                // First run.
            }
        });

        createSite(siteKey, {languages: 'en', templateSet: 'jahia-store-template', serverName: 'secfeed.local', locale: 'en'});
        createUser(DEV, DEV_PWD);
        createUser(DEV2, DEV2_PWD);
        createUser(DEV3, DEV3_PWD);
        createUser(STORE_ADMIN, STORE_ADMIN_PWD);
        // Granted in the edit workspace before the site is published: the lock reaches live with the folder.
        grantRoles(sitePath, ['store-developer'], DEV, 'USER');
        grantRoles(sitePath, ['store-administrator'], STORE_ADMIN, 'USER');

        // A: box checked, every optional field set. B: same, box unchecked.
        cy.apollo({
            mutation: addNodeInWorkspace,
            variables: addAdvisoryVars('EDIT', 'jsa-2026-9001', [
                ...advisoryProps('JSA-2026-9001', 'high', [
                    'graphql-dxm-provider@3.10.0',
                    'org.jahia.modules:graphql-dxm-provider@2.24.0',
                    'jahia-core@8.2.3.7'
                ]),
                {name: 'cvss', value: '7.5'},
                {name: 'cves', values: ['CVE-2026-1111']},
                {name: 'advisoryUrl', value: 'https://academy.jahia.com/jsa-2026-9001'},
                {name: 'summary', value: 'Cypress summary', language: 'en'}
            ])
        });
        cy.apollo({
            mutation: addNodeInWorkspace,
            variables: addAdvisoryVars('EDIT', 'jsa-2026-9002', advisoryProps('JSA-2026-9002', 'critical', ['jahia-core@8.2.3.8'], false))
        });

        // A module with an owner-flagged version, a plain version and a flagged but unpublished version.
        cy.apollo({mutation: createForgeModule, variables: {parentPath: repositoryPath, name: moduleName, title: 'Cypress Security Module'}});
        addVersion('1.4.2', [
            {name: 'published', value: 'true'},
            {name: 'securityFix', value: 'true'},
            {name: 'securitySeverity', value: 'medium'},
            {name: 'securityReferences', values: ['GHSA-2c2c-3f3f-4g4g']},
            {name: 'securityNote', value: 'Fixes an XSS in the settings page'}
        ]);
        addVersion('1.4.1', [{name: 'published', value: 'true'}]);
        addVersion('1.4.3', [{name: 'securityFix', value: 'true'}]);
        setNodeProperty(modulePath, 'groupId', 'org.cypress.security', 'en');
        setNodeProperty(modulePath, 'published', 'true', 'en');

        // Publishes the site: the module list, the advisories folder (with its lock) and A and B reach live.
        publishAndWaitJobEnding(sitePath, ['en']);
    });

    after(() => {
        cy.login();
        deleteSite(siteKey);
        [DEV, DEV2, DEV3, STORE_ADMIN].forEach(user => deleteUser(user));
    });

    it('the site import creates the security-advisories folder', () => {
        cy.login();
        cy.apollo({query: getNodeByPath, variables: {path: advisoriesPath}})
            .its('data.jcr.nodeByPath.primaryNodeType.name').should('eq', 'jnt:contentFolder');
    });

    it('lists a published advisory whose box is checked, not a published one whose box is unchecked', () => {
        advisoryIds().should('deep.equal', ['JSA-2026-9001']);
    });

    it('serves every field of an advisory', () => {
        fetchFeedAnonymously().then(feed => {
            const a = feed.advisories.find(x => x.id === 'JSA-2026-9001')!;
            expect(a.title).to.equal('Advisory JSA-2026-9001');
            expect(a.severity).to.equal('high');
            expect(a.cvss).to.equal(7.5);
            expect(a.cves).to.deep.equal(['CVE-2026-1111']);
            expect(a.url).to.equal('https://academy.jahia.com/jsa-2026-9001');
            expect(a.summary).to.equal('Cypress summary');
            expect(a.fixes).to.deep.equal([
                {component: 'graphql-dxm-provider', groupId: 'org.jahia.modules', fixedVersions: ['3.10.0', '2.24.0']},
                {component: 'jahia-core', fixedVersions: ['8.2.3.7']}
            ]);
        });
    });

    describe('Jahia publication', () => {
        it('an advisory with its box checked is absent until it is published in Jahia', () => {
            errorsAs(null, addNodeInWorkspace, addAdvisoryVars('EDIT', 'jsa-2026-9003', advisoryProps('JSA-2026-9003', 'medium', ['jcontent@3.8.2'])))
                .should('have.length', 0);
            advisoryIds().should('not.include', 'JSA-2026-9003');

            publishAdvisory('jsa-2026-9003');
            advisoryIds().should('include', 'JSA-2026-9003');
        });

        it('an edit in the edit workspace changes nothing until the advisory is published again', () => {
            setAdvisoryProperty(null, 'EDIT', 'jsa-2026-9003', ['severity', 'critical']).should('have.length', 0);
            severityOf('JSA-2026-9003').should('equal', 'medium');

            publishAdvisory('jsa-2026-9003');
            severityOf('JSA-2026-9003').should('equal', 'critical');
        });

        it('unchecking the box withdraws the advisory once published, not before', () => {
            setAdvisoryProperty(null, 'EDIT', 'jsa-2026-9003', ['published', 'false']).should('have.length', 0);
            advisoryIds().should('include', 'JSA-2026-9003');

            publishAdvisory('jsa-2026-9003');
            advisoryIds().should('not.include', 'JSA-2026-9003');
        });

        it('unpublishing the advisory in Jahia withdraws it', () => {
            // Positive control: box checked again and published, so it is listed.
            setAdvisoryProperty(null, 'EDIT', 'jsa-2026-9003', ['published', 'true']).should('have.length', 0);
            publishAdvisory('jsa-2026-9003');
            advisoryIds().should('include', 'JSA-2026-9003');

            cy.login();
            unpublishNode(advisoryPath('jsa-2026-9003'), 'en');
            advisoryIds().should('not.include', 'JSA-2026-9003');
            advisoryIds().should('deep.equal', ['JSA-2026-9001']);
        });
    });

    describe('store developers are locked out of the advisories folder', () => {
        it('a developer granted before the folder was published: refused in live and edit, still writes elsewhere in live', () => {
            writesInLiveElsewhere(devCreds, 'written in live by the developer');
            cannotWriteAdvisories(devCreds, 'LIVE', 'jsa-2026-9004');
            cannotWriteAdvisories(devCreds, 'EDIT', 'jsa-2026-9005');
            advisoryAIsIntact();
        });

        it('a developer granted through forge { grantRole } after the folder exists is locked out too', () => {
            errorsAs(null, grantSiteRole, {siteKey, role: 'store-developer', principalName: DEV2, principalType: 'USER'})
                .should('have.length', 0);
            // Publishes the site node alone (with its ACL), so the new grant is effective in live; the
            // advisories folder is not republished: the lock in live is the one the store wrote there.
            cy.login();
            cy.apollo({mutation: publishNodeWithOptions, variables: {pathOrId: sitePath, languages: ['en'], publishSubNodes: false, includeSubTree: false}});
            waitAllJobsFinished('Publication of the site node', 60000);

            writesInLiveElsewhere(dev2Creds, 'written in live by the second developer');
            cannotWriteAdvisories(dev2Creds, 'LIVE', 'jsa-2026-9006');
            cannotWriteAdvisories(dev2Creds, 'EDIT', 'jsa-2026-9007');
            advisoryAIsIntact();
        });

        it('a developer granted directly in live (no publication) is locked out at once', () => {
            // A grant made only in live gives no API access by itself: Jahia's AclListener maintains the
            // site-privileged group from edit-workspace ACL changes only, and the security filter's graphql
            // scope requires privileged_user (jcr:read_default on /sites). A live-only store-developer is
            // therefore a user already privileged on the site through another role; joining site-privileged
            // here gives exactly that, and no write anywhere (the privileged role only reads).
            cy.login();
            addUserToGroup(DEV3, 'site-privileged', siteKey);
            errorsAs(null, grantNodeRoles, {
                workspace: 'LIVE', pathOrId: sitePath, principalType: 'USER', principalName: DEV3, roleNames: ['store-developer']
            }).should('have.length', 0);

            writesInLiveElsewhere(dev3Creds, 'written in live by the third developer');
            cannotWriteAdvisories(dev3Creds, 'LIVE', 'jsa-2026-9008');
            advisoryAIsIntact();
        });
    });

    describe('store administrators keep their rights', () => {
        it('a store administrator creates and edits an advisory in the edit workspace', () => {
            errorsAs(storeAdminCreds, addNodeInWorkspace, addAdvisoryVars('EDIT', 'jsa-2026-9010', advisoryProps('JSA-2026-9010', 'medium', ['jcontent@3.8.2'])))
                .should('have.length', 0);
            setAdvisoryProperty(storeAdminCreds, 'EDIT', 'jsa-2026-9010', ['severity', 'high']).should('have.length', 0);
            // Not published yet: absent.
            advisoryIds().should('not.include', 'JSA-2026-9010');
        });

        it('a store administrator publishes the advisory, which is then listed', () => {
            // Negative first: created and edited, not published, so absent (asserted by the previous test too).
            advisoryIds().should('not.include', 'JSA-2026-9010');

            // The store-administrator role holds Jahia's "publish" permission since 5.2.0; without it the publication
            // silently skips the node (the mutation still answers true), so the feed is the real check.
            cy.apolloClient(storeAdminCreds, {log: true, setCurrentApolloClient: false})
                .apollo({mutation: publishNodeWithOptions, errorPolicy: 'all', variables: {pathOrId: advisoryPath('jsa-2026-9010'), languages: ['en'], publishSubNodes: true, includeSubTree: true}})
                .then(res => expect(res.errors || [], 'publication errors').to.have.length(0));
            cy.login();
            waitAllJobsFinished('Publication of the advisory by the store administrator', 60000);
            advisoryIds().should('include', 'JSA-2026-9010');
            severityOf('JSA-2026-9010').should('equal', 'high');
        });
    });

    describe('an advisory written directly in live is ignored (second line of defence)', () => {
        it('a published advisory changed in live (jmix:liveProperties) is withdrawn, and a republication does not restore it', () => {
            errorsAs(null, addNodeInWorkspace, addAdvisoryVars('EDIT', 'jsa-2026-9011', advisoryProps('JSA-2026-9011', 'low', ['jcontent@3.8.2'])))
                .should('have.length', 0);
            publishAdvisory('jsa-2026-9011');
            advisoryIds().should('include', 'JSA-2026-9011');

            // Root is not locked: it writes in live the way a store developer used to.
            setAdvisoryProperty(null, 'LIVE', 'jsa-2026-9011', ['severity', 'critical']).should('have.length', 0);
            advisoryIds().should('not.include', 'JSA-2026-9011');

            // Jahia keeps jmix:liveProperties (and the live value) through a publication from edit.
            setAdvisoryProperty(null, 'EDIT', 'jsa-2026-9011', ['advisoryUrl', 'https://academy.jahia.com/republished']).should('have.length', 0);
            publishAdvisory('jsa-2026-9011');
            advisoryIds().should('not.include', 'JSA-2026-9011');
        });

        it('recovery: delete the tampered advisory, publish the deletion, publish a new one', () => {
            cy.login();
            markForDeletion(advisoryPath('jsa-2026-9011'));
            publishAdvisory('jsa-2026-9011');
            errorsAs(null, addNodeInWorkspace, addAdvisoryVars('EDIT', 'jsa-2026-9011-r', advisoryProps('JSA-2026-9011', 'low', ['jcontent@3.8.2'])))
                .should('have.length', 0);
            publishAdvisory('jsa-2026-9011-r');
            advisoryIds().should('include', 'JSA-2026-9011');
        });

        it('an advisory created in live (j:originWS=live) is never listed', () => {
            errorsAs(null, addNodeInWorkspace, addAdvisoryVars('LIVE', 'jsa-2026-9012', advisoryProps('JSA-2026-9012', 'critical', ['jahia-core@9.9.9.9'])))
                .should('have.length', 0);
            advisoryIds().should('not.include', 'JSA-2026-9012').and('include', 'JSA-2026-9001');
        });
    });

    it('lists owner-flagged versions of published modules, published versions only', () => {
        // 1.4.3 is flagged but unpublished, 1.4.1 is published but not flagged.
        fetchFeedAnonymously().then(feed => {
            const mine = feed.flaggedVersions.filter(v => v.name === moduleName);
            expect(mine).to.deep.equal([{
                groupId: 'org.cypress.security',
                name: moduleName,
                version: '1.4.2',
                severity: 'medium',
                references: ['GHSA-2c2c-3f3f-4g4g'],
                note: 'Fixes an XSS in the settings page'
            }]);
        });
    });

    it('leaves moduleList.json unchanged: no security key in the catalogue', () => {
        cy.clearCookies();
        cy.request(moduleListUrl).then(res => {
            const body = typeof res.body === 'string' ? JSON.parse(res.body) : res.body;
            const module = body[0].modules.find((m: { name: string }) => m.name === moduleName);
            expect(module.versions.map((v: { version: string }) => v.version).sort()).to.deep.equal(['1.4.1', '1.4.2']);
            module.versions.forEach((v: object) => expect(v).to.have.all.keys('version', 'requiredVersion', 'downloadUrl'));
        });
    });

    it('refuses a malformed fixedIn value at write time (CND constraint)', () => {
        // Positive control: the same advisory with a valid value is accepted, its vulnerabilities given as
        // Jahia vulnerability identifiers (JSA-YYYY-NNNN.NNNN), as the security team writes them.
        errorsAs(null, addNodeInWorkspace, addAdvisoryVars('EDIT', 'good-fixed-in', [
            ...advisoryProps('JSA-2026-9006', 'high', ['jahia-core@8.2.3.9'], false),
            {name: 'cves', values: ['JSA-2026-0036.1066', 'CVE-2026-1234']}
        ])).should('have.length', 0);
        // Refused: a malformed vulnerability identifier, and a suffix on the advisory identifier itself.
        ['JSA-2026-0036.', 'JSA-2026-0036.10a'].forEach((cve, i) => {
            errorsAs(null, addNodeInWorkspace, addAdvisoryVars('EDIT', `bad-cve-${i}`, [
                ...advisoryProps('JSA-2026-9006', 'high', ['jahia-core@8.2.3.9'], false),
                {name: 'cves', values: [cve]}
            ])).should('have.length.greaterThan', 0);
        });
        errorsAs(null, addNodeInWorkspace, addAdvisoryVars('EDIT', 'bad-advisory-id', advisoryProps('JSA-2026-0036.1066', 'high', ['jahia-core@8.2.3.9'], false)))
            .should('have.length.greaterThan', 0);
        // "8.2..3" matters: the feed parser refuses "..", so the CND must refuse it too, or a published
        // advisory would pass jContent and then fail the whole feed.
        ['../../etc@1.0', 'jahia-core@8.2..3', 'jahia-core'].forEach((value, i) => {
            errorsAs(null, addNodeInWorkspace, addAdvisoryVars('EDIT', `bad-fixed-in-${i}`, advisoryProps('JSA-2026-9006', 'high', [value], false)))
                .should('have.length.greaterThan', 0);
        });
    });
});
