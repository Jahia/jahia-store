/**
 * The validation dashboard of the store, for its administrators: the versions waiting for a
 * check, the rejected ones, the approved ones. Every selector of the page lives here.
 */
export class ValidationDashboardPage {
    static visit(siteKey: string): ValidationDashboardPage {
        cy.visit(`/cms/render/live/en/sites/${siteKey}/home/validations.html`);
        return new ValidationDashboardPage();
    }

    /** The page refuses a visitor who does not administer the store. */
    expectForbidden(): ValidationDashboardPage {
        cy.get('[data-validation-dashboard]').should('not.exist');
        cy.contains('store administrators', {timeout: 20000}).should('be.visible');
        return this;
    }

    /** The version sits in the section: waiting, rejected or approved. */
    expectInSection(section: 'waiting' | 'rejected' | 'approved', moduleName: string, version: string): ValidationDashboardPage {
        cy.get(`[data-validation-section="${section}"] [data-validation-record="${moduleName}/${version}"]`, {timeout: 20000}).should('exist');
        return this;
    }

    expectAbsent(moduleName: string, version: string): ValidationDashboardPage {
        cy.get('[data-validation-dashboard]', {timeout: 20000});
        cy.get(`[data-validation-record="${moduleName}/${version}"]`).should('not.exist');
        return this;
    }

    record(moduleName: string, version: string): DashboardRecord {
        return new DashboardRecord(moduleName, version);
    }
}

/** One version on the dashboard. */
export class DashboardRecord {
    private readonly moduleName: string;
    private readonly version: string;

    constructor(moduleName: string, version: string) {
        this.moduleName = moduleName;
        this.version = version;
    }

    private root(): Cypress.Chainable<JQuery> {
        return cy.get(`[data-validation-record="${this.moduleName}/${this.version}"]`, {timeout: 20000});
    }

    expectStatus(status: string): DashboardRecord {
        this.root().should('have.attr', 'data-validation-status', status);
        return this;
    }

    expectReason(reason: string): DashboardRecord {
        this.root().should('contain.text', reason);
        return this;
    }

    /** A check that waits for a human shows the prompt to give to the assistant, naming the version. */
    expectPromptOf(checkKey: string, text: string): DashboardRecord {
        this.root().find(`[data-check-answer="${checkKey}"] pre`).should('contain.text', text);
        return this;
    }

    /** Paste the answer of the assistant for a check and record it; the page reloads. */
    answerCheck(checkKey: string, answer: string): DashboardRecord {
        this.root()
            .find(`[data-check-answer="${checkKey}"][data-check-answer-ready="true"]`, {timeout: 20000})
            .within(() => {
                cy.get('textarea').clear().type(answer, {parseSpecialCharSequences: false, delay: 0});
                cy.contains('button', 'Record').click();
            });
        cy.get(`[data-validation-record="${this.moduleName}/${this.version}"] [data-check-answer="${checkKey}"]`, {timeout: 30000}).should('not.exist');
        return this;
    }

    expectCheck(checkKey: string, status: string, comment?: string): DashboardRecord {
        const check = this.root().find(`[data-validation-check="${checkKey}"]`);
        check.find('[data-validation-status]').should('have.attr', 'data-validation-status', status);
        if (comment) {
            this.root().find(`[data-validation-check="${checkKey}"]`).should('contain.text', comment);
        }

        return this;
    }

    /** Invalidate the version with a reason; the page reloads with the version rejected. */
    invalidate(reason: string): DashboardRecord {
        this.root()
            .find('[data-invalidate][data-invalidate-ready="true"]', {timeout: 20000})
            .within(() => {
                cy.get('button').first().click();
                cy.get('input').type(reason);
                cy.contains('button', 'Invalidate').click();
            });
        cy.get(`[data-validation-section="rejected"] [data-validation-record="${this.moduleName}/${this.version}"]`, {timeout: 30000}).should('exist');
        return this;
    }
}
