/**
 * The "My modules" page of the storefront: the modules of the signed-in developer, the upload
 * form and the form that registers a git repository. Every selector of the page lives here.
 */
export class MyModulesPage {
    private readonly siteKey: string;

    constructor(siteKey: string) {
        this.siteKey = siteKey;
    }

    static visit(siteKey: string): MyModulesPage {
        cy.visit(`/cms/render/live/en/sites/${siteKey}/home/my-modules.html`);
        return new MyModulesPage(siteKey);
    }

    /**
     * Paste the URL of a repository and register it. On success the form sends the browser back
     * to this page with the new module. The window is stamped before the click and the step waits
     * until the stamp is gone: the page load replaces the window, and the logger of the test
     * harness injects commands on load that would break a chain pending on a DOM node.
     */
    registerRepository(url: string): MyModulesPage {
        cy.window().then(w => {
            (w as unknown as {__beforeRegister?: boolean}).__beforeRegister = true;
        });
        this.submitRepository(url);
        cy.window({timeout: 60000}).should(w => {
            expect((w as unknown as {__beforeRegister?: boolean}).__beforeRegister, 'the page reloaded').to.be.undefined;
        });
        return this;
    }

    /** Paste the URL of a repository the store refuses: the form says why and the page stays. */
    registerRepositoryRefused(url: string, reason: string): MyModulesPage {
        this.submitRepository(url);
        cy.get('[data-git-register-ready="true"] [role="alert"]', {timeout: 60000}).should('contain.text', reason);
        return this;
    }

    private submitRepository(url: string): void {
        cy.get('[data-git-register-ready="true"]', {timeout: 20000}).within(() => {
            cy.get('input[name="gitUrl"]').clear().type(url);
            cy.get('button[type="submit"]').click();
        });
    }

    module(title: string): ModuleCard {
        return new ModuleCard(title);
    }

    expectNoModule(title: string): MyModulesPage {
        cy.get('body', {timeout: 20000}).should('not.contain.text', title);
        return this;
    }
}

/** One card of the list of modules. */
export class ModuleCard {
    private readonly title: string;

    constructor(title: string) {
        this.title = title;
    }

    private card(): Cypress.Chainable<JQuery> {
        return cy.contains('[data-forge-card]', this.title, {timeout: 30000});
    }

    /** The card says the module is read from a git repository. */
    expectReadFromGit(): ModuleCard {
        this.expectSource('git');
        return this;
    }

    /** The card is the one of an uploaded module: no source badge. */
    expectUploaded(): ModuleCard {
        this.expectSource('local');
        return this;
    }

    /**
     * The card exists with the source. While the page reloads after a registration, the query
     * yields no element: an assertion error there retries, where a type error would not.
     */
    private expectSource(source: string): void {
        this.card().should($card => {
            const element = $card[0] as Element | undefined;
            expect(element, 'the card of the module').to.exist;
            expect(element.getAttribute('data-source')).to.equal(source);
        });
    }

    open(): void {
        this.card().click();
    }
}
