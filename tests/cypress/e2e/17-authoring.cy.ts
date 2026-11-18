import {DocumentNode} from 'graphql';
import {createSite, deleteSite, setNodeProperty, uploadFile} from '@jahia/cypress';
import {createSharedCategory, deleteSharedCategory} from '../support/sharedCategories';

/**
 * Click the editor's Save and wait for the full-page reload it triggers (the
 * editor reloads so the SSR'd values refresh without a manual refresh). Stamping
 * `window` before the click and waiting for the stamp to vanish is reload-safe —
 * a window object doesn't "detach" like a DOM element would mid-navigation, so no
 * assertion ends up straddling the reload.
 */
const saveAndWaitReload = (): void => {
    cy.window().then(w => {
        (w as unknown as {__preSave?: boolean}).__preSave = true;
    });
    // The editor Save is an icon-only button (tooltip + aria-label); target its
    // stable data hook rather than visible text.
    cy.get('[data-editor-save]').click();
    cy.window({timeout: 20000}).should(w => {
        expect((w as unknown as {__preSave?: boolean}).__preSave).to.be.undefined;
    });
};

/**
 * Open the module detail page's "Versions" popup (replaced the old Versions tab).
 * Waits for the trigger island to hydrate (data-versions-ready) before clicking,
 * then for the native <dialog> to be open — so the per-version controls inside it
 * are visible/interactive.
 */
const openVersionsPopup = (): void => {
    cy.get('[data-versions-open][data-versions-ready="true"]', {timeout: 20000}).click();
    cy.get('[data-versions-dialog][open]', {timeout: 20000}).should('be.visible');
};

/**
 * Open the module editor and switch to its Media tab, where the screenshot and
 * video managers live. Scope the tab click to the editor tablist ("Module
 * fields") since the detail page also has a "Module sections" tablist. Waits for
 * the screenshot manager to hydrate.
 */
const openEditorMediaTab = (): void => {
    // Wait for the editor island to hydrate before clicking (the SSR button is
    // visible before its handler attaches).
    cy.get('[data-editor-ready]', {timeout: 20000});
    cy.contains('button', /edit module/i).click();
    cy.get('[aria-label="Module fields"]', {timeout: 20000}).contains('[role="tab"]', 'Media').click();
    cy.get('[data-screenshots-ready]', {timeout: 20000});
};

/**
 * Authoring views (jahia-store-template JS module) — Phase 3.
 *
 * Covers in-site editing of module metadata via the ModuleEditor island, which
 * saves through the generic jcr GraphQL mutations (session-authenticated, JCR
 * ACLs apply — no custom Java action).
 *
 * Requires the JS build of jahia-store-template.
 */
