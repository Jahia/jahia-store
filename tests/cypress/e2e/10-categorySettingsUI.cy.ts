import {DocumentNode} from 'graphql';
import {createSite, deleteSite} from '@jahia/cypress';
import {createSharedCategory, deleteSharedCategory} from '../support/sharedCategories';

/**
 * Live-UI test for the React categorySettings admin route.
 *
 * Drives the three top-level interactions: wire a root category, add a child,
 * fill in per-language titles. The root category itself is seeded directly via
 * GraphQL (a jnt:category under the shared tree /sites/systemsite/categories, the
 * only place a root is accepted) — that part is infrastructure, not what the
 * admin would do, so it stays outside the UI flow.
 *
 * The page renders multiple buttons with the label "Save" (root-Save,
 * title-Save). Each click must be scoped to its enclosing section heading
 * — `cy.contains('button', /^Save$/i)` would otherwise always match the
 * first Save in DOM order regardless of which one the user is targeting.
 */
describe('Category settings — live UI', () => {
    const siteKey = 'categorySettingsUiSite';
    const rootCategoryName = 'cy-10-ui-root-categories';

    const getCategorySettings: DocumentNode =
        require('graphql-tag/loader!../fixtures/graphql/query/getCategorySettings.graphql');

    let rootCategoryUuid: string;

    before(() => {
        cy.login();
        try {
            deleteSite(siteKey);
        } catch {
            // ignore
        }

        createSite(siteKey, {
            languages: 'en,fr',
            templateSet: 'jahia-store-template',
            serverName: 'localhost',
            locale: 'en'
        });

        createSharedCategory(rootCategoryName).then(uuid => {
            rootCategoryUuid = uuid;
        });
    });

    after(() => {
        cy.login();
        deleteSite(siteKey);
        deleteSharedCategory(rootCategoryName);
    });

    it('configures a root, adds a category, and persists per-language titles', () => {
        cy.login();
        cy.visit(`/jahia/administration/${siteKey}/categorySettings`);

        // Step 1: wire the root category. Scope the Save click to the
        // root-category section so we don't accidentally grab a later Save.
        cy.get('#root-category-uuid input', {timeout: 60000})
            .clear()
            .type(rootCategoryUuid)
            .should('have.value', rootCategoryUuid);
        cy.contains('h3', /Root category/i)
            .parent()
            .within(() => {
                cy.contains('button', /^Save$/i).should('not.be.disabled').click();
            });

        // Step 2: add a child category. After the root is wired the "Add a
        // category" section becomes visible (gated on settings.rootCategoryUuid).
        cy.get('#new-category-name input', {timeout: 15000})
            .clear()
            .type('ui-portlets')
            .should('have.value', 'ui-portlets');
        cy.contains('h3', /Add a category/i)
            .parent()
            .within(() => {
                cy.contains('button', /^Add$/i).should('not.be.disabled').click();
            });

        // Step 3: persist per-language titles. The editor section auto-opens
        // after Add succeeds.
        cy.get('#title-en input', {timeout: 15000})
            .clear()
            .type('Portlets EN')
            .should('have.value', 'Portlets EN');
        cy.get('#title-fr input')
            .clear()
            .type('Portlets FR')
            .should('have.value', 'Portlets FR');
        cy.contains('h3', /Edit category titles/i)
            .parent()
            .within(() => {
                cy.contains('button', /^Save$/i).should('not.be.disabled').click();
            });

        cy.apollo({query: getCategorySettings, variables: {siteKey}, fetchPolicy: 'no-cache'})
            .its('data.forge.categorySettings.categories')
            .should((cats: Array<{ titles: Array<{ language: string; title: string }> }>) => {
                expect(cats).to.have.length(1);
                const titles = Object.fromEntries(cats[0].titles.map(t => [t.language, t.title]));
                expect(titles.en).to.equal('Portlets EN');
                expect(titles.fr).to.equal('Portlets FR');
            });
    });
});
