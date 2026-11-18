import {DocumentNode} from 'graphql';
import {createSite, deleteSite, publishAndWaitJobEnding} from '@jahia/cypress';

/**
 * JSTORE-SEC-012 — the media MIME guard ({@code ForgeMediaMimeListener}) covers the LIVE workspace and
 * has no ancestor-depth escape. Spec 28 covers the guard in EDIT; this spec writes where it used to be
 * blind:
 * <ul>
 *   <li><b>S1</b> — an SVG uploaded under a spoofed {@code image/png} mime straight into LIVE (store
 *       developers hold jcr:write_live and the store front-end writes in live). Before the fix only the
 *       default workspace was observed, so the file stayed and was served inline.</li>
 *   <li><b>S2</b> — the same file nested 40 folders under the module's {@code icon} folder, in LIVE.
 *       Before the fix the ancestor walk stopped after 32 nodes and skipped the file.</li>
 * </ul>
 * A genuine raster written in LIVE is the control and must survive. The writes run as root: the guard
 * does not depend on who writes, only on where. Waits are polls on node removal, not fixed sleeps.
 */
describe('Media MIME guard covers the live workspace and deep nesting (JSTORE-SEC-012)', () => {
    const siteKey = 'mediaMimeGuardLive';
    const repo = `/sites/${siteKey}/contents/modules-repository`;
    const iconFolder = `${repo}/widget/icon`;
    const nestingDepth = 40;
    const deepFolder = `${iconFolder}/${Array.from({length: nestingDepth}, (_, i) => `d${i + 1}`).join('/')}`;

    const createForgeModuleInWorkspace: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/createForgeModuleInWorkspace.graphql');
    const addNodeInWorkspace: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/addNodeInWorkspace.graphql');
    const uploadFileInWorkspace: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/uploadFileInWorkspace.graphql');
    const getNodePropertyInWorkspace: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/query/getNodePropertyInWorkspace.graphql');

    const existsInLive = (path: string) =>
        cy
            .apollo({
                query: getNodePropertyInWorkspace,
                variables: {workspace: 'LIVE', path, name: 'jcr:primaryType', language: null},
                fetchPolicy: 'no-cache',
                errorPolicy: 'all'
            })
            .then((res: { data?: { jcr?: { nodeByPath?: unknown } } }) => Boolean(res?.data?.jcr?.nodeByPath));

    const addFolderInLive = (parentPath: string, name: string) =>
        cy.apollo({
            mutation: addNodeInWorkspace,
            variables: {workspace: 'LIVE', parentPath, name, primaryNodeType: 'jnt:folder', properties: []}
        });

    const uploadInLive = (fixture: string, parentPath: string, name: string, mimeType: string) =>
        cy.fixture(fixture, 'binary').then(content => {
            const blob = Cypress.Blob.binaryStringToBlob(content, mimeType);
            const file = new File([blob], name, {type: blob.type});
            return cy.apollo({
                mutation: uploadFileInWorkspace,
                variables: {workspace: 'LIVE', parentPathOrId: parentPath, name, mimeType, file}
            });
        });

    const waitUntilRemovedFromLive = (path: string) =>
        cy.waitUntil(() => existsInLive(path).then(exists => exists === false), {
            timeout: 30000,
            interval: 1000,
            errorMsg: `${path} was not removed from LIVE by ForgeMediaMimeListener`
        });

    before(() => {
        cy.login();
        try {
            deleteSite(siteKey);
        } catch {
            // First run
        }

        createSite(siteKey, {
            languages: 'en',
            templateSet: 'jahia-store-template',
            serverName: 'mediamimeguardlive.local',
            locale: 'en'
        });
        // Publish the site so its module repository exists in LIVE, then build the module in LIVE only.
        publishAndWaitJobEnding(`/sites/${siteKey}`, ['en']);
        cy.apollo({
            mutation: createForgeModuleInWorkspace,
            variables: {workspace: 'LIVE', parentPath: repo, name: 'widget', title: 'Widget'}
        });
        addFolderInLive(`${repo}/widget`, 'icon');
        let parent = iconFolder;
        for (let i = 1; i <= nestingDepth; i++) {
            addFolderInLive(parent, `d${i}`);
            parent = `${parent}/d${i}`;
        }
    });

    after(() => {
        cy.login();
        deleteSite(siteKey);
    });

    it('S1: removes a spoofed SVG uploaded straight into LIVE and keeps a genuine raster', () => {
        uploadInLive('../../assets/icon.png', iconFolder, 'logo.webp', 'image/webp');
        uploadInLive('evil-markup.svg', iconFolder, 'evil.png', 'image/png');

        waitUntilRemovedFromLive(`${iconFolder}/evil.png`);
        existsInLive(`${iconFolder}/logo.webp`).should('equal', true);
    });

    it('S2: removes a spoofed SVG nested 40 folders deep under the module', () => {
        uploadInLive('evil-markup.svg', deepFolder, 'evil.png', 'image/svg+xml');

        waitUntilRemovedFromLive(`${deepFolder}/evil.png`);
    });
});