describe('Authoring views (JS module)', () => {
    const siteKey = 'authoring';
    const repo = `/sites/${siteKey}/contents/modules-repository`;
    const moduleRender = `/cms/render/default/en${repo}/widget.html`;

    const createForgeModule: DocumentNode =
        require('graphql-tag/loader!../fixtures/graphql/mutation/createForgeModule.graphql');

    const addNodeWithProps: DocumentNode =
        require('graphql-tag/loader!../fixtures/graphql/mutation/addNodeWithProperties.graphql');

    const getNodeProperty: DocumentNode =
        require('graphql-tag/loader!../fixtures/graphql/query/getNodeProperties.graphql');

    // Category-settings fixtures (same OSGi-backed path the admin UI uses), to verify
    // the editor lists the configured categories.
    // Root categories must be in the shared tree /sites/systemsite/categories (JSTORE-SEC-001);
    // deleteSite does not remove them, so after() deletes them.
    const sharedRootNames = ['cy-17-cats', 'cy-17-cats2'];

    const setRootCategory: DocumentNode =
        require('graphql-tag/loader!../fixtures/graphql/mutation/setRootCategory.graphql');

    const addForgeCategory: DocumentNode =
        require('graphql-tag/loader!../fixtures/graphql/mutation/addForgeCategory.graphql');

    const readProp = (path: string, name: string) =>
        cy.apollo({query: getNodeProperty, variables: {path, name, language: null}, fetchPolicy: 'no-cache'});

    const islandBundle = '/modules/jahia-store-template/dist/client/components/forge/ModuleEditor.client.tsx.js';

    before(function () {
        cy.request({url: islandBundle, failOnStatusCode: false}).then(res => {
            if (res.status !== 200) {
                cy.log('jahia-store-template JS module not deployed — skipping authoring spec');
                this.skip();
            }
        });
        cy.login();
        try {
            deleteSite(siteKey);
        } catch {
            // ignore
        }

        createSite(siteKey, {
            languages: 'en',
            templateSet: 'jahia-store-template',
            serverName: 'authoring.local',
            locale: 'en'
        });
        // The jahia-store-template import.xml provides modules-repository + the
        // home/my-modules page (which hosts the JAR upload form).
        cy.apollo({
            mutation: createForgeModule,
            variables: {parentPath: repo, name: 'widget', title: 'Widget'}
        });
        setNodeProperty(`${repo}/widget`, 'description', '<p>Original description.</p>', 'en');
        setNodeProperty(`${repo}/widget`, 'published', 'true', 'en');
        // An unpublished version, so the per-version publish control has something
        // to flip (the module's own published flag starts true).
        cy.apollo({
            mutation: addNodeWithProps,
            variables: {
                parentPath: `${repo}/widget`,
                name: 'widget-1.0.0',
                primaryNodeType: 'jnt:forgeModuleVersion',
                properties: [
                    {name: 'versionNumber', value: '1.0.0'},
                    {name: 'published', value: 'false'}
                ]
            }
        });

        // A second module with TWO versions, dedicated to the delete-version test —
        // so widget keeps exactly one version (the publish-control test relies on a
        // single [data-forge-version] being unambiguous).
        cy.apollo({
            mutation: createForgeModule,
            variables: {parentPath: repo, name: 'multiver', title: 'Multi Version'}
        });
        setNodeProperty(`${repo}/multiver`, 'published', 'true', 'en');
        [
            {name: 'multiver-1.0.0', num: '1.0.0'},
            {name: 'multiver-2.0.0', num: '2.0.0'}
        ].forEach(v => {
            cy.apollo({
                mutation: addNodeWithProps,
                variables: {
                    parentPath: `${repo}/multiver`,
                    name: v.name,
                    primaryNodeType: 'jnt:forgeModuleVersion',
                    properties: [
                        {name: 'versionNumber', value: v.num},
                        {name: 'published', value: 'true'}
                    ]
                }
            });
        });
    });

    after(() => {
        cy.login();
        deleteSite(siteKey);
        sharedRootNames.forEach(name => deleteSharedCategory(name));
    });

    beforeEach(() => {
        cy.login();
    });

    it('shows the in-site editor to a user with write permission', () => {
        cy.visit(moduleRender);
        cy.contains('button', /edit module/i, {timeout: 20000}).should('be.visible');
    });

    it('edits module metadata across tabs and persists via GraphQL', () => {
        cy.visit(moduleRender);
        // Wait for the editor island to hydrate before clicking (the SSR button
        // is visible before its handler is attached).
        cy.get('[data-editor-ready]', {timeout: 20000});
        cy.contains('button', /edit module/i).click();
        // General tab is active by default — the title lives here.
        cy.get('#edit-jcr-title', {timeout: 10000}).should('have.value', 'Widget').clear().type('Widget Pro');
        // Code repository is on the Author & links tab.
        cy.contains('[role="tab"]', /author/i).click();
        cy.get('#edit-codeRepository').clear().type('https://github.com/acme/widget');
        // Saving reloads the page so the SSR'd values refresh immediately (no manual
        // refresh); wait for that reload, then assert the persisted values.
        saveAndWaitReload();
        cy.get('h1', {timeout: 20000}).should('contain.text', 'Widget Pro');
        cy.contains('a', 'https://github.com/acme/widget').should('exist');
    });

    it('chooses the author display mode (authorNameDisplayedAs) and persists it', () => {
        cy.visit(moduleRender);
        cy.get('[data-editor-ready]', {timeout: 20000});
        cy.contains('button', /edit module/i).click();
        // The author-display selector (Email / First and last name / Organization) is on
        // the Author tab; its option values are the authorNameDisplayedAs choicelist keys.
        cy.contains('[role="tab"]', /author/i).click();
        cy.get('#edit-author-display', {timeout: 10000}).select('fullName');
        saveAndWaitReload();
        // Re-open the editor: the persisted choice is reflected back.
        cy.get('[data-editor-ready]', {timeout: 20000});
        cy.contains('button', /edit module/i).click();
        cy.contains('[role="tab"]', /author/i).click();
        cy.get('#edit-author-display', {timeout: 10000}).should('have.value', 'fullName');
    });

    it('edits status + tags on the General tab and persists them', () => {
        cy.visit(moduleRender);
        cy.get('[data-editor-ready]', {timeout: 20000});
        cy.contains('button', /edit module/i).click();
        // Status (choicelist) + tags (j:tagList) are on the General tab. They use
        // the same non-i18n setValue / multi-value setValues paths as category.
        cy.get('#edit-status', {timeout: 10000}).select('legacy');
        cy.get('#edit-tags').type('analytics{enter}charts{enter}');
        // Wait for the save-triggered reload before asserting. Scope the status check to the
        // detail Information rail's Status field: a bare cy.contains(/legacy/) would also match
        // the editor's own status <select> "legacy" <option>, which would make the assertion
        // pass for the wrong reason (or fail on visibility).
        saveAndWaitReload();
        cy.get('[data-detail-info]', {timeout: 20000})
            .contains('dt', /status/i)
            .next('dd')
            .should('contain.text', 'legacy');
        cy.get('[data-tag-list]').should('contain.text', 'analytics').and('contain.text', 'charts');
    });

    it('keyboard-navigates the editor tabs (roving tabindex)', () => {
        cy.visit(moduleRender);
        cy.get('[data-editor-ready]', {timeout: 20000});
        cy.contains('button', /edit module/i).click();
        // Scope to the editor's tablist ("Module fields") — the detail page now also
        // has a section tablist ("Module sections"), so a bare [role=tab] is ambiguous.
        cy.get('[aria-label="Module fields"]', {timeout: 10000}).as('editorTabs');
        // The active tab is the only one in the tab order; ArrowRight moves + selects.
        cy.get('@editorTabs').find('[role="tab"][aria-selected="true"]').should('contain.text', 'General');
        cy.get('@editorTabs').find('[role="tab"][aria-selected="true"]').type('{rightarrow}');
        cy.get('@editorTabs').find('[role="tab"][aria-selected="true"]').should('contain.text', 'Description');
        cy.get('#panel-description').should('have.attr', 'aria-labelledby', 'tab-description');
    });

    it('mounts CKEditor 5 (from richtext-ckeditor5) for richtext fields', () => {
        cy.visit(moduleRender);
        cy.get('[data-editor-ready]', {timeout: 20000});
        cy.contains('button', /edit module/i).click();
        cy.contains('[role="tab"]', /^Description$/).click();
        // The federated CKEditor build is loaded from the deployed richtext-ckeditor5
        // module and instantiated on the live page (allow time for the remote fetch).
        cy.get('[data-ckeditor-state="ready"]', {timeout: 30000}).should('exist');
        cy.get('.ck-editor__editable', {timeout: 10000}).should('be.visible');
        cy.get('.ck-toolbar button').its('length').should('be.greaterThan', 0);
        // The federated `.` entry ships no CSS, so the storefront injects the
        // matching CKEditor 5 stylesheet itself — without it the editor is unstyled.
        cy.get('head style[data-ckeditor5-styles]').should('exist');
    });

    it('uploads a module icon (GraphQL multipart) and stores real image bytes', () => {
        cy.visit(moduleRender);
        cy.get('[data-editor-ready]', {timeout: 20000});
        cy.contains('button', /edit module/i).click();
        // Icon upload lives on the (default) General tab.
        cy.get('[data-icon-input]', {timeout: 10000}).selectFile('assets/icon.png', {force: true});
        cy.contains('button', /upload icon/i).click();
        cy.get('[data-icon-status="uploaded"]', {timeout: 20000}).should('exist');
        // Persisted: the module header renders an icon <img> after reload (the
        // placeholder is a <span>, so its presence proves the file node was created).
        cy.reload();
        cy.get('header img', {timeout: 20000}).should('have.attr', 'src').and('match', /\S/);
        // Guard the multipart-upload bug: the stored binary must be the real PNG,
        // not a stringified servlet Part ("org.apache.catalina...ApplicationPart@…",
        // which was served as image/png but only ~49 bytes of ASCII).
        cy.get('header img').invoke('attr', 'src').then(src => {
            cy.request({url: src as string, encoding: 'binary'}).then(resp => {
                expect(resp.status).to.eq(200);
                // Real image bytes — the corrupt value was a ~49-byte ASCII string.
                expect(resp.body.length, 'icon byte size').to.be.greaterThan(100);
                expect(resp.body, 'not a stringified servlet Part')
                    .not.to.contain('ApplicationPart');
            });
        });
    });

    it('owner publishes / unpublishes the module via the publish control', () => {
        cy.visit(moduleRender);
        // Widget was seeded published=true; the control reflects it once hydrated.
        cy.get('[data-publish-scope="module"][data-publish-ready="true"]', {timeout: 20000})
            .should('have.attr', 'data-published', 'true');
        cy.get('[data-publish-scope="module"] button').click();
        cy.get('[data-publish-scope="module"]').should('have.attr', 'data-published', 'false');
        // Persisted (setValue on the rendered workspace), survives reload.
        cy.reload();
        cy.get('[data-publish-scope="module"][data-publish-ready="true"]', {timeout: 20000})
            .should('have.attr', 'data-published', 'false');
    });

    it('owner publishes a specific version via the version publish control', () => {
        cy.visit(moduleRender);
        openVersionsPopup();
        // The seeded version starts unpublished.
        cy.get('[data-forge-version] [data-publish-scope="version"][data-publish-ready="true"]', {timeout: 20000})
            .should('have.attr', 'data-published', 'false');
        cy.get('[data-forge-version] [data-publish-scope="version"] button').click();
        cy.get('[data-forge-version] [data-publish-scope="version"]').should('have.attr', 'data-published', 'true');
        cy.reload();
        openVersionsPopup();
        cy.get('[data-forge-version] [data-publish-scope="version"][data-publish-ready="true"]', {timeout: 20000})
            .should('have.attr', 'data-published', 'true');
    });

    it('owner removes a version via the version delete control (with confirm)', () => {
        const multiRender = `/cms/render/default/en${repo}/multiver.html`;

        cy.visit(multiRender);
        openVersionsPopup();
        // Both seeded versions are listed.
        cy.contains('[data-forge-version]', '2.0.0').should('be.visible');
        cy.contains('[data-forge-version]', '1.0.0').should('be.visible');

        // In the 1.0.0 card: open the inline confirm, then confirm the deletion. A
        // successful delete reloads the page, so stamp window before the confirm
        // click and wait for the stamp to vanish (reload-safe, like saveAndWaitReload).
        const v1 = () => cy.contains('[data-forge-version]', '1.0.0');
        v1().find('[data-version-delete-scope="version"][data-version-delete-ready="true"]', {timeout: 20000})
            .should('exist');
        v1().contains('button', /remove version/i).click();
        cy.window().then(w => {
            (w as unknown as {__preDelete?: boolean}).__preDelete = true;
        });
        v1().contains('button', /^Delete$/).click();
        cy.window({timeout: 20000}).should(w => {
            expect((w as unknown as {__preDelete?: boolean}).__preDelete).to.be.undefined;
        });

        // After the reload: 2.0.0 remains, 1.0.0 is gone.
        openVersionsPopup();
        cy.contains('[data-forge-version]', '2.0.0').should('be.visible');
        cy.get('[data-forge-version]').should('have.length', 1);
        cy.contains('[data-forge-version]', '1.0.0').should('not.exist');
    });

    it('renders the JAR upload form wired to the createEntryFromJar action', () => {
        cy.visit(`/cms/render/default/en/sites/${siteKey}/home/my-modules.html`);
        // The upload form is a client island that posts via XHR (so Jahia's CsrfGuard
        // attaches its token — a plain <form> POST is rejected). The createEntryFromJar
        // action URL now rides in the island's hydration props, not a <form action>.
        cy.get('input[type="file"][name="file"]').should('exist');
        cy.get('[data-upload-ready="true"]', {timeout: 20000}).should('exist');
        cy.get('body').should($b => expect($b.html()).to.contain('createEntryFromJar.do'));
    });

    it('owner can add a version from the module page (upload form in the Versions popup)', () => {
        cy.visit(moduleRender);
        openVersionsPopup();
        // The owner-only "Upload a new version" form lives in the Versions popup and
        // posts to the SAME createEntryFromJar action (which upserts: it appends the
        // version to the existing module). A real upload needs a Maven repo, so —
        // like the my-modules upload test — assert the form is present and wired.
        cy.get('[data-add-version]', {timeout: 20000}).should('be.visible');
        cy.get('[data-add-version] input[type="file"][name="file"]').should('exist');
        cy.get('[data-add-version] [data-upload-ready="true"]', {timeout: 20000}).should('exist');
        cy.get('body').should($b => expect($b.html()).to.contain('createEntryFromJar.do'));
    });

    it('owner can reorder and delete screenshots in the editor Media tab (jcr mutations)', () => {
        // Two screenshots in the module's (autocreated) screenshots node.
        uploadFile('../../assets/screenshot.png', `${repo}/widget/screenshots`, 'shot-a.png', 'image/png');
        uploadFile('../../assets/screenshot.png', `${repo}/widget/screenshots`, 'shot-b.png', 'image/png');

        cy.intercept('POST', '/modules/graphql').as('gql');
        cy.visit(moduleRender);
        openEditorMediaTab();
        cy.get('[data-screenshot-name]').then($els => {
            expect($els.eq(0).attr('data-screenshot-name')).to.eq('shot-a.png');
            expect($els.eq(1).attr('data-screenshot-name')).to.eq('shot-b.png');
        });

        // Move the first screenshot down -> order becomes b, a (persisted via reorderChildren).
        cy.get('[data-screenshot-name="shot-a.png"]').find('button[aria-label="Move down"]').click();
        cy.wait('@gql');
        cy.reload();
        openEditorMediaTab();
        cy.get('[data-screenshot-name]').first().should('have.attr', 'data-screenshot-name', 'shot-b.png');

        // Delete the (now first) screenshot -> only shot-a.png remains. Deletion is
        // irreversible, so it now requires confirming an inline prompt: the first click
        // reveals Confirm/Cancel, the "Delete" confirm button performs the deletion.
        cy.get('[data-screenshot-name="shot-b.png"]').find('button[aria-label="Delete screenshot"]').click();
        cy.get('[data-screenshot-name="shot-b.png"]').contains('button', 'Delete').click();
        cy.wait('@gql');
        cy.reload();
        openEditorMediaTab();
        cy.get('[data-screenshot-name="shot-b.png"]').should('not.exist');
        cy.get('[data-screenshot-name="shot-a.png"]').should('exist');
    });

    it('owner can set and clear the module video in the editor Media tab', () => {
        // Verify persistence via GraphQL rather than reloading the page: once a video
        // exists the detail page embeds a YouTube iframe, which never loads in the
        // offline test env and would hang cy.reload() waiting for the page load event.
        cy.visit(moduleRender);
        openEditorMediaTab();

        // Set a YouTube video (creates the `video` child node + its properties).
        cy.get('#edit-video-provider').select('youtube');
        cy.get('[data-video-id]').clear().type('dQw4w9WgXcQ');
        cy.get('[data-video-save]').click();
        cy.contains('[data-video-manager] output', 'Saved', {timeout: 20000});
        readProp(`${repo}/widget/video`, 'provider')
            .its('data.jcr.nodeByPath.properties[0].value')
            .should('equal', 'youtube');
        readProp(`${repo}/widget/video`, 'identifier')
            .its('data.jcr.nodeByPath.properties[0].value')
            .should('equal', 'dQw4w9WgXcQ');

        // Clear it (provider "None" + Save) -> the video node is deleted.
        cy.get('#edit-video-provider').select('None');
        cy.get('[data-video-save]').click();
        cy.contains('[data-video-manager] output', 'Saved', {timeout: 20000});
        // A missing node makes nodeByPath resolve to null/undefined (the field errors
        // out for a non-existent path), so assert "not exist" rather than strictly null.
        readProp(`${repo}/widget/video`, 'provider').its('data.jcr.nodeByPath').should('not.exist');
    });

    it('editor lists the configured categories (regression: was always "none configured")', () => {
        // Wire a site root category + a child via the OSGi-backed admin path, then
        // confirm the editor surfaces them. Guards the bug where the editor read the
        // stale JCR `rootCategory` property instead of the OSGi forge config.
        createSharedCategory(sharedRootNames[0]).then(rootUuid => {
            cy.apollo({mutation: setRootCategory, variables: {siteKey, rootCategoryUuid: rootUuid}});
        });
        cy.apollo({mutation: addForgeCategory, variables: {siteKey, name: 'widgets'}});

        cy.visit(moduleRender);
        cy.get('[data-editor-ready]', {timeout: 20000});
        cy.contains('button', /edit module/i).click();
        // The category control lives on the default (General) tab. Categories now use a
        // tag-style chip picker: a configured-but-unselected category appears as an option
        // in the add-dropdown (not a checkbox label).
        cy.contains('No categories are configured').should('not.exist');
        cy.get('#edit-category', {timeout: 20000}).find('option').contains(/widgets/i).should('exist');
    });

    it('category picker works like the tag chips: pick adds a chip, × removes it', () => {
        // Configure two categories so the dropdown still offers one after the first is picked.
        createSharedCategory(sharedRootNames[1]).then(rootUuid => {
            cy.apollo({mutation: setRootCategory, variables: {siteKey, rootCategoryUuid: rootUuid}});
        });
        cy.apollo({mutation: addForgeCategory, variables: {siteKey, name: 'alpha'}});
        cy.apollo({mutation: addForgeCategory, variables: {siteKey, name: 'beta'}});

        cy.visit(moduleRender);
        cy.get('[data-editor-ready]', {timeout: 20000});
        cy.contains('button', /edit module/i).click();

        // Pick "alpha" from the add-dropdown — it becomes a removable chip and leaves the dropdown.
        cy.get('#edit-category', {timeout: 20000}).select('alpha');
        cy.get('[data-category-select]').contains('li', 'alpha').should('be.visible');
        cy.get('#edit-category').find('option').contains(/alpha/i).should('not.exist');
        // "beta" is still offered.
        cy.get('#edit-category').find('option').contains(/beta/i).should('exist');

        // The chip's × removes it and returns it to the dropdown.
        cy.get('[data-category-select]')
            .contains('li', 'alpha')
            .find('button[aria-label^="Remove category"]')
            .click();
        cy.get('[data-category-select]').contains('li', 'alpha').should('not.exist');
        cy.get('#edit-category').find('option').contains(/alpha/i).should('exist');
    });

    it('screenshot viewer: arrow keys navigate and Escape closes', () => {
        // Two distinct screenshots so the detail-page lightbox has something to page
        // through (same source image, different node names -> different URLs).
        uploadFile('../../assets/screenshot.png', `${repo}/widget/screenshots`, 'kbd-1.png', 'image/png');
        uploadFile('../../assets/screenshot.png', `${repo}/widget/screenshots`, 'kbd-2.png', 'image/png');

        cy.visit(moduleRender);
        // Open the viewer on the first screenshot (Overview is the default detail tab).
        cy.get('[aria-label^="Open screenshot"]', {timeout: 20000}).first().click();
        cy.get('[data-lightbox]').should('exist');

        cy.get('[data-lightbox-image]').invoke('attr', 'src').then(firstSrc => {
            // ArrowRight advances to the next image...
            cy.get('body').type('{rightarrow}');
            cy.get('[data-lightbox-image]').should('have.attr', 'src').and('not.eq', firstSrc);
            // ...ArrowLeft returns to the first.
            cy.get('body').type('{leftarrow}');
            cy.get('[data-lightbox-image]').should('have.attr', 'src', firstSrc);
        });

        // Escape closes the viewer.
        cy.get('body').type('{esc}');
        cy.get('[data-lightbox]').should('not.exist');
    });

    it('owner jumps from the detail screenshots to the editor Media tab (cross-island shortcut)', () => {
        // A screenshot makes the detail page render the screenshots section + the owner shortcut.
        uploadFile('../../assets/screenshot.png', `${repo}/widget/screenshots`, 'ux13.png', 'image/png');
        cy.visit(moduleRender);
        // Wait for the editor island to hydrate (its listener must be attached) AND the shortcut
        // island to hydrate (its click handler), then click the shortcut.
        cy.get('[data-editor-ready]', {timeout: 20000});
        cy.get('[data-edit-media][data-edit-media-ready="true"]', {timeout: 20000}).click();
        // The separate ModuleEditor island received the event and opened itself on the Media tab
        // (the "Module fields" tablist only renders when the editor is open).
        cy.get('[aria-label="Module fields"]', {timeout: 20000})
            .contains('[role="tab"]', 'Media')
            .should('have.attr', 'aria-selected', 'true');
        cy.get('[data-screenshots-ready]', {timeout: 20000}).should('be.visible');
    });

    it('rejects an invalid video id without writing the video node', () => {
        cy.intercept('POST', '/modules/graphql').as('gql');
        cy.visit(moduleRender);
        openEditorMediaTab();

        cy.get('#edit-video-provider').select('youtube');
        cy.get('[data-video-id]').clear().type('../../etc/passwd');
        cy.get('[data-video-save]').click();

        // The manager surfaces an error and writes nothing (the guard short-circuits before any
        // GraphQL mutation), so the video node stays absent.
        cy.get('[data-video-manager] [role="alert"]', {timeout: 20000}).should('be.visible');
        readProp(`${repo}/widget/video`, 'provider').its('data.jcr.nodeByPath').should('not.exist');
    });

    it('uploads a screenshot through the Media-tab control and rejects a non-image', () => {
        cy.visit(moduleRender);
        openEditorMediaTab();

        // Happy path: a PNG picked through the UI is uploaded and shown.
        cy.get('[data-screenshot-input]').selectFile('assets/screenshot.png', {force: true});
        cy.get('[data-screenshot-name="screenshot.png"]', {timeout: 20000}).should('exist');

        // A non-image file is rejected client-side with an error and adds no thumbnail.
        cy.get('[data-screenshot-input]').selectFile('assets/provisioning.yml', {force: true});
        cy.get('[data-screenshots-ready] [role="alert"]', {timeout: 20000}).should('be.visible');
        cy.get('[data-screenshot-name="provisioning.yml"]').should('not.exist');
    });

    it('accepts a pasted YouTube URL and stores the extracted video id', () => {
        cy.visit(moduleRender);
        openEditorMediaTab();

        cy.get('#edit-video-provider').select('youtube');
        cy.get('[data-video-id]').clear().type('https://www.youtube.com/watch?v=dQw4w9WgXcQ');
        cy.get('[data-video-save]').click();
        cy.contains('[data-video-manager] output', 'Saved', {timeout: 20000});
        // The full URL is stored as the bare id, not verbatim.
        readProp(`${repo}/widget/video`, 'identifier')
            .its('data.jcr.nodeByPath.properties[0].value')
            .should('equal', 'dQw4w9WgXcQ');

        // Clean up so the shared module ends without a video node.
        cy.get('#edit-video-provider').select('None');
        cy.get('[data-video-save]').click();
        cy.contains('[data-video-manager] output', 'Saved', {timeout: 20000});
    });

    it('confirms before discarding unsaved editor changes', () => {
        cy.visit(moduleRender);
        cy.get('[data-editor-ready]', {timeout: 20000});
        cy.contains('button', /edit module/i).click();
        // Make the editor dirty on the General tab.
        cy.get('#edit-jcr-title', {timeout: 10000}).clear().type('Throwaway title');

        // Cancel now asks before discarding; "Keep editing" dismisses and preserves the edit.
        cy.get('[data-editor-cancel]').click();
        cy.contains('[role="alertdialog"]', 'Discard', {timeout: 10000}).should('be.visible');
        cy.contains('button', 'Keep editing').click();
        cy.get('#edit-jcr-title').should('have.value', 'Throwaway title');

        // Cancel again, then Discard -> the editor closes and the edit is gone on reopen.
        cy.get('[data-editor-cancel]').click();
        cy.contains('[role="alertdialog"] button', 'Discard').click();
        cy.contains('button', /edit module/i, {timeout: 10000}).should('be.visible');
        cy.contains('button', /edit module/i).click();
        cy.get('#edit-jcr-title', {timeout: 10000}).should('not.have.value', 'Throwaway title');
    });
});
