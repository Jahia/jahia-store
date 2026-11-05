import {DocumentNode} from 'graphql';
import {Result} from 'axe-core';
import {createSite, deleteSite, setNodeProperty, uploadFile} from '@jahia/cypress';

/**
 * Accessibility gate (jahia-store-template JS module) — enforces the module's
 * WCAG 2.2 Level AAA conformance target with axe-core.
 *
 * Seeds a jahia-store-template site (home grid + a module detail + my-modules), then
 * runs axe on each rendered page against the full WCAG ladder up to AAA, plus the
 * landmark/region best-practice rules. Any violation fails the spec — this
 * replaces the previously-manual EqualWeb/axe audit with an automated gate.
 *
 * (Store administration is no longer in-site; it lives in the Jahia site
 * administration / jContent.) Requires the JS build of jahia-store-template. Skips
 * gracefully on the legacy JSP build.
 */
describe('Accessibility — WCAG 2.2 AAA gate (JS module)', () => {
    const siteKey = 'a11y';
    const contents = `/sites/${siteKey}/contents`;
    const repo = `${contents}/modules-repository`;
    const render = (path: string) => `/cms/render/default/en/sites/${siteKey}${path}.html`;
    const detailRender = `/cms/render/default/en${repo}/analytics.html`;

    // Full WCAG ladder up to AAA + the landmark/region best practices that the
    // manual audit flagged (duplicate/nested <main>, unique landmarks).
    const AXE_RUN: { runOnly: { type: 'tag'; values: string[] } } = {
        runOnly: {
            type: 'tag',
            values: ['wcag2a', 'wcag2aa', 'wcag2aaa', 'wcag21a', 'wcag21aa', 'wcag21aaa', 'wcag22aa', 'best-practice']
        }
    };

    // Cypress-terminal-report mirrors cy.log to the terminal, so a failing run
    // prints exactly which rule/selector broke instead of just a count.
    const logViolations = (violations: Result[]) => {
        cy.log(`a11y: ${violations.length} violation(s)`);
        violations.forEach(v => {
            cy.log(`[${v.impact}] ${v.id}: ${v.help}`);
            v.nodes.forEach(n => cy.log(`  → ${n.target.join(', ')}`));
        });
    };

    const audit = () => {
        cy.injectAxe();
        cy.checkA11y(undefined, AXE_RUN, logViolations);
    };

    const createForgeModule: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/createForgeModule.graphql');

    const addNodeWithProps: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/addNodeWithProperties.graphql');

    const islandBundle = '/modules/jahia-store-template/dist/client/components/forge/ModuleEditor.client.tsx.js';

    before(function () {
        cy.request({url: islandBundle, failOnStatusCode: false}).then(res => {
            if (res.status !== 200) {
                cy.log('jahia-store-template JS module not deployed — skipping accessibility spec');
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
            languages: 'en,fr',
            templateSet: 'jahia-store-template',
            serverName: 'a11y.local',
            locale: 'en'
        });

        // A published module with a version (so the grid + detail render with
        // real content, exercising card/detail contrast — not just the empty state).
        cy.apollo({
            mutation: createForgeModule,
            variables: {parentPath: repo, name: 'analytics', title: 'Analytics Dashboard'}
        });
        setNodeProperty(`${repo}/analytics`, 'description', '<p>Real-time charts and KPI widgets.</p>', 'en');
        setNodeProperty(`${repo}/analytics`, 'status', 'supported', 'en');
        setNodeProperty(`${repo}/analytics`, 'published', 'true', 'en');
        // GroupId drives the generated (mavenproxy) download URL, so the audit covers
        // the rendered download links too.
        setNodeProperty(`${repo}/analytics`, 'groupId', 'org.cypress.test', 'en');
        cy.apollo({
            mutation: addNodeWithProps,
            variables: {
                parentPath: `${repo}/analytics`,
                name: 'v100',
                primaryNodeType: 'jnt:forgeModuleVersion',
                properties: [
                    {name: 'versionNumber', value: '1.0.0'},
                    {name: 'published', value: 'true'},
                    {name: 'changeLog', value: '<ul><li>Initial release</li></ul>'},
                    // Declares a dependency on the "seo" module created below, so the detail page's
                    // [data-dependency-lists] section (otherwise never rendered by this spec) is
                    // present during the WCAG audit too.
                    {name: 'references', values: ['seo']}
                ]
            }
        });

        // A second published module for a non-trivial grid.
        cy.apollo({
            mutation: createForgeModule,
            variables: {parentPath: repo, name: 'seo', title: 'SEO Toolkit'}
        });
        setNodeProperty(`${repo}/seo`, 'description', '<p>Meta tags and sitemaps.</p>', 'en');
        setNodeProperty(`${repo}/seo`, 'status', 'community', 'en');
        setNodeProperty(`${repo}/seo`, 'published', 'true', 'en');

        // Two screenshots so the detail page shows the lightbox (audited in its open state below).
        uploadFile('../../assets/screenshot.png', `${repo}/analytics/screenshots`, 'a11y-1.png', 'image/png');
        uploadFile('../../assets/screenshot.png', `${repo}/analytics/screenshots`, 'a11y-2.png', 'image/png');
    });

    after(() => {
        deleteSite(siteKey);
    });

    beforeEach(() => {
        cy.login();
    });

    it('home storefront grid has no WCAG 2.2 AAA violations', () => {
        cy.visit(render('/home'));
        // Wait for the storefront grid to render, then a card, before auditing.
        cy.get('[data-forge-list]', {timeout: 20000});
        cy.contains('Analytics Dashboard').should('be.visible');
        audit();
    });

    it('module detail page has no WCAG 2.2 AAA violations', () => {
        cy.visit(detailRender);
        cy.contains('h1', 'Analytics Dashboard').should('be.visible');
        // Let the section tabs initialise (only the active panel is then visible).
        cy.get('[data-detail-tabs-ready]', {timeout: 20000});
        audit();
    });

    it('"My modules" page has no WCAG 2.2 AAA violations', () => {
        cy.visit(render('/home/my-modules'));
        cy.contains('[data-forge-card]', 'Analytics Dashboard').should('be.visible');
        audit();
    });

    it('the in-site editor (open) has no WCAG 2.2 AAA violations', () => {
        cy.visit(detailRender);
        cy.get('[data-editor-ready]', {timeout: 20000});
        cy.contains('button', /edit module/i).click();
        // The editor field tablist is present once the form is open.
        cy.get('[aria-label="Module fields"]', {timeout: 20000}).should('be.visible');
        audit();
    });

    it('the screenshot viewer (open) has no WCAG 2.2 AAA violations', () => {
        cy.visit(detailRender);
        cy.get('[data-detail-tabs-ready]', {timeout: 20000});
        cy.get('[aria-label^="Open screenshot"]', {timeout: 20000}).first().click();
        cy.get('[data-lightbox]').should('exist');
        audit();
    });
});
