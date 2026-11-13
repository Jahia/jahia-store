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
 * Trust model under test:
 *   - store developers hold jcr:write_live on the whole site, but the security-advisories folder does
 *     not inherit the site ACL: their live writes there (a forged advisory, a changed severity, a
 *     deleted live copy, a node squatting the name of a future advisory) are refused;
 *   - advisories are read from the EDIT workspace and emitted only when fully published, translations
 *     included: a pending change withdraws the advisory until it is published again;
 *   - owner-flagged versions (securityFix) follow moduleList.json visibility: published modules and
 *     published versions only.
 *
 * The view is not cached, so every fetch sees the current state.
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
        // E: published; the developer later tries to delete its live copy.
        addAdvisoryInEdit('jsa-2026-9005', advisoryProps('JSA-2026-9005', 'low', ['jcontent@3.8.3']));
        // F: published, then its translated title changes in EDIT and is not republished.
        addAdvisoryInEdit('jsa-2026-9008', advisoryProps('JSA-2026-9008', 'high', ['jcontent@3.8.4']));

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

        // After publication: C gets a pending change, F a pending change of its translated title, and B
        // is a draft that was never published.
        cy.apollo({
            mutation: setPropertyInWorkspace,
            variables: {workspace: 'EDIT', pathOrId: `${advisoriesPath}/jsa-2026-9003`, name: 'severity', value: 'critical'}
        });
        cy.apollo({
            mutation: setPropertyInWorkspace,
            variables: {workspace: 'EDIT', pathOrId: `${advisoriesPath}/jsa-2026-9008`, name: 'jcr:title', value: 'Edited title', language: 'en'}
        });
        addAdvisoryInEdit('jsa-2026-9002', advisoryProps('JSA-2026-9002', 'critical', ['jahia-core@8.2.3.8']));
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

    /** Runs a mutation as the store developer and yields its GraphQL errors (refused = non-empty). */
    const developerErrors = (mutation: DocumentNode, variables: object) =>
        asDeveloper().apollo({mutation, variables, errorPolicy: 'all'}).then(res => res.errors || []);

    it('positive control: the developer can write in live outside the advisories folder', () => {
        developerErrors(setPropertyInWorkspace, {
            workspace: 'LIVE', pathOrId: `${modulePath}/${moduleName}-1.4.1`, name: 'securityNote', value: 'written in live'
        }).should('have.length', 0);
    });

    it('refuses the developer\'s live writes in the advisories folder: forge, downgrade, delete', () => {
        developerErrors(addNodeInWorkspace, {
            workspace: 'LIVE', parentPath: advisoriesPath, name: 'jsa-2026-9004',
            primaryNodeType: 'jnt:forgeSecurityAdvisory',
            properties: advisoryProps('JSA-2026-9004', 'critical', ['jahia-core@9.9.9.9'])
        }).should('have.length.greaterThan', 0);
        developerErrors(setPropertyInWorkspace, {
            workspace: 'LIVE', pathOrId: `${advisoriesPath}/jsa-2026-9001`, name: 'severity', value: 'low'
        }).should('have.length.greaterThan', 0);
        developerErrors(deleteNode, {workspace: 'LIVE', pathOrId: `${advisoriesPath}/jsa-2026-9005`})
            .should('have.length.greaterThan', 0);
    });

    it('a name squatted in live cannot block the publication of a new advisory', () => {
        developerErrors(addNodeInWorkspace, {
            workspace: 'LIVE', parentPath: advisoriesPath, name: 'jsa-2026-9007',
            primaryNodeType: 'jnt:forgeSecurityAdvisory',
            properties: advisoryProps('JSA-2026-9007', 'low', ['jahia-core@9.9.9.9'])
        }).should('have.length.greaterThan', 0);

        cy.login();
        addAdvisoryInEdit('jsa-2026-9007', advisoryProps('JSA-2026-9007', 'critical', ['jahia-core@8.2.3.9']));
        publishAndWaitJobEnding(`${advisoriesPath}/jsa-2026-9007`, ['en']);

        fetchFeedAnonymously().then(feed => {
            const squatted = feed.advisories.find(a => a.id === 'JSA-2026-9007');
            expect(squatted, 'advisory published despite the squat attempt').to.not.be.undefined;
            expect(squatted!.severity).to.equal('critical');
        });
    });

    it('emits exactly the fully published advisories, from the edit workspace', () => {
        fetchFeedAnonymously().then(feed => {
            expect(feed.schemaVersion).to.equal(1);
            const ids = feed.advisories.map(a => a.id).filter(id => id !== 'JSA-2026-9007').sort();
            // Set equality: B (draft), C (pending change), F (pending translation change) and D (refused
            // in live) are absent. 9007 belongs to the squatting test, which may run before or after.
            expect(ids).to.deep.equal(['JSA-2026-9001', 'JSA-2026-9005']);
        });
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

    it('lists owner-flagged versions of published modules, published versions only', () => {
        // Published versions only: 1.4.3 is flagged but unpublished, 1.4.1 is published but not flagged.
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
        // "8.2..3" matters: the feed parser refuses "..", so the CND must refuse it too, or a published
        // advisory would pass jContent and then fail the whole feed.
        ['../../etc@1.0', 'jahia-core@8.2..3', 'jahia-core'].forEach((value, i) => {
            cy.apollo({
                mutation: addNodeInWorkspace,
                variables: {
                    workspace: 'EDIT', parentPath: advisoriesPath, name: `bad-fixed-in-${i}`,
                    primaryNodeType: 'jnt:forgeSecurityAdvisory',
                    properties: advisoryProps('JSA-2026-9006', 'high', [value])
                },
                errorPolicy: 'all'
            }).its('errors').should('have.length.greaterThan', 0);
        });
    });
});
