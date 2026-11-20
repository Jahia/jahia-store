/**
 * The "My modules" page of the storefront: the modules of the signed-in developer, the upload
 * form and the form that registers a git repository. Every selector of the page lives here.
 */
export class MyModulesPage {
    static visit(siteKey: string): MyModulesPage {
        cy.visit(`/cms/render/live/en/sites/${siteKey}/home/my-modules.html`);
        return new MyModulesPage();
    }

    /** Paste the URL of a repository and register it; the page reloads with the new module on success. */
    registerRepository(url: string): MyModulesPage {
        cy.get('[data-git-register-ready="true"]', {timeout: 20000}).within(() => {
            cy.get('input[name="gitUrl"]').clear().type(url);
            cy.get('button[type="submit"]').click();
        });
        // Registered or refused, the form settles: either the page reloads and the form is
        // hydrated anew, or the form shows the refusal.
        cy.get('[data-git-register-ready="true"] button[type="submit"]', {timeout: 60000}).should('not.be.disabled');
        return this;
    }

    /** The registration was refused, and the form says why. */
    expectRegistrationRefused(reason: string): MyModulesPage {
        cy.get('[data-git-register-ready="true"] [role="alert"]', {timeout: 30000}).should('contain.text', reason);
        return this;
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
        this.card().should($card => {
            expect($card.attr('data-source')).to.equal('git');
            expect($card.text()).to.contain('Git');
        });
        return this;
    }

    /** The card is the one of an uploaded module: no source badge. */
    expectUploaded(): ModuleCard {
        this.card().should('have.attr', 'data-source', 'local');
        return this;
    }

    open(): void {
        this.card().click();
    }
}
