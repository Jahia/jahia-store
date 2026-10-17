---
status: proposed
date: 2026-10-17
decision-makers: Jahia Store team
---

# Git-backed modules come from a read-only external data provider

## Context and Problem Statement

The store must list modules whose source code and artefacts live outside Jahia, in a git repository that a contributor names by its HTTP URL. The store needs the description of the module, its versions and the artefact of each version, and it holds none of these itself.

Every consumer of the store, from the storefront to the catalogue that Jahia servers read, addresses a module by its node types. How does the store expose a module whose data lives in a repository, without a copy that drifts from it?

## Decision Drivers

* The repository is the only source of truth for a git-backed module.
* The store never writes into a contributor's repository.
* Every consumer of the store keeps reading the same node types.

## Considered Options

* A read-only external data provider that serves the repository as nodes
* An import job that copies the repository into JCR nodes
* A CI-driven upload of the metadata through the store API

## Decision Outcome

Chosen option: "A read-only external data provider that serves the repository as nodes", because the repository stays the only source, the store writes nothing, and every consumer reads ordinary nodes.

### Consequences

* Good, because a module needs no copy in the store and no synchronisation.
* Good, because the contract of the catalogue JSON and of the storefront is unchanged.
* Bad, because a read of the module depends on a cache of the remote repository.
* Bad, because an external provider serves one content to both workspaces, so the publication state of a git-backed module is a state the store holds beside the node.

### Confirmation

A module registered by its repository URL lists in the catalogue JSON with one version per matching tag, and no write reaches the repository.

## Pros and Cons of the Options

### A read-only external data provider that serves the repository as nodes

* Good, because the store holds no copy of the module.
* Bad, because the provider must answer a query that spans local and external nodes.

### An import job that copies the repository into JCR nodes

Rejected because a copy drifts from the repository between two runs of the job.

* Good, because every store feature works on a plain node.
* Bad, because the store holds two sources for one module and must reconcile them.

### A CI-driven upload of the metadata through the store API

Rejected because the store then holds a copy that each contributor's CI must keep in step.

* Good, because the store controls what enters it.
* Bad, because a contributor without a CI has no way in.
