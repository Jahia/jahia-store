import {createSite, createUser, deleteSite, deleteUser, grantRoles, publishAndWaitJobEnding} from '@jahia/cypress';
import {GitModule, GitRepositoryInJahia, gitModulesOf, storeBranchSetting, storeFiles, unregisterGitModule} from '../support/gitStore';
import {MyModulesPage} from '../page-object/MyModulesPage';
import {ModulePage} from '../page-object/ModulePage';
import {Catalogue} from '../page-object/Catalogue';

/**
 * A module read from a git repository (docs/git-backed-modules.md): a store developer registers
 * the repository, the store serves the module from its STORE.md and its version files, and
 * nothing is edited in the store. The repository is served by Jahia itself, from the files of
 * the site, so the suite needs no git host.
 *
 * Requires the JS build of jahia-store-template.
 */
describe('A module read from a git repository', () => {
    const siteKey = 'gitstore';
    const developer = {username: 'gitdev', password: 'Gitdev#1234'};
    const repository = new GitRepositoryInJahia(siteKey, 'cy-git-module');
    // The store branch the instance had before the spec pinned it to the default branch of each repository.
    let storeBranchBefore = '';
    const islandBundle = '/modules/jahia-store-template/dist/client/components/forge/GitRefreshButton.client.tsx.js';

    const artifact = (version: string): string => `https://downloads.example.com/cy-git-module/cy-git-module-${version}.jar`;

    const sampleModule: GitModule = {
        name: 'cy-git-module',
        groupId: 'org.example.modules',
        title: 'Cypress Git Module',
        description: 'A module whose **description, versions and artefacts** live in a git repository. ![Overview](store/overview.png)',
        iconFile: 'assets/icon.png',
        tabs: {
            Installation: 'Drop the jar in the **modules** folder of Jahia.',
            License: 'MIT, in full.'
        },
        versions: [
            {
                number: '1.1.0',
                artifact: artifact('1.1.0'),
                date: '2026-02-15',
                tag: 'v1.1.0',
                tagUrl: 'https://git.example.com/cy-git-module/tags/v1.1.0',
                changelog: 'https://git.example.com/cy-git-module/changes/v1.1.0',
                notes: 'Second release, with a **faster** probe.'
            },
            {number: '1.0.0', artifact: artifact('1.0.0'), date: '2026-01-15', tag: 'v1.0.0', notes: 'First release.'},
            {number: '2.0.0', artifact: artifact('2.0.0'), date: '2026-03-15', disabled: true, notes: 'Not released yet.'}
        ]
    };

    /** The same module once 2.0.0 is released. */
    const sampleModuleWith200Released: GitModule = {
        ...sampleModule,
        versions: sampleModule.versions.map(v => (v.number === '2.0.0' ? {...v, disabled: false, notes: 'Released at last.'} : v))
    };

    const filesWithOverview = (module: GitModule) => ({...storeFiles(module), 'store/overview.png': {file: 'assets/icon.png'}});

    before(function () {
        cy.request({url: islandBundle, failOnStatusCode: false}).then(res => {
            if (res.status !== 200) {
                cy.log('jahia-store-template JS module not deployed — skipping git-backed modules spec');
                this.skip();
            }
        });
        cy.login();
        unregisterGitModule(siteKey, sampleModule.name);
        storeBranchSetting.read().then(branch => {
            storeBranchBefore = branch;
        });
        storeBranchSetting.write('');
        try {
            deleteSite(siteKey);
        } catch {
            // Ignore — first run.
        }

        createSite(siteKey, {languages: 'en', templateSet: 'jahia-store-template', serverName: 'gitstore.local', locale: 'en'});
        createUser(developer.username, developer.password);
        grantRoles(`/sites/${siteKey}`, ['store-developer'], developer.username, 'USER');
        publishAndWaitJobEnding(`/sites/${siteKey}`, ['en']);
        repository.publish(filesWithOverview(sampleModule));
    });

    after(() => {
        cy.login();
        unregisterGitModule(siteKey, sampleModule.name);
        deleteSite(siteKey);
        deleteUser(developer.username);
        storeBranchSetting.write(storeBranchBefore);
    });

    it('a store developer registers the repository from the My modules page, and finds the module there, marked as read from git', () => {
        cy.login(developer.username, developer.password);
        MyModulesPage.visit(siteKey)
            .registerRepository(repository.url())
            .module(sampleModule.title)
            .expectReadFromGit();
        gitModulesOf(siteKey).should(modules => {
            expect(modules.map(m => m.name)).to.include(sampleModule.name);
            expect(modules.find(m => m.name === sampleModule.name).mountStatus).to.equal('mounted');
        });
    });

    it('the page of the module shows what STORE.md says: the icon, the overview with its image, the tabs, and the download of the newest released version', () => {
        cy.logout();
        ModulePage.visit(siteKey, sampleModule.name)
            .expectTitle(sampleModule.title)
            .expectIcon()
            .expectOverview('live in a git repository')
            .expectOverviewImage('overview.png')
            .expectDownloadOf('1.1.0', artifact('1.1.0'))
            .expectNoControls()
            .tab('Installation')
            .open()
            .expectContent('Drop the jar in the modules folder');
        ModulePage.visit(siteKey, sampleModule.name).tab('License').open().expectContent('MIT, in full');
    });

    it('a visitor sees the released versions only, each with its notes, its artefact, its tag and its changelog', () => {
        cy.logout();
        const versions = ModulePage.visit(siteKey, sampleModule.name).openVersions();
        versions.expectCount(2).expectAbsent('2.0.0');
        versions
            .version('1.1.0')
            .expectReleased()
            .expectNotes('faster')
            .expectDownload(artifact('1.1.0'))
            .expectTag('v1.1.0', 'https://git.example.com/cy-git-module/tags/v1.1.0')
            .expectChangelogLink('https://git.example.com/cy-git-module/changes/v1.1.0');
        versions.version('1.0.0').expectReleased().expectNotes('First release');
    });

    it('the developer who registered the module sees the disabled version marked as such, and no editor', () => {
        cy.login(developer.username, developer.password);
        const page = ModulePage.visit(siteKey, sampleModule.name).expectReadFrom(repository.url()).expectNoEditor();
        page.openVersions().expectCount(3).version('2.0.0').expectDisabled().expectNotes('Not released yet');
    });

    it('the catalogue that Jahia servers read lists the released versions only', () => {
        Catalogue.of(siteKey).expectVersions(sampleModule.name, ['1.1.0', '1.0.0']);
    });

    it('a release pushed to the repository shows once the developer refreshes the module', () => {
        cy.login();
        repository.publish(filesWithOverview(sampleModuleWith200Released), 'release 2.0.0');
        cy.login(developer.username, developer.password);
        const page = ModulePage.visit(siteKey, sampleModule.name).expectDownloadOf('1.1.0', artifact('1.1.0'));
        page.refreshFromGit().expectDownloadOf('2.0.0', artifact('2.0.0'));
        page.openVersions().version('2.0.0').expectReleased().expectNotes('Released at last');
        Catalogue.of(siteKey).expectVersions(sampleModule.name, ['2.0.0', '1.1.0', '1.0.0']);
    });

    it('a repository that fails the contract is refused with the reason', () => {
        cy.login();
        new GitRepositoryInJahia(siteKey, 'broken-module').publish({'README.md': 'No STORE.md here.\n'});
        cy.login(developer.username, developer.password);
        MyModulesPage.visit(siteKey)
            .registerRepository(new GitRepositoryInJahia(siteKey, 'broken-module').url())
            .expectRegistrationRefused('STORE.md');
    });

    it('the developer removes the module from the store, and the repository is untouched', () => {
        cy.login(developer.username, developer.password);
        ModulePage.visit(siteKey, sampleModule.name).removeFromStore();
        new MyModulesPage().expectNoModule(sampleModule.title);
        gitModulesOf(siteKey).should(modules => {
            expect(modules.map(m => m.name)).to.not.include(sampleModule.name);
        });
        Catalogue.of(siteKey).expectNoModule(sampleModule.name);
        cy.request(`${repository.externalUrl()}/HEAD`).its('body').should('contain', 'refs/heads/main');
    });
});
