import {DocumentNode} from 'graphql';
import {createSite, deleteSite, publishAndWaitJobEnding, setNodeProperty, uploadFile} from '@jahia/cypress';

/**
 * JSTORE-SEC-005 — an anonymous visitor must not reach unpublished store content through any path, not only
 * through the rendered module page (spec 30). Each path applies the module's one published-only rule
 * (`StoreVisibility`): a module or package and each of its versions carry a `published` flag, and content
 * under a draft is a draft.
 *
 * The fixture, published to live (publishing the site makes the nodes readable in live; their `published`
 * flags stay as set):
 *   - `released` (published) with version 1.0.0 (published, artifact attached) and version 2.0.0 (DRAFT,
 *     artifact attached): the positive control and a draft version of a published module;
 *   - `draftmod` (DRAFT module) with version 1.0.0 (published, artifact attached): the published version of an
 *     unpublished module, which the RSS feed used to list (SEC-1015);
 *   - `proxyreleased` (published) and `proxydraft` (DRAFT), Maven-deployed versions with no attached artifact,
 *     their JARs in the stack's Nexus, served through /modules/mavenproxy.
 *
 * Every refusal is paired with a positive control on the same path, so a test cannot pass because the path is
 * broken. Owner access (jcr:write, here root) to drafts is checked where the module keeps it (/files, proxy).
 * moduleList.json is unchanged: its contract is locked by spec 14; this spec only checks that it still lists
 * released content and no draft.
 */
