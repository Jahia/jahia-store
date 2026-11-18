import {DocumentNode} from 'graphql';

/**
 * Test categories for the store's root category (JSTORE-SEC-001).
 *
 * Jahia keeps categories in one shared tree, not per site, and the module only accepts a root
 * category at or below this path. A root created under `/sites/<siteKey>` is refused. Deleting the
 * test site does not remove what a spec creates here, so each spec must call
 * `deleteSharedCategory` for every category it created, in its `after` hook.
 */
export const SHARED_CATEGORIES_PATH = '/sites/systemsite/categories';

const addNode: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/addNodeForTest.graphql');
const deleteNode: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/deleteNode.graphql');

/** Delete a test category of the shared tree, with its children. A missing node is not an error. */
export function deleteSharedCategory(name: string): void {
    cy.apollo({mutation: deleteNode, variables: {path: `${SHARED_CATEGORIES_PATH}/${name}`}, errorPolicy: 'all'});
}

/**
 * Create a `jnt:category` directly under the shared tree, removing a stale one left by an earlier
 * run first. Yields the new category's UUID.
 */
export function createSharedCategory(name: string): Cypress.Chainable<string> {
    deleteSharedCategory(name);
    return cy
        .apollo({
            mutation: addNode,
            variables: {parentPath: SHARED_CATEGORIES_PATH, name, primaryNodeType: 'jnt:category'}
        })
        .then(result => (result.data as {jcr: {addNode: {node: {uuid: string}}}}).jcr.addNode.node.uuid);
}
