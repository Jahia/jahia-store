import {DocumentNode} from 'graphql';
import {createSite, deleteSite, setNodeProperty} from '@jahia/cypress';

/**
 * Covers the module-detail "Dependencies" / "Depended on by" section
 * (jahia-store-template `src/components/forge/dependencies.ts` +
 * `DependencyLists.tsx`), restored by MOD-1705. Before this spec the feature had
 * ZERO end-to-end coverage — no spec anywhere set a version's multi-valued
 * `references` property, so the whole thing could silently break and every other
 * spec would stay green.
 *
 * Data model (`jnt:forgeModuleVersion` in privateappstore's definitions.cnd):
 * `- references (string) multiple` — raw manifest tokens. `CreateEntryFromJar`
 * (the Java upload action) builds this by splitting the JAR manifest's
 * `Jahia-Depends` header on "," WITHOUT trimming, so real data can contain a
 * leading space (`" seo"`) or a version range shredded by the same naive split
 * (`"seo=[1.1"`, `"2)"`). `dependencyRefNames()` / `forgeDependants()` in
 * dependencies.ts are written defensively around exactly those spellings — this
 * spec exercises them against a real Jackrabbit backend rather than a mock.
 *
 * `[data-dependency-lists]` is server-rendered and NOT gated behind a detail tab
 * (it is a sibling of the tab panels, per DependencyLists.tsx's own comment), and
 * ForgeEntryDetail only mounts it at all when at least one column is non-empty
 * (`dependencies.length > 0 || dependants.length > 0`) — hence case 6 below.
 *
 * Every page below is rendered through `/cms/render/default/...` (the logged-in-owner
 * preview workspace, same as 20-accessibility.cy.ts), not `/cms/render/live/...`.
 * Unlike 16-storefront.cy.ts, nothing here depends on guest-vs-owner
 * visibility, so there is no `publishAndWaitJobEnding` step — the dependency
 * queries in dependencies.ts run against whichever JCR session rendered the
 * page, and every module/version below is marked `published` on that session
 * regardless (both `resolveStoreModules` and `forgeDependants` hard-filter on
 * `published = true` in their JCR-SQL2, independently of who is viewing).
 *
 * Requires the JS build of jahia-store-template.
 */
