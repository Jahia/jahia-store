import {DocumentNode} from 'graphql';

/**
 * Smoke tests: confirm both modules under test are deployed and ACTIVE.
 *
 * Bundle state is read via Jahia's Module Management REST API:
 *   GET /modules/api/bundles/<symbolicName>/<osgiVersion>/_info
 *
 * The OSGi version differs from the Maven version: bnd rewrites
 *   "4.3.1-SNAPSHOT"  ->  "4.3.1.SNAPSHOT"
 * (dot-qualifier instead of dash-qualifier). The helper below performs
 * the same substitution so tests can be parameterized with the Maven
 * version coming straight from the .env file.
 *
 * In addition, we probe one of jahia-store's CND-defined node types
 * via JCR — CND registration happens during bundle activation, so a hit
 * is an independent confirmation that the module reached ACTIVE state.
 */
describe('Modules deployed', () => {
    const getNodeTypeByName: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/query/getNodeTypeByName.graphql');

    const auth = {
        username: 'root',
        password: (Cypress.env('SUPER_USER_PASSWORD') as string) || 'root1234'
    };

    const toOsgiVersion = (mavenVersion: string): string => mavenVersion.replace(/-SNAPSHOT$/, '.SNAPSHOT');

    const bundleKey = (symbolicName: string, mavenVersion: string): string =>
        `${symbolicName}/${toOsgiVersion(mavenVersion)}`;

    const expectBundleActive = (symbolicName: string, mavenVersion: string) => {
        // An unset version would silently build the key "<name>/undefined" and fail
        // several assertions later with nothing pointing back at the cause.
        expect(mavenVersion, `no version configured for ${symbolicName} - set it in .env or cypress.config.ts`)
            .to.be.a('string').and.not.be.empty;
        const key = bundleKey(symbolicName, mavenVersion);
        cy.request({
            url: `/modules/api/bundles/${key}/_info`,
            auth,
            failOnStatusCode: false
        }).then(response => {
            expect(response.status, `GET _info for ${key}`).to.equal(200);
            // Response shape: { "<clusterNodeId>": { type, osgiState, moduleState } }.
            // On a single-node stack there's exactly one entry; in a cluster
            // every entry must report ACTIVE/STARTED.
            const entries = Object.values(response.body ?? {}) as Array<{
                osgiState?: string
                moduleState?: string
                type?: string
                message?: string
            }>;
            expect(entries, `info payload for ${key}`).to.have.length.greaterThan(0);
            entries.forEach((info, idx) => {
                // Module Management answers 200 with {message, cause} when the key does
                // not resolve, so the status check above cannot tell "not deployed" from
                // "deployed under a different version". Name that case explicitly -
                // otherwise a stale configured version reads as "expected undefined to
                // equal ACTIVE", which points at the wrong thing.
                expect(info.message, `no bundle for ${key} - the configured version is probably stale`)
                    .to.be.undefined;
                expect(info.osgiState, `osgiState[${idx}] for ${key}`).to.equal('ACTIVE');
                expect(info.moduleState, `moduleState[${idx}] for ${key}`).to.equal('STARTED');
            });
        });
    };

    before(() => {
        cy.login();
    });

    it('jahia-store bundle is ACTIVE', () => {
        expectBundleActive('jahia-store', Cypress.env('PRIVATEAPPSTORE_VERSION') as string);
    });

    it('jahia-store-template bundle is ACTIVE', () => {
        expectBundleActive('jahia-store-template', Cypress.env('STORE_TEMPLATE_VERSION') as string);
    });

    it('jahia-store CND is registered (jnt:forgeModule resolves)', () => {
        cy.apollo({query: getNodeTypeByName, variables: {name: 'jnt:forgeModule'}})
            .its('data.jcr.nodeTypeByName')
            .should((nodeType: { name: string } | null) => {
                expect(nodeType, 'jnt:forgeModule should be registered').to.not.be.null;
                expect(nodeType?.name).to.equal('jnt:forgeModule');
            });
    });
});
