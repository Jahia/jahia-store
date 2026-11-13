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
 * securityUpdates.json: the feed Jahia instances read to warn administrators about updates that fix
 * security issues (contentFolder.securityUpdates.jsp + SecurityFeed).
 *
 * Trust model under test:
 *   - advisories are read from the EDIT workspace and emitted only when fully published. Store
 *     developers hold jcr:write_live on the whole site, so anything they write directly in live (a
 *     forged advisory, a changed severity, a deleted live copy) must not change the feed;
 *   - owner-flagged versions (securityFix) follow moduleList.json visibility: published modules and
 *     published versions only.
 *
 * Everything is prepared in before() and the feed is fetched afterwards: the view is cached for
 * 10 minutes, so fixtures added after the first fetch would not be seen.
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

    const moduleName = 'cy-sec-module';
    const modulePath = `${repositoryPath}/${moduleName}`;

    const addNodeInWorkspace: DocumentNode =
        require('graphql-tag/loader!../fixtures/graphql/mutation/addNodeInWorkspace.graphql');
    const setPropertyInWorkspace: DocumentNode =
        require('graphql-tag/loader!../fixtures/graphql/mutation/setPropertyInWorkspace.graphql');
    const createForgeModule: DocumentNode =
        require('graphql-tag/loader!../fixtures/graphql/mutation/createForgeModule.graphql');
    const deleteNode: DocumentNode =
        require('graphql-tag/loader!../fixtures/graphql/mutation/deleteNodeInWorkspace.graphql');

    type Advisory = {
        id: string; title: string; severity: string; cvss?: number; cves: string[]; url?: string;
        published?: string; summary?: string;
        fixes: Array<{ component: string; groupId?: string; fixedVersions: string[] }>;
    };
    type Feed = {
        schemaVersion: number;
        advisories: Advisory[];
        flaggedVersions: Array<{ groupId: string; name: string; version: string; severity?: string; references: string[]; note?: string }>;
    };

    const advisoryProps = (id: string, severity: string, fixedIn: string[]) => [
        {name: 'jcr:title', value: `Advisory ${id}`, language: 'en'},
        {name: 'advisoryId', value: id},
        {name: 'severity', value: severity},
        {name: 'fixedIn', values: fixedIn}
    ];

    /** Root, EDIT workspace: what the security team does in jContent. */
    const addAdvisoryInEdit = (name: string, properties: object[]) =>
        cy.apollo({
            mutation: addNodeInWorkspace,
            variables: {workspace: 'EDIT', parentPath: advisoriesPath, name, primaryNodeType: 'jnt:forgeSecurityAdvisory', properties}
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

    /** The store developer, in their own session. */
    const asDeveloper = () => cy.apolloClient(devCreds, {log: true, setCurrentApolloClient: false});

    const fetchFeedAnonymously = () => {
        cy.clearCookies();
        return cy.request({url: feedUrl, failOnStatusCode: false}).then(res => {
            expect(res.status, 'feed status').to.equal(200);
            const body = typeof res.body === 'string' ? JSON.parse(res.body) : res.body;
            return body as Feed;
        });
    };

    before(() => {
        cy.login();
        try {
            deleteSite(siteKey);
        } catch {
            // First run.
        }
        try {
            deleteUser(DEV);
        } catch {
            // First run.
        }

        createSite(siteKey, {languages: 'en', templateSet: 'jahia-store-template', serverName: 'secfeed.local', locale: 'en'});
        createUser(DEV, DEV_PWD);
        grantRoles(sitePath, ['store-developer'], DEV, 'USER');

        // A: published, every optional field set.
        addAdvisoryInEdit('jsa-2026-9001', [
            ...advisoryProps('JSA-2026-9001', 'high', [
                'graphql-dxm-provider@3.10.0',
                'org.jahia.modules:graphql-dxm-provider@2.24.0',
                'jahia-core@8.2.3.7'
            ]),
            {name: 'cvss', value: '7.5'},
            {name: 'cves', values: ['CVE-2026-1111']},
            {name: 'advisoryUrl', value: 'https://academy.jahia.com/jsa-2026-9001'},
            {name: 'summary', value: 'Cypress summary', language: 'en'}
        ]);
        // C: published, then changed in EDIT and not republished.
        addAdvisoryInEdit('jsa-2026-9003', advisoryProps('JSA-2026-9003', 'medium', ['jcontent@3.8.2']));
        // E: published, then its live copy is deleted by the developer.
        addAdvisoryInEdit('jsa-2026-9005', advisoryProps('JSA-2026-9005', 'low', ['jcontent@3.8.3']));

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

        publishAndWaitJobEnding(sitePath, ['en']);

        // After publication: C gets a pending change, B is a draft that was never published.
        cy.apollo({
            mutation: setPropertyInWorkspace,
            variables: {workspace: 'EDIT', pathOrId: `${advisoriesPath}/jsa-2026-9003`, name: 'severity', value: 'critical'}
        });
        addAdvisoryInEdit('jsa-2026-9002', advisoryProps('JSA-2026-9002', 'critical', ['jahia-core@8.2.3.8']));

        // The store developer, directly in LIVE: forges D, downgrades A, deletes E's live copy.
        asDeveloper().apollo({
            mutation: addNodeInWorkspace,
            variables: {
                workspace: 'LIVE', parentPath: advisoriesPath, name: 'jsa-2026-9004',
                primaryNodeType: 'jnt:forgeSecurityAdvisory',
                properties: advisoryProps('JSA-2026-9004', 'critical', ['jahia-core@9.9.9.9'])
            }
        });
        asDeveloper().apollo({
            mutation: setPropertyInWorkspace,
            variables: {workspace: 'LIVE', pathOrId: `${advisoriesPath}/jsa-2026-9001`, name: 'severity', value: 'low'}
        });
        asDeveloper().apollo({
            mutation: deleteNode,
            variables: {workspace: 'LIVE', pathOrId: `${advisoriesPath}/jsa-2026-9005`}
        });
    });

    after(() => {
        cy.login();
        deleteSite(siteKey);
        deleteUser(DEV);
    });

    it('the module import creates the security-advisories folder in the site', () => {
        cy.login();
        cy.apollo({
            query: require('graphql-tag/loader!../fixtures/graphql/query/getNodeByPath.graphql'),
            variables: {path: advisoriesPath}
        }).its('data.jcr.nodeByPath.primaryNodeType.name').should('eq', 'jnt:contentFolder');
    });

    it('the developer really wrote in live (positive control for the tamper assertions)', () => {
        asDeveloper().apollo({
            query: require('graphql-tag/loader!../fixtures/graphql/query/getNodePropertyInWorkspace.graphql'),
            variables: {workspace: 'LIVE', path: `${advisoriesPath}/jsa-2026-9004`, name: 'advisoryId'},
            fetchPolicy: 'no-cache'
        }).its('data.jcr.nodeByPath.properties.0.value').should('eq', 'JSA-2026-9004');
    });

    it('emits exactly the fully published advisories, from the edit workspace', () => {
        fetchFeedAnonymously().then(feed => {
            expect(feed.schemaVersion).to.equal(1);
            const ids = feed.advisories.map(a => a.id).sort();
            // Set equality: B (draft), C (pending change) and D (forged in live) are absent; E stays
            // although its live copy was deleted.
            expect(ids).to.deep.equal(['JSA-2026-9001', 'JSA-2026-9005']);
        });
    });

    it('serves every field of an advisory, ignoring the developer\'s live change', () => {
        fetchFeedAnonymously().then(feed => {
            const a = feed.advisories.find(x => x.id === 'JSA-2026-9001')!;
            expect(a.title).to.equal('Advisory JSA-2026-9001');
            expect(a.severity, 'severity from edit, not the live downgrade').to.equal('high');
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

    it('lists owner-flagged versions of published modules, published versions only', () => {
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

    it('rejects a malformed fixedIn value at write time (CND constraint)', () => {
        cy.login();
        cy.apollo({
            mutation: addNodeInWorkspace,
            variables: {
                workspace: 'EDIT', parentPath: advisoriesPath, name: 'bad-fixed-in',
                primaryNodeType: 'jnt:forgeSecurityAdvisory',
                properties: advisoryProps('JSA-2026-9006', 'high', ['../../etc@1.0'])
            },
            errorPolicy: 'all'
        }).its('errors').should('have.length.greaterThan', 0);
    });
});