describe('Module dependency lists (MOD-1705)', () => {
    const siteKey = 'moddeps';
    const repo = `/sites/${siteKey}/contents/modules-repository`;
    const detailUrl = (name: string) => `/cms/render/default/en${repo}/${name}.html`;

    const createForgeModule: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/createForgeModule.graphql');

    const addNodeWithProps: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/addNodeWithProperties.graphql');

    const islandBundle = '/modules/jahia-store-template/dist/client/components/forge/ModuleEditor.client.tsx.js';

    /**
     * Creates a published `jnt:forgeModule` under the shared repo. `published` is not part of
     * `createForgeModule.graphql` (it only sets `jcr:title`), so it is flipped separately, exactly
     * like 20-accessibility.cy.ts already does.
     */
    const createModule = (name: string, title: string) => {
        cy.apollo({
            mutation: createForgeModule,
            variables: {parentPath: repo, name, title}
        });
        setNodeProperty(`${repo}/${name}`, 'published', 'true', 'en');
    };

    /**
     * Adds a single published version under a module, with an optional multi-valued `references`
     * list.
     *
     * UNTRIED-STEP CHECK: `InputJCRProperty` (see the GraphQL schema's `values: [String]` field,
     * "The values to set (for multivalued properties)") is passed straight through to
     * `addNode(properties: $properties)` here — no existing spec in this suite had ever exercised
     * `values` on a freshly-created node before this one. It is asserted indirectly below: every
     * case that depends on a `references` array actually resolving correctly (forward AND reverse)
     * only works if the mutation truly wrote a multi-valued property, so a silent no-op here would
     * fail cases 1-5, not just report a GraphQL error.
     */
    const addVersion = (modulePath: string, name: string, references?: string[]) =>
        cy.apollo({
            mutation: addNodeWithProps,
            variables: {
                parentPath: modulePath,
                name,
                primaryNodeType: 'jnt:forgeModuleVersion',
                properties: [
                    {name: 'versionNumber', value: '1.0.0'},
                    {name: 'published', value: 'true'},
                    ...(references ? [{name: 'references', values: references}] : [])
                ]
            }
        });

    before(function () {
        cy.request({url: islandBundle, failOnStatusCode: false}).then(res => {
            if (res.status !== 200) {
                cy.log('jahia-store-template JS module not deployed — skipping dependencies spec');
                this.skip();
            }
        });
        cy.login();
        try {
            deleteSite(siteKey);
        } catch {
            // ignore — first run.
        }

        createSite(siteKey, {
            languages: 'en',
            templateSet: 'jahia-store-template',
            serverName: 'moddeps.local',
            locale: 'en'
        });

        // The target of every reverse-list (dependants) assertion below. Its own version declares
        // no dependencies (the legacy "none" sentinel), but it still has plenty of dependants, so
        // its own `[data-dependency-lists]` DOES render (case 6 needs a module with NEITHER side
        // populated, which "base" is not).
        createModule('base', 'Base Library');
        addVersion(`${repo}/base`, 'v100', ['none']);

        // Case 1 + the "regression" half of case 2: "base=[1.0,2)" is a version-ranged reference -
        // dependencyRefNames()/forgeDependants() both strip everything from "=" onward before
        // comparing, so this must still resolve to "base". `default` is a platform module with no
        // store content under this site, and `no-such-module` does not exist anywhere -
        // resolveStoreModules() is documented to silently drop names that resolve to nothing,
        // exactly like the legacy view did.
        createModule('consumer', 'Consumer Module');
        addVersion(`${repo}/consumer`, 'v100', ['default', 'base=[1.0,2)', 'no-such-module']);

        // Case 3: the leading space that CreateEntryFromJar's naive `split(",")` leaves behind
        // (`"default, base"` splits to `["default", " base"]`, un-trimmed). forgeDependants()'s
        // second LIKE term (`LOWER(v.[references]) LIKE ' base%'`) exists specifically for this
        // spelling.
        createModule('leadingspace', 'Leading Space Dependant');
        addVersion(`${repo}/leadingspace`, 'v100', [' base']);

        // Case 4 — THE critical regression check. `"BASE"` is placed SECOND in a two-value array
        // (not alone) so the test cannot pass merely because Jackrabbit's LOWER() happens to touch
        // a lone value — dependencies.ts's own comment flags as UNVERIFIED whether LOWER() applies
        // per-value on a MULTI-VALUED property or only to the property's first value. If it only
        // lowers the first value, "other" (already lowercase) would mask the bug and this test
        // would give a false pass with `references: ['BASE']` alone.
        createModule('upperref', 'Uppercase Reference');
        addVersion(`${repo}/upperref`, 'v100', ['other', 'BASE']);

        // Case 5: a module that (redundantly, as an author might actually write) lists itself
        // alongside a real dependency. Both forward (dependencyRefNames) and reverse
        // (forgeDependants) exclude the node's own identifier/name, so neither column may ever
        // link a module to itself.
        createModule('selfref', 'Self Referencing Module');
        addVersion(`${repo}/selfref`, 'v100', ['selfref', 'base']);

        // Case 6: the legacy "no dependencies declared" sentinel, and nothing else in this site
        // references it — dependencies.length === 0 AND dependants.length === 0, so
        // ForgeEntryDetail must not mount `[data-dependency-lists]` at all.
        createModule('standalone', 'Standalone Module');
        addVersion(`${repo}/standalone`, 'v100', ['none']);
    });

    after(() => {
        cy.login();
        deleteSite(siteKey);
    });

    beforeEach(() => {
        cy.login();
    });

    it('case 1 — forward list resolves a real dependency and drops the platform module and the unknown name', () => {
        cy.visit(detailUrl('consumer'));
        cy.contains('h1', 'Consumer Module').should('be.visible');
        cy.get('[data-dependency-lists]', {timeout: 20000});
        cy.get('[data-dependency-column="dependencies"]').within(() => {
            cy.get('[data-dependency="base"]').should('exist');
            // "default" is a platform module, not store content — resolveStoreModules() finds no
            // matching jnt:forgeModule under this site and silently drops it, by design.
            cy.get('[data-dependency="default"]').should('not.exist');
            // Never resolves to anything, anywhere.
            cy.get('[data-dependency="no-such-module"]').should('not.exist');
        });
    });

    it('case 2 — reverse list finds the dependant even though its reference was version-ranged', () => {
        // Regression test: "base=[1.0,2)" (a version range) previously was never matched in the
        // reverse direction — forgeDependants() strips everything after "=" before comparing, the
        // same way the forward side does.
        cy.visit(detailUrl('base'));
        cy.contains('h1', 'Base Library').should('be.visible');
        cy.get('[data-dependency-lists]', {timeout: 20000});
        cy.get('[data-dependency-column="dependants"] [data-dependency="consumer"]').should('exist');
    });

    it('case 3 — reverse list finds a dependant spelled with the untrimmed leading space', () => {
        cy.visit(detailUrl('base'));
        cy.get('[data-dependency-lists]', {timeout: 20000});
        cy.get('[data-dependency-column="dependants"] [data-dependency="leadingspace"]').should('exist');
    });

    it('case 4 — CRITICAL: reverse list is case-insensitive ("BASE" must still resolve to "base")', () => {
        // This is the regression test for a just-fixed bug where the reverse JCR query compared
        // `references` case-sensitively, so an author-typed "BASE" (as it would appear verbatim in
        // a hand-written manifest) never matched the module named "base". If this assertion fails,
        // treat it as a genuine product regression — do NOT loosen it (e.g. by lower-casing the
        // expectation or switching to a case-insensitive contains) to make the spec pass.
        cy.visit(detailUrl('base'));
        cy.get('[data-dependency-lists]', {timeout: 20000});
        cy.get('[data-dependency-column="dependants"] [data-dependency="upperref"]').should('exist');
    });

    it('case 5 — a self-referencing module never links to itself, in either column', () => {
        cy.visit(detailUrl('selfref'));
        cy.contains('h1', 'Self Referencing Module').should('be.visible');
        // The section still renders (it depends on "base"), so this cannot pass merely because
        // the section was absent.
        cy.get('[data-dependency-lists]', {timeout: 20000});
        cy.get('[data-dependency-column="dependencies"]').within(() => {
            cy.get('[data-dependency="base"]').should('exist');
            cy.get('[data-dependency="selfref"]').should('not.exist');
        });
        cy.get('[data-dependency-column="dependants"] [data-dependency="selfref"]').should('not.exist');
    });

    it('case 6 — a module with no declared dependencies and no dependants renders no section at all', () => {
        cy.visit(detailUrl('standalone'));
        cy.contains('h1', 'Standalone Module').should('be.visible');
        // Give the page a moment to fully settle (e.g. the editor island) before asserting an
        // absence, so this cannot pass merely because nothing had rendered yet.
        cy.get('[data-editor-ready]', {timeout: 20000});
        cy.get('[data-dependency-lists]').should('not.exist');
    });
});