describe('Anonymous visitors do not reach unpublished store content (JSTORE-SEC-005)', () => {
    const siteKey = 'draftDisclosure';
    const groupId = 'org.cypress.disclosure';
    const groupPath = groupId.replace(/\./g, '/');
    const repo = `/sites/${siteKey}/contents/modules-repository`;
    const jsonUrl = `/en/sites/${siteKey}/contents/modules-repository.moduleList.json`;
    const rssUrl = `/en/sites/${siteKey}/contents/modules-repository.moduleList.rss`;
    const feedUrl = `/en/sites/${siteKey}/contents/modules-repository.securityUpdates.json`;

    const artifactFixture = '../../assets/sample-module.jar';
    const artifactName = 'sample-module.jar';

    const createForgeModule: DocumentNode =
        require('graphql-tag/loader!../fixtures/graphql/mutation/createForgeModule.graphql');
    const addNodeWithProps: DocumentNode =
        require('graphql-tag/loader!../fixtures/graphql/mutation/addNodeWithProperties.graphql');
    const updateForgeSettings: DocumentNode =
        require('graphql-tag/loader!../fixtures/graphql/mutation/updateForgeSettings.graphql');

    const versionPath = (module: string, version: string) => `${repo}/${module}/${module}-${version}`;
    const filesUrl = (module: string, version: string) => `/files/live${versionPath(module, version)}/${artifactName}`;
    const proxyUrl = (module: string, version: string) =>
        `/modules/mavenproxy/${siteKey}/${groupPath}/${module}/${version}/${module}-${version}.jar`;

    const forgeRepo = 'http://nexus:8081/repository/maven-releases'; // Jahia (container) view
    const nexusUser = Cypress.env('NEXUS_USERNAME') || 'admin';
    const nexusPass = Cypress.env('NEXUS_PASSWORD') || 'admin123';
    const putRepo = Cypress.env('NEXUS_URL') ? `${Cypress.env('NEXUS_URL')}/repository/maven-releases` : forgeRepo;

    const addModule = (name: string, published: boolean) => {
        cy.apollo({mutation: createForgeModule, variables: {parentPath: repo, name, title: `Disclosure ${name}`}});
        setNodeProperty(`${repo}/${name}`, 'groupId', groupId, 'en');
        setNodeProperty(`${repo}/${name}`, 'published', String(published), 'en');
    };

    const addVersion = (module: string, version: string, published: boolean, withArtifact: boolean) => {
        cy.apollo({
            mutation: addNodeWithProps,
            variables: {
                parentPath: `${repo}/${module}`,
                name: `${module}-${version}`,
                primaryNodeType: 'jnt:forgeModuleVersion',
                properties: [{name: 'versionNumber', value: version}]
            }
        });
        if (withArtifact) {
            uploadFile(artifactFixture, versionPath(module, version), artifactName, 'application/java-archive');
        }

        setNodeProperty(versionPath(module, version), 'published', String(published), 'en');
    };

    /** Deploys the fixture JAR to Nexus at the proxy path of module/version (a re-run's PUT is a no-op). */
    const deployToNexus = (module: string, version: string) => {
        cy.fixture(artifactFixture, 'binary').then(bin => {
            const bytes = Uint8Array.from(bin as unknown as string, c => c.charCodeAt(0));
            cy.request({
                method: 'PUT',
                url: `${putRepo}/${groupPath}/${module}/${version}/${module}-${version}.jar`,
                auth: {user: nexusUser, pass: nexusPass},
                headers: {'Content-Type': 'application/java-archive'},
                body: bytes.buffer,
                failOnStatusCode: false
            });
        });
    };

    const statusOf = (url: string) =>
        cy.request({url, failOnStatusCode: false, followRedirect: false, encoding: 'binary'}).its('status');

    before(() => {
        cy.login();
        try {
            deleteSite(siteKey);
        } catch {
            // First run
        }

        createSite(siteKey, {languages: 'en', templateSet: 'jahia-store-template', serverName: 'localhost', locale: 'en'});

        addModule('released', true);
        addVersion('released', '1.0.0', true, true);
        addVersion('released', '2.0.0', false, true);

        addModule('draftmod', false);
        addVersion('draftmod', '1.0.0', true, true);

        addModule('proxyreleased', true);
        addVersion('proxyreleased', '1.0.0', true, false);
        addModule('proxydraft', false);
        addVersion('proxydraft', '1.0.0', true, false);

        cy.apollo({
            mutation: updateForgeSettings,
            variables: {siteKey, url: forgeRepo, id: 'remote-repository', user: nexusUser, password: nexusPass}
        });
        deployToNexus('proxyreleased', '1.0.0');
        deployToNexus('proxydraft', '1.0.0');

        publishAndWaitJobEnding(`/sites/${siteKey}`, ['en']);
    });

    after(() => {
        cy.login();
        deleteSite(siteKey);
    });

    it('S1: the RSS feed lists the released version only, never a version of a draft module or a draft version', () => {
        cy.logout();
        cy.request({url: rssUrl, failOnStatusCode: false}).then(res => {
            expect(res.status).to.equal(200);
            const body = String(res.body);
            expect(body, 'positive control: the released version is listed').to.contain('released-1.0.0');
            expect(body, 'published version of an UNPUBLISHED module').to.not.contain('draftmod');
            expect(body, 'draft version of a published module').to.not.contain('released-2.0.0');
            expect(body, 'Maven-deployed version of an UNPUBLISHED module').to.not.contain('proxydraft');
        });
    });

    it('S2: /files serves the released artifact but answers 404 for a draft artifact, like a missing file', () => {
        cy.logout();
        statusOf(filesUrl('released', '1.0.0')).should('equal', 200);
        statusOf(filesUrl('released', '9.9.9')).then(missing => {
            expect(missing, 'a missing artifact').to.equal(404);
            statusOf(filesUrl('released', '2.0.0')).should('equal', missing);
            statusOf(filesUrl('draftmod', '1.0.0')).should('equal', missing);
        });
    });

    it('S2: the owner side (jcr:write) still downloads the draft artifacts from /files', () => {
        cy.login();
        statusOf(filesUrl('released', '2.0.0')).should('equal', 200);
        statusOf(filesUrl('draftmod', '1.0.0')).should('equal', 200);
    });

    it('S2: /modules/mavenproxy streams the released artifact but answers 404 for a draft module, without fetching it', () => {
        cy.logout();
        statusOf(proxyUrl('proxyreleased', '1.0.0')).should('equal', 200);
        statusOf(proxyUrl('proxydraft', '1.0.0')).should('equal', 404);
        statusOf(`/modules/mavenproxy/${siteKey}/${groupPath}/proxydraft/maven-metadata.xml`).should('equal', 404);
    });

    it('S2: the owner side (jcr:write) still downloads the draft artifact through the proxy', () => {
        cy.login();
        statusOf(proxyUrl('proxydraft', '1.0.0')).should('equal', 200);
    });

    it('S3: a draft module page and a missing page answer the same', () => {
        cy.logout();
        statusOf(`/cms/render/live/en${repo}/no-such-module.html`).then(missing => {
            statusOf(`/cms/render/live/en${repo}/draftmod.html`).should('equal', missing);
        });
    });

    it('S5/S6: the JSON views are served at the store folder only', () => {
        cy.logout();
        // Positive controls: the store folder serves both views.
        statusOf(jsonUrl).should('equal', 200);
        statusOf(feedUrl).should('equal', 200);
        // Any other content folder: the anonymous grant no longer applies there.
        [`/en/sites/${siteKey}/contents`, `/en/sites/${siteKey}/contents/security-advisories`].forEach(folder => {
            [`${folder}.moduleList.json`, `${folder}.securityUpdates.json`].forEach(url => {
                cy.request({url, failOnStatusCode: false}).then(res => {
                    expect(res.status, url).to.not.equal(200);
                    expect(String(res.body), `${url} must not carry a feed`).to.not.contain('"advisories"').and.not.contain('"modules"');
                });
            });
        });
    });

    it('moduleList.json still lists the released content and no draft (the published contract is unchanged)', () => {
        cy.logout();
        cy.request(jsonUrl).then(res => {
            const body = typeof res.body === 'string' ? JSON.parse(res.body) : res.body;
            const modules: Array<{name: string; versions: Array<{version: string}>}> = body[0].modules;
            const names = modules.map(m => m.name);
            expect(names).to.include.members(['released', 'proxyreleased']);
            expect(names).to.not.include.members(['draftmod']);
            expect(names).to.not.include('proxydraft');
            const released = modules.find(m => m.name === 'released');
            expect(released?.versions.map(v => v.version)).to.deep.equal(['1.0.0']);
        });
    });
});
