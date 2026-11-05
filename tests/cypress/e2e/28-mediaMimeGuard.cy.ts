import {DocumentNode} from 'graphql';
import {createSite, deleteSite, uploadFile} from '@jahia/cypress';

/**
 * S34 / U1 — end-to-end proof that {@code ForgeMediaMimeListener} + {@code MagicByteImageValidator}
 * neutralize the stored-XSS surface: a file planted under a {@code jmix:forgeElement} whose bytes are
 * actually script-capable/markup (declared as {@code image/png}) is asynchronously REMOVED, while a
 * genuine raster survives untouched.
 *
 * <p>Files are seeded with the {@code @jahia/cypress} {@code uploadFile} multipart helper (the same
 * primitive the authoring UI uses), which lands the REAL bytes on the {@code jnt:resource} — the
 * fidelity the guard's magic-byte sniff depends on. (An earlier revision seeded {@code jcr:data} as a
 * base64 property; Jahia does not decode that inline, so the stored bytes were the base64 text and
 * even the legit raster was seen as non-raster. The multipart upload is the reliable path.)</p>
 *
 * <ul>
 *   <li><b>evil.png</b> — an SVG carrying an inline {@code <script>} (fixture {@code evil-markup.svg}),
 *       uploaded under a spoofed {@code image/png} mime. Its leading byte is {@code '<'} →
 *       {@code looksLikeMarkup} → Rule A removes it.</li>
 *   <li><b>logo.png</b> — a genuine PNG ({@code assets/icon.png}); detected == declared → survives.</li>
 * </ul>
 * The wait is a deterministic poll on node-removal, not a fixed timeout.
 */
describe('Media MIME guard removes spoofed/script-capable uploads (stored-XSS)', () => {
    const siteKey = 'mediaMimeGuard';
    const repo = `/sites/${siteKey}/contents/modules-repository`;
    const iconFolder = `${repo}/widget/icon`;
    const evilPath = `${iconFolder}/evil.png`;
    const legitPath = `${iconFolder}/logo.webp`;

    const createForgeModule: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/createForgeModule.graphql');
    const addNodeWithProperties: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/addNodeWithProperties.graphql');
    const getNodeByPath: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/query/getNodeByPath.graphql');

    const nodeExists = (path: string) =>
        cy
            .apollo({query: getNodeByPath, variables: {path}, fetchPolicy: 'no-cache', errorPolicy: 'all'})
            .then((res: { data?: { jcr?: { nodeByPath?: unknown } } }) => Boolean(res?.data?.jcr?.nodeByPath));

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
            serverName: 'mediamimeguard.local',
            locale: 'en'
        });
        cy.apollo({mutation: createForgeModule, variables: {parentPath: repo, name: 'widget', title: 'Widget'}});
        // The forge module's CND defines `+ icon (jnt:folder)`, so the icon media folder MUST be a
        // jnt:folder (a jnt:contentFolder violates the child-node definition and is rejected).
        cy.apollo({
            mutation: addNodeWithProperties,
            variables: {
                parentPath: `${repo}/widget`,
                name: 'icon',
                primaryNodeType: 'jnt:folder',
                properties: [],
                mixins: []
            }
        });

        // A genuine raster (control, must survive) and the spoofed markup-as-PNG (attack, removed).
        // uploadFile paths are relative to cypress/fixtures; assets/ is two levels up. assets/icon.png
        // is a real WebP, so declare it image/webp — detected == declared → left untouched.
        uploadFile('../../assets/icon.png', iconFolder, 'logo.webp', 'image/webp');
        uploadFile('evil-markup.svg', iconFolder, 'evil.png', 'image/png');
    });

    after(() => {
        cy.login();
        deleteSite(siteKey);
    });

    it('removes the spoofed script-capable file and keeps the genuine raster', () => {
        // Poll until the async listener removes the offending node (both workspaces are checked
        // server-side). Deterministic wait, not a fixed sleep.
        cy.waitUntil(() => nodeExists(evilPath).then(exists => exists === false), {
            timeout: 30000,
            interval: 1000,
            errorMsg: 'spoofed file was not removed by ForgeMediaMimeListener'
        });

        // The legitimate raster must survive untouched.
        nodeExists(legitPath).should('equal', true);
    });
});
