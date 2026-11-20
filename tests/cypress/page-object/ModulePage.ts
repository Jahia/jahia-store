/**
 * The page of a module in the storefront: its header, its tabs, its versions dialog, and the bar
 * the editors of a git-backed module get. Every selector of the page lives here.
 */
export class ModulePage {
    static visit(siteKey: string, moduleName: string): ModulePage {
        cy.visit(`/cms/render/live/en/sites/${siteKey}/contents/modules-repository/${moduleName}.html`);
        return new ModulePage();
    }

    /** The page is not there: the storefront answers an error page for a module that is gone. */
    static expectGone(siteKey: string, moduleName: string): void {
        cy.request({
            url: `/cms/render/live/en/sites/${siteKey}/contents/modules-repository/${moduleName}.html`,
            failOnStatusCode: false
        })
            .its('status')
            .should('eq', 404);
    }

    expectTitle(title: string): ModulePage {
        cy.get('h1', {timeout: 20000}).should('contain.text', title);
        return this;
    }

    /** The icon of the module is served from the module itself and loads. */
    expectIcon(): ModulePage {
        cy.get('[data-detail] header img')
            .should('have.attr', 'src')
            .and('match', /\/icon\/[^/]+$/);
        cy.get('[data-detail] header img').should($img => {
            expect(($img[0] as unknown as {naturalWidth: number}).naturalWidth).to.be.greaterThan(0);
        });
        return this;
    }

    expectOverview(text: string): ModulePage {
        cy.get('[data-detail-panel="overview"]').should('contain.text', text);
        return this;
    }

    /** An image of the overview is served beside the module and loads. */
    expectOverviewImage(fileName: string): ModulePage {
        cy.get(`[data-detail-panel="overview"] img[src$="/files/${fileName}"]`).should($img => {
            expect(($img[0] as unknown as {naturalWidth: number}).naturalWidth).to.be.greaterThan(0);
        });
        return this;
    }

    /** The prominent download of the header names the version and links its artefact. */
    expectDownloadOf(version: string, artifactUrl: string): ModulePage {
        cy.get('[data-latest-download]', {timeout: 20000})
            .should('contain.text', version)
            .and('have.attr', 'href', artifactUrl);
        return this;
    }

    tab(title: string): ModuleTab {
        return new ModuleTab(title);
    }

    expectNoTab(title: string): ModulePage {
        cy.get('[role="tablist"][aria-label="Module sections"]').should('not.contain.text', title);
        return this;
    }

    openVersions(): VersionsDialog {
        cy.get('[data-versions-open][data-versions-ready="true"]', {timeout: 20000}).click();
        cy.get('[data-versions-dialog][open]', {timeout: 20000}).should('be.visible');
        return new VersionsDialog();
    }

    /** The bar of the editors of a git-backed module names the repository the module is read from. */
    expectReadFrom(repositoryUrl: string): ModulePage {
        cy.get('[data-git-bar]', {timeout: 20000}).should('contain.text', repositoryUrl);
        return this;
    }

    /** No editor, publish toggle nor upload: the module is read from its repository. */
    expectNoEditor(): ModulePage {
        cy.get('[data-owner-bar]').should('not.exist');
        cy.get('[data-add-version]').should('not.exist');
        return this;
    }

    /** A visitor gets neither the git bar nor an editor. */
    expectNoControls(): ModulePage {
        cy.get('[data-git-bar]').should('not.exist');
        cy.get('[data-owner-bar]').should('not.exist');
        return this;
    }

    /** Press "Refresh from git" and wait for the page to reload with the new reading. */
    refreshFromGit(): ModulePage {
        this.waitForReload(() => cy.get('[data-git-refresh-ready="true"] [data-git-refresh]', {timeout: 20000}).click());
        return this;
    }

    /** Press "Remove from the store" and confirm; the browser lands on the My modules page. */
    removeFromStore(): void {
        cy.get('[data-module-delete="git"][data-module-delete-ready="true"]', {timeout: 20000}).within(() => {
            cy.get('button').first().click();
            cy.get('button.store-btn--danger').click();
        });
        cy.location('pathname', {timeout: 30000}).should('include', 'my-modules.html');
    }

    /**
     * Stamp the window, act, and wait until the stamp is gone: a reload replaces the window, and
     * a DOM node would detach mid-navigation.
     */
    private waitForReload(action: () => void): void {
        cy.window().then(w => {
            (w as unknown as {__beforeReload?: boolean}).__beforeReload = true;
        });
        action();
        cy.window({timeout: 30000}).should(w => {
            expect((w as unknown as {__beforeReload?: boolean}).__beforeReload).to.be.undefined;
        });
    }
}

/** One tab of the page: Overview, Installation, License, or one the module declares. */
export class ModuleTab {
    private readonly title: string;

    constructor(title: string) {
        this.title = title;
    }

    open(): ModuleTab {
        // The tabs are an island: a click before it is hydrated changes nothing.
        cy.get('[data-detail-tabs-ready="true"][role="tablist"][aria-label="Module sections"]', {timeout: 20000})
            .contains('[role="tab"]', this.title)
            .click()
            .should('have.attr', 'aria-selected', 'true');
        return this;
    }

    expectContent(text: string): ModuleTab {
        cy.get('[data-detail-panel]:visible').should('contain.text', text);
        return this;
    }
}

/** The versions dialog of the page. */
export class VersionsDialog {
    version(number: string): VersionEntry {
        return new VersionEntry(number);
    }

    expectAbsent(number: string): VersionsDialog {
        cy.get('[data-versions-dialog][open]').within(() => {
            cy.contains('[data-forge-version]', number).should('not.exist');
        });
        return this;
    }

    expectCount(count: number): VersionsDialog {
        cy.get('[data-versions-dialog][open] [data-forge-version]').should('have.length', count);
        return this;
    }
}

/** One version of the dialog. */
export class VersionEntry {
    private readonly number: string;

    constructor(number: string) {
        this.number = number;
    }

    private entry(): Cypress.Chainable<JQuery> {
        return cy.get('[data-versions-dialog][open]').contains('[data-forge-version]', this.number);
    }

    expectDisabled(): VersionEntry {
        this.entry().should('contain.text', 'disabled');
        return this;
    }

    expectReleased(): VersionEntry {
        this.entry().should('not.contain.text', 'disabled').and('not.contain.text', 'draft');
        return this;
    }

    expectNotes(text: string): VersionEntry {
        this.entry().should('contain.text', text);
        return this;
    }

    expectDownload(artifactUrl: string): VersionEntry {
        this.entry().find('a').filter(`[href="${artifactUrl}"]`).should('exist');
        return this;
    }

    expectTag(name: string, url: string): VersionEntry {
        this.entry().find('[data-version-tag] a').should('have.text', name).and('have.attr', 'href', url);
        return this;
    }

    expectChangelogLink(url: string): VersionEntry {
        this.entry().find('[data-version-changelog]').should('have.attr', 'href', url);
        return this;
    }
}
