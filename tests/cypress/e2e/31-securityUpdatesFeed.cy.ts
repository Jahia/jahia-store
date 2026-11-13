import {DocumentNode} from 'graphql';
import {
    createSite,
    createUser,
    deleteSite,
    deleteUser,
    grantRoles,
    publishAndWaitJobEnding,
    setNodeProperty
} from '@jahia/cypress';

/**
 * SecurityUpdates.json: the feed Jahia instances read to warn administrators about updates that fix
 * security issues (contentFolder.securityUpdates.jsp + SecurityFeed).
 *
 * Model under test:
 *   - advisories live in <site>/contents/security-advisories (created by the jahia-store-template site
 *     import). The feed reads them from the EDIT workspace and lists those whose `published` box is
 *     checked; Jahia publication plays no part, and edits apply at once;
 *   - store and site administrators can write advisories; store developers cannot (their role writes
 *     LIVE only), and what they write in live never reaches the feed;
 *   - owner-flagged versions (securityFix) follow moduleList.json visibility: published modules and
 *     published versions only.
 *
 * Every refusal is paired with a positive control, so a missing folder or a broken fixture cannot make
 * a refusal test pass for the wrong reason. The view is not cached: each fetch sees the current state.
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
    const STORE_ADMIN = 'secfeedadmin';
    const STORE_ADMIN_PWD = 'Secfeedadmin#1234';
    const storeAdminCreds = {username: STORE_ADMIN, password: STORE_ADMIN_PWD};

    const moduleName = 'cy-sec-module';
    const modulePath = `${repositoryPath}/${moduleName}`;

    const addNodeInWorkspace: DocumentNode =
        require('graphql-tag/loader!../fixtures/graphql/mutation/addNodeInWorkspace.graphql');
    const setPropertyInWorkspace: DocumentNode =
        require('graphql-tag/loader!../fixtures/graphql/mutation/setPropertyInWorkspace.graphql');
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

    const advisoryProps = (id: string, severity: string, fixedIn: string[], published = true) => [
        {name: 'jcr:title', value: `Advisory ${id}`, language: 'en'},
        {name: 'advisoryId', value: id},
        {name: 'severity', value: severity},
        {name: 'fixedIn', values: fixedIn},
        {name: 'published', value: String(published)}
    ];

    const addAdvisoryVars = (workspace: 'EDIT' | 'LIVE', name: string, properties: object[]) => ({
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
    const errorsAs = (creds: { username: string; password: string } | null, mutation: DocumentNode, variables: object) => {
        const client = creds ? cy.apolloClient(creds, {log: true, setCurrentApolloClient: false}) : cy;
        return client.apollo({mutation, variables, errorPolicy: 'all'}).then(res => res.errors || []);
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

    before(() => {
        cy.login();
        try {
            deleteSite(siteKey);
        } catch {
            // First run.
        }

        [DEV, STORE_ADMIN].forEach(user => {
            try {
                deleteUser(user);
            } catch {
                // First run.
            }
        });

        createSite(siteKey, {languages: 'en', templateSet: 'jahia-store-template', serverName: 'secfeed.local', locale: 'en'});
        createUser(DEV, DEV_PWD);
        grantRoles(sitePath, ['store-developer'], DEV, 'USER');
        createUser(STORE_ADMIN, STORE_ADMIN_PWD);
        grantRoles(sitePath, ['store-administrator'], STORE_ADMIN, 'USER');

        // A: published, every optional field set. B: same, box unchecked.
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

        // Publishes the site so that the module list, and the live copy of the advisories folder, exist.
        publishAndWaitJobEnding(sitePath, ['en']);
    });

    after(() => {
        cy.login();
        deleteSite(siteKey);
        deleteUser(DEV);
        deleteUser(STORE_ADMIN);
    });

    it('the site import creates the security-advisories folder', () => {
        cy.login();
        cy.apollo({query: getNodeByPath, variables: {path: advisoriesPath}})
            .its('data.jcr.nodeByPath.primaryNodeType.name').should('eq', 'jnt:contentFolder');
    });

    it('lists the advisory whose published box is checked, without Jahia publication', () => {
        // B (box unchecked) is absent.
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

    it('a store administrator writes an advisory, which is listed at once', () => {
        errorsAs(storeAdminCreds, addNodeInWorkspace, addAdvisoryVars('EDIT', 'jsa-2026-9003', advisoryProps('JSA-2026-9003', 'medium', ['jcontent@3.8.2'])))
            .should('have.length', 0);
        advisoryIds().should('include', 'JSA-2026-9003');
    });

    it('positive control: the store developer writes in live, including in the advisories folder', () => {
        errorsAs(devCreds, setPropertyInWorkspace, {
            workspace: 'LIVE', pathOrId: `${modulePath}/${moduleName}-1.4.1`, name: 'securityNote', value: 'written in live'
        }).should('have.length', 0);
        errorsAs(devCreds, addNodeInWorkspace, addAdvisoryVars('LIVE', 'jsa-2026-9004', advisoryProps('JSA-2026-9004', 'critical', ['jahia-core@9.9.9.9'])))
            .should('have.length', 0);
    });

    it('what the store developer wrote in live never reaches the feed', () => {
        advisoryIds().should('not.include', 'JSA-2026-9004');
    });

    it('the store developer cannot create or change an advisory in the edit workspace', () => {
        errorsAs(devCreds, addNodeInWorkspace, addAdvisoryVars('EDIT', 'jsa-2026-9005', advisoryProps('JSA-2026-9005', 'critical', ['jahia-core@9.9.9.9'])))
            .should('have.length.greaterThan', 0);
        errorsAs(devCreds, setPropertyInWorkspace, {
            workspace: 'EDIT', pathOrId: `${advisoriesPath}/jsa-2026-9001`, name: 'severity', value: 'low'
        }).should('have.length.greaterThan', 0);
        errorsAs(devCreds, setPropertyInWorkspace, {
            workspace: 'EDIT', pathOrId: `${advisoriesPath}/jsa-2026-9001`, name: 'published', value: 'false'
        }).should('have.length.greaterThan', 0);

        advisoryIds().should('include', 'JSA-2026-9001').and('not.include', 'JSA-2026-9005');
        fetchFeedAnonymously().its('advisories').then(advisories => {
            expect((advisories as Advisory[]).find(a => a.id === 'JSA-2026-9001')!.severity).to.equal('high');
        });
    });

    it('an edit applies at once, and unchecking the box removes the advisory', () => {
        cy.login();
        errorsAs(null, setPropertyInWorkspace, {
            workspace: 'EDIT', pathOrId: `${advisoriesPath}/jsa-2026-9003`, name: 'severity', value: 'critical'
        }).should('have.length', 0);
        fetchFeedAnonymously().then(feed => {
            expect(feed.advisories.find(a => a.id === 'JSA-2026-9003')!.severity).to.equal('critical');
        });

        cy.login();
        errorsAs(null, setPropertyInWorkspace, {
            workspace: 'EDIT', pathOrId: `${advisoriesPath}/jsa-2026-9003`, name: 'published', value: 'false'
        }).should('have.length', 0);
        advisoryIds().should('deep.equal', ['JSA-2026-9001']);
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
        cy.login();
        // Positive control: the same advisory with a valid value is accepted. The identifier carries the
        // numeric suffix the security team uses (JSA-YYYY-NNNN.NNNN).
        errorsAs(null, addNodeInWorkspace, addAdvisoryVars('EDIT', 'good-fixed-in', advisoryProps('JSA-2026-0036.1066', 'high', ['jahia-core@8.2.3.9'], false)))
            .should('have.length', 0);
        // A malformed identifier suffix is refused.
        ['JSA-2026-0036.', 'JSA-2026-0036.10a'].forEach((id, i) => {
            errorsAs(null, addNodeInWorkspace, addAdvisoryVars('EDIT', `bad-id-${i}`, advisoryProps(id, 'high', ['jahia-core@8.2.3.9'], false)))
                .should('have.length.greaterThan', 0);
        });
        // "8.2..3" matters: the feed parser refuses "..", so the CND must refuse it too, or a published
        // advisory would pass jContent and then fail the whole feed.
        ['../../etc@1.0', 'jahia-core@8.2..3', 'jahia-core'].forEach((value, i) => {
            errorsAs(null, addNodeInWorkspace, addAdvisoryVars('EDIT', `bad-fixed-in-${i}`, advisoryProps('JSA-2026-9006', 'high', [value], false)))
                .should('have.length.greaterThan', 0);
        });
    });
});
