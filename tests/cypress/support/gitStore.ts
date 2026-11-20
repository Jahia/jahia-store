import {DocumentNode} from 'graphql';
import {addNode, publishAndWaitJobEnding} from '@jahia/cypress';

/**
 * A module as a git repository holds it (docs/git-backed-modules.md of jahia-store): the fields
 * of STORE.md, the description, the tabs and the versions. `storeFiles` renders it to the files
 * of the repository, so a spec describes a module and never writes Markdown.
 */
export interface GitModuleVersion {
    number: string
    artifact: string
    date?: string
    tag?: string
    tagUrl?: string
    changelog?: string
    disabled?: boolean
    notes?: string
}

export interface GitModule {
    name: string
    groupId: string
    title: string
    description: string
    license?: string
    /** A file of the tests folder, served as the icon. */
    iconFile?: string
    /** The tabs of the page, by title, each with its Markdown content. */
    tabs?: Record<string, string>
    versions: GitModuleVersion[]
}

type RepositoryFiles = Record<string, string | {file: string}>;

const frontMatter = (fields: Record<string, string | boolean | undefined>): string =>
    '---\n' +
    Object.entries(fields)
        .filter(([, value]) => value !== undefined)
        .map(([key, value]) => `${key}: ${value}\n`)
        .join('') +
    '---\n';

const fileNameOf = (title: string): string => title.toLowerCase().replace(/[^a-z0-9]+/g, '-');

/** The files of the repository of a module, to the contract of the store. */
export function storeFiles(module: GitModule): RepositoryFiles {
    const files: RepositoryFiles = {};
    const tabs = Object.keys(module.tabs || {});
    files['STORE.md'] =
        frontMatter({
            name: module.name,
            groupId: module.groupId,
            title: module.title,
            license: module.license,
            icon: module.iconFile ? 'store/icon.png' : undefined
        }) +
        `\n${module.description}\n\n## Versions\n\n` +
        module.versions.map(v => `- [${v.number}](store/${v.number})\n`).join('') +
        (tabs.length ? `\n## Tabs\n\n${tabs.map(t => `- [${t}](store/${fileNameOf(t)}.md)\n`).join('')}` : '');
    if (module.iconFile) {
        files['store/icon.png'] = {file: module.iconFile};
    }

    for (const title of tabs) {
        files[`store/${fileNameOf(title)}.md`] = `${module.tabs[title]}\n`;
    }

    for (const version of module.versions) {
        files[`store/${version.number}/VERSION.md`] =
            frontMatter({
                artifact: version.artifact,
                date: version.date,
                tag: version.tag,
                tagUrl: version.tagUrl,
                changelog: version.changelog,
                disabled: version.disabled ? true : undefined
            }) + `\n${version.notes || ''}\n`;
    }

    return files;
}

const deleteNodeDoc: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/deleteNode.graphql');
const unregisterDoc: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/forgeUnregisterGitModule.graphql');
const gitModulesDoc: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/query/forgeGitModules.graphql');

/**
 * A git repository served by Jahia itself, under the files of the site, which the store fetches
 * through the file servlet of the live workspace. The URL the store is given is the one Jahia
 * reaches itself with, inside its container: `JAHIA_INTERNAL_URL`, `http://localhost:8080` by
 * default.
 */
export class GitRepositoryInJahia {
    private readonly folder: string;

    constructor(private readonly siteKey: string, private readonly name: string) {
        this.folder = `/sites/${siteKey}/files/git/${name}.git`;
    }

    /** The URL the store registers: the one Jahia reaches itself with. */
    url(): string {
        return `${GitRepositoryInJahia.internalUrl()}/files/live${this.folder}`;
    }

    /** The URL of the same repository from the browser of the test. */
    externalUrl(): string {
        return `/files/live${this.folder}`;
    }

    static internalUrl(): string {
        return Cypress.env('JAHIA_INTERNAL_URL') || 'http://localhost:8080';
    }

    /** Replace the content of the repository with these files, as one commit, and publish it. */
    publish(files: RepositoryFiles, message = 'store data'): void {
        cy.log(`repository ${this.name}: ${Object.keys(files).length} files`);
        cy.apollo({mutation: deleteNodeDoc, variables: {path: this.folder}, errorPolicy: 'all'});
        cy.task<string[]>('buildGitRepository', {name: this.name, files, message}).then(paths => {
            const folders = new Set<string>();
            for (const p of paths) {
                const parts = p.split('/');
                for (let i = 1; i < parts.length; i++) {
                    folders.add(parts.slice(0, i).join('/'));
                }
            }

            this.folderNode(`/sites/${this.siteKey}/files`, 'git');
            this.folderNode(`/sites/${this.siteKey}/files/git`, `${this.name}.git`);
            for (const folder of Array.from(folders).sort()) {
                const slash = folder.lastIndexOf('/');
                this.folderNode(`${this.folder}${slash < 0 ? '' : '/' + folder.slice(0, slash)}`, folder.slice(slash + 1));
            }

            for (const p of paths) {
                const slash = p.lastIndexOf('/');
                const parent = slash < 0 ? this.folder : `${this.folder}/${p.slice(0, slash)}`;
                this.uploadFile(`cypress/fixtures/git/${this.name}/${p}`, parent, p.slice(slash + 1));
            }
        });
        publishAndWaitJobEnding(`/sites/${this.siteKey}/files/git`, ['en']);
    }

    /**
     * Upload a file of the tests folder as a jnt:file, through the upload mutation of the library.
     * The `uploadFile` helper of the library reads a fixture, and the fixtures folder is the one of
     * the library, so the generated files are read by their path from the tests folder instead.
     */
    private uploadFile(path: string, parentPathOrId: string, name: string): void {
        cy.readFile(path, 'binary').then((content: string) => {
            const blob = Cypress.Blob.binaryStringToBlob(content, 'application/octet-stream');
            const file = new File([blob], name, {type: blob.type});
            cy.apollo({
                mutationFile: 'graphql/jcr/mutation/uploadFile.graphql',
                variables: {parentPathOrId, name, mimeType: 'application/octet-stream', file}
            });
        });
    }

    /** A folder that may exist already: the add fails quietly then. */
    private folderNode(parent: string, name: string): void {
        addNode({parentPathOrId: parent, name, primaryNodeType: 'jnt:folder'}, {errorPolicy: 'all'});
    }
}

const getBranchDoc: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/query/getGitStoreBranch.graphql');
const setBranchDoc: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/setGitStoreBranch.graphql');

/**
 * The store branch of the module configuration, which every registered repository is read from:
 * empty for the default branch of each repository. A spec pins it and puts it back.
 */
export const storeBranchSetting = {
    read(): Cypress.Chainable<string> {
        return cy
            .apollo({query: getBranchDoc, fetchPolicy: 'no-cache'})
            .then(result => cy.wrap((result.data?.admin?.jahia?.configuration?.value as string) || '', {log: false}));
    },
    write(branch: string): void {
        cy.apollo({mutation: setBranchDoc, variables: {branch}});
    }
};

/** The registrations of the store of a site, as the administration GraphQL lists them. */
export function gitModulesOf(siteKey: string): Cypress.Chainable<{name: string; gitUrl: string; mountStatus: string}[]> {
    return cy.apollo({query: gitModulesDoc, variables: {siteKey}, fetchPolicy: 'no-cache'}).its('data.forge.gitModules');
}

/** Remove a registration, as an administrator does; nothing happens when there is none. */
export function unregisterGitModule(siteKey: string, name: string): void {
    cy.apollo({mutation: unregisterDoc, variables: {siteKey, name}, errorPolicy: 'all'});
}
