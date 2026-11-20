import {DocumentNode} from 'graphql';

const setRequireApprovalDoc: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/setValidationRequireApproval.graphql');
const requestDoc: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/forgeRequestValidation.graphql');
const answerDoc: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/forgeAnswerValidationCheck.graphql');
const validationDoc: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/query/forgeValidation.graphql');

/** The checks that wait for a human until a service runs them (jahia-store docs/validation.md). */
export const MANUAL_CHECKS = ['code-review', 'security'];

/**
 * The gate on the publication of a version: a version reaches the public once approved. The
 * suite turns it off for the specs that are not about it, and 29-validation turns it on.
 */
export function setApprovalRequired(required: boolean): void {
    cy.apollo({mutation: setRequireApprovalDoc, variables: {value: String(required)}});
}

/** Validate a version as the current Apollo user and read its record. */
export function requestValidation(siteKey: string, moduleName: string, version: string): Cypress.Chainable<{status: string}> {
    return cy.apollo({mutation: requestDoc, variables: {siteKey, moduleName, version}}).its('data.forge.requestValidation');
}

/** Answer a manual check as an administrator, as the dashboard does. */
export function answerCheck(answer: {siteKey: string; moduleName: string; version: string; check: string; answer: string}): void {
    cy.apollo({mutation: answerDoc, variables: answer});
}

/** Approve a version outright: validate it, then answer every manual check with an approval. */
export function approveVersion(siteKey: string, moduleName: string, version: string): void {
    requestValidation(siteKey, moduleName, version);
    for (const check of MANUAL_CHECKS) {
        answerCheck({siteKey, moduleName, version, check, answer: '{"status": "approved", "comments": ["Approved by the test suite"]}'});
    }
}

export interface ValidationRecord {
    status: string
    reason: string | null
    checks: {key: string; label: string; status: string; comments: string[]}[]
}

/** The record of a version, as the API gives it to its owner or an administrator. */
export function validationOf(siteKey: string, moduleName: string, version: string): Cypress.Chainable<ValidationRecord | null> {
    return cy.apollo({query: validationDoc, variables: {siteKey, moduleName, version}, fetchPolicy: 'no-cache'}).its('data.forge.validation');
}
