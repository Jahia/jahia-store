# Git-backed modules

A git-backed module is a module whose description, versions and artefacts live in a git
repository that the store reads and never writes. The store serves it as a `jnt:forgeModule`
node with `jnt:forgeModuleVersion` children, through a read-only external data provider
(the `external-provider` module of Jahia), so the storefront, the administration, the GraphQL
API and the catalogue JSON treat it as any other module. ADR 0001 records why.

This document is the contract: the files the repository must hold, the node mapping, the
registration, the download path and the cache.

## The repository contract

### `STORE.md`

The repository holds a `STORE.md` at its root. It is a Markdown file with a YAML front matter.
The front matter carries the store fields, and the Markdown body is the description of the module.

```markdown
---
name: my-module
groupId: org.example.modules
title: My Module
artifact: https://github.com/example/my-module/releases/download/v{version}/my-module-{version}.jar
license: Apache-2.0
icon: docs/icon.png
author: Example Inc.
authorUrl: https://example.com
authorEmail: modules@example.com
requiredVersion: 8.2.0.0
dependencies:
  - default
---

The description of the module, in Markdown.
```

| Field | Mandatory | Meaning |
|---|---|---|
| `name` | yes | The module name, which is the `artifactId` and the node name. A Jahia server identifies a module by this name alone. |
| `groupId` | yes | The Maven `groupId`. One `groupId` per module name across both stores. |
| `title` | yes | The title shown in the store. |
| `artifact` | yes | The URL of the artefact of a version. `{version}` is replaced by the version number. The URL is absolute and uses `http` or `https`. |
| `license` | no | The license of the module, as a short name or a URL. |
| `icon` | no | The path, inside the repository, of the icon of the module. |
| `author`, `authorUrl`, `authorEmail` | no | The author shown in the store. |
| `requiredVersion` | no | The lowest Jahia version the module runs on. |
| `dependencies` | no | The Jahia modules the module depends on. |

A repository that lacks a mandatory field, or whose `artifact` is not an absolute `http` or
`https` URL, is refused at registration, and the error names the field.

### Versions are tags

Each tag of the repository that matches the version pattern is one version. The pattern is a
property of the registration, and a store-level default applies when the registration names
none. The default pattern accepts `1.2.3` and `v1.2.3`, and a pre-release tag such as
`1.2.3-rc1` is not a version.

The version number is the tag without its `v` prefix. The store reads `STORE.md` as it stands
at the tag, so the `artifact`, the `requiredVersion` and the `dependencies` of a version are the
ones declared at that tag. The description, the title, the icon and the author of the module
come from the root `STORE.md` on the default branch of the repository.

### `CHANGELOG.md`

The release notes of a version come from the `CHANGELOG.md` at the tag, in the
[Keep a Changelog](https://keepachangelog.com/) layout: the section whose heading is
`## [1.2.3]`, `## 1.2.3` or `## v1.2.3`, up to the next `##` heading. A version whose section
is absent has no release notes.

### `store/` directory

A repository may hold a `store/` directory for the information that does not fit one file. It is
reserved for the long description, the screenshots and the per-version files, and the store reads
`store/index.md` in place of `STORE.md` when it exists. The layout of that directory is not fixed
yet.

## The node mapping

The data source serves one module per registration. The root of the provider is the module.

| Path in the provider | Node type | Source |
|---|---|---|
| `/` | `jnt:forgeModule` | The root `STORE.md`. |
| `/<version>` | `jnt:forgeModuleVersion` | One per tag that matches the pattern. |
| `/icon` | `jnt:folder` | Always present. |
| `/icon/<file>` | `jnt:file` | The file that the `icon` field names, when it names one. |
| `/screenshots` | `jnt:forgeScreenshotsList` | Always present, empty. |

| Property of `jnt:forgeModule` | Source |
|---|---|
| `jcr:title` | `title` |
| `groupId` | `groupId` |
| `description` | The Markdown body of `STORE.md`, rendered to HTML. |
| `license` | `license` |
| `authorURL`, `authorEmail` | `authorUrl`, `authorEmail` |
| `codeRepository` | The URL of the repository. |
| `published` | `true`. A git-backed module is published by its registration. |
| `status` | `community`. |
| `supportedByJahia`, `reviewedByJahia` | `false`. |

| Property of `jnt:forgeModuleVersion` | Source |
|---|---|
| `versionNumber` | The tag without its `v` prefix. |
| `url` | The `artifact` of the `STORE.md` at the tag, with `{version}` replaced. |
| `changeLog` | The section of `CHANGELOG.md` at the tag, rendered to HTML. |
| `references` | The `dependencies` of the `STORE.md` at the tag. |
| `uploadDate` | The date of the tag. |
| `published` | `true`. |

The i18n properties (`description`, `license`, `changeLog`) hold the same value in every
language.

The node identifiers are the paths inside the provider, so they are stable across restarts and
refreshes. Jahia may attach local properties and access control to an external node of these
types, which is where the store keeps what the repository does not hold: the owner, the categories
and, later, the approval and the verification verdicts.

## Registration

A registration is a mount point node of type `jnt:gitStoreMountPoint`, a child of `/mounts`,
whose `mountPoint` property references the `modules-repository` folder of the store site. Its
name is `<module name>-mount`, so the module is served at
`/sites/<siteKey>/contents/modules-repository/<module name>`.

| Property | Meaning |
|---|---|
| `j:gitUrl` | The HTTP URL of the repository. |
| `j:tagPattern` | The regular expression a tag must match to be a version. Optional. |
| `j:branch` | The branch that holds the root `STORE.md`. Optional, the default branch of the repository otherwise. |

The GraphQL mutations `forge { registerGitModule }` and `forge { unregisterGitModule }` create
and remove the node, for a caller who holds the `siteAdminForgeSettings` permission on the site.
`registerGitModule` reads the repository first and refuses a repository that fails the contract.
The query `forge { gitModules }` lists the registrations of a site.

## Downloads

The catalogue JSON and the storefront build the download URL of a version from the module
coordinates, on the Maven proxy of the site:
`/modules/mavenproxy/<siteKey>/<groupId as path>/<name>/<version>/<name>-<version>.jar`.

The proxy resolves these coordinates to the version node of the site. When that node holds a
`url`, the proxy streams the artefact from that URL without credentials. Otherwise it streams it
from the Maven repository of the site, as before. A git-backed module therefore needs no change in
the consumers.

## The cache

The data source keeps a bare clone of the repository under the Jahia data directory, in
`forge-git/<hash of the URL>`. A read of the module lists the remote tags, fetches the ones it has
not seen and reads the files it needs from the clone. The list of tags is refreshed on demand, at
most once per refresh interval, so a render never waits on the remote twice in a row. The mutation
`forge { refreshGitModule }` forces the next read to refresh.

The refresh interval and the default tag pattern are the configuration of the module, in
`org.jahia.modules.forge.git.cfg`.
