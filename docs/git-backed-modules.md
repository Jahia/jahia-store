# Git-backed modules

A git-backed module is a module whose description, versions and artefacts live in a git
repository that the store reads and never writes. The store serves it as a `jnt:forgeModule`
node with `jnt:forgeModuleVersion` children, through a read-only external data provider
(the `external-provider` module of Jahia), so the storefront, the administration, the GraphQL
API and the catalogue JSON treat it as any other module. ADR 0001 records why.

This document is the contract: the files the repository must hold, the node mapping, the
registration, the refresh, the download path and the cache.
[`examples/server-availability-manager/`](examples/server-availability-manager/) holds the store
data of one real module, written to this contract.

## The repository contract

The store reads one branch of the repository, the store branch: the branch that the module
configuration names, or the default branch of the repository when it names none. Everything the
store knows of the module is on that branch. Tags, other branches and the history play no part.

### `STORE.md`

The store branch holds a `STORE.md` at the root of the repository. It is a Markdown file with a
YAML front matter. The front matter carries the fields shared by every version, the Markdown body
is the description of the module, and a `## Versions` section of the body lists the released
versions.

```markdown
---
name: my-module
groupId: org.example.modules
title: My Module
license: Apache-2.0
icon: store/icon.png
screenshots:
  - store/screenshot-1.png
author: Example Inc.
authorUrl: https://example.com
authorEmail: modules@example.com
requiredVersion: 8.2.0.0
dependencies:
  - default
---

The description of the module, in Markdown.

## Versions

- [1.2.0](store/1.2.0)
- [1.1.0](store/1.1.0)

## Tabs

- [Installation](store/installation.md)
- [License](store/license.md)
```

| Field | Mandatory | Meaning |
|---|---|---|
| `name` | yes | The module name, which is the `artifactId` and the node name. A Jahia server identifies a module by this name alone. |
| `groupId` | yes | The Maven `groupId`. One `groupId` per module name across both stores. |
| `title` | yes | The title shown in the store. |
| `license` | no | The license of the module, as a short name or a URL. |
| `icon` | no | The path, inside the repository, of the icon of the module. |
| `screenshots` | no | The paths, inside the repository, of the screenshots of the module. |
| `author`, `authorUrl`, `authorEmail` | no | The author shown in the store. |
| `requiredVersion` | no | The lowest Jahia version the module runs on, for a version that states none. |
| `dependencies` | no | The Jahia modules the module depends on, for a version that states none. |

The `## Versions` section lists one version per list item: a link whose text is the version
number and whose target is the folder of that version, relative to `STORE.md`. The section is
not part of the description. A relative image or link in the description names a file of the
repository, relative to its root, and the store serves that file beside the module.

The `## Tabs` section lists the tabs of the page of the module beyond its overview, as many as
the module wants: one link per tab, whose text is the title of the tab and whose target is the
Markdown file of its content, relative to `STORE.md`. The page shows the tabs in the order of the
list, after the overview. A tab titled `License` takes the place of the `license` field on the
page. A relative image or link in a tab names a file of the repository, relative to the file of
the tab, as a Markdown viewer resolves it. A version number holds letters, digits, `.`, `_` and `-`, as a
Maven version does. A module with no `## Versions` section has no version yet.

A repository that lacks `STORE.md` on the store branch, lacks a mandatory field, names a version
or a tab twice, or links a tab to a file it does not hold is refused at registration, and the
error names the cause.

### The folder of a version

The folder of a version holds everything the store reads of that version: its `VERSION.md`, and
the images and other files that the release notes show.

```markdown
---
artifact: https://github.com/example/my-module/releases/download/v1.2.0/my-module-1.2.0.jar
date: 2026-03-04
tag: v1.2.0
disabled: false
requiredVersion: 8.2.1.0
dependencies:
  - default
---

### Improvements

- Added the thing. ![The thing](thing.png)
```

| Field | Mandatory | Meaning |
|---|---|---|
| `artifact` | yes | The URL of the artefact of the version: absolute, `http` or `https`. The store never guesses it. |
| `date` | no | The release date, `YYYY-MM-DD`. The date of the tip of the store branch otherwise. |
| `tag` | no | The git tag of the version. The store links it to its page on the git host. |
| `tagUrl` | no | The page of the tag. On GitHub the store derives it from `tag`, as `<repository>/releases/tag/<tag>`; another host names it here. |
| `changelog` | no | The page of the changelog of the version. On GitHub the store derives it from the tags of the version and of the one before it in the list, as `<repository>/compare/<previous>...<tag>`; another host, or another page, names it here. |
| `disabled` | no | `true` disables the version: the store serves it unpublished, so the public, the catalogue JSON and the download link ignore it, and the editors of the module see it marked as disabled. `false` by default. |
| `requiredVersion` | no | The lowest Jahia version this version runs on. The value of `STORE.md` otherwise. |
| `dependencies` | no | The Jahia modules this version depends on. The value of `STORE.md` otherwise. |

The Markdown body of `VERSION.md` is the release notes of the version. When the body is empty and
the folder holds a `CHANGELOG.md`, that file is the release notes. A relative link or image in
the notes names a file of the folder, and the store serves that file beside the version. A bare
URL in the notes is a link.

A version whose folder holds no `VERSION.md`, or whose `artifact` is absent or not an absolute
`http` or `https` URL, is refused with the version number and the cause.

## The node mapping

The data source serves one module per registration. The root of the provider is the module.

| Path in the provider | Node type | Source |
|---|---|---|
| `/` | `jnt:forgeModule` | `STORE.md`. |
| `/<version>` | `jnt:forgeModuleVersion` | One per version of the `## Versions` section, the disabled ones included. |
| `/<version>/<file>` | `jnt:file` | A file of the folder of the version, other than its Markdown files. |
| `/icon` | `jnt:folder` | Always present. |
| `/icon/<file>` | `jnt:file` | The file that the `icon` field names, when it names one. |
| `/screenshots` | `jnt:forgeScreenshotsList` | Always present. |
| `/screenshots/<file>` | `jnt:file` | One per file that the `screenshots` field names. |
| `/files` | `jnt:folder` | Always present. |
| `/files/<file>` | `jnt:file` | One per file the description or a tab shows, under its base name. |
| `/tabs` | `jnt:forgeTabs` | Always present. |
| `/tabs/<name>` | `jnt:forgeTab` | One per tab of the `## Tabs` section, named after its title in lowercase, with the title as `jcr:title` and the file rendered to HTML as `content`. |

| Property of `jnt:forgeModule` | Source |
|---|---|
| `jcr:title` | `title` |
| `groupId` | `groupId` |
| `description` | The Markdown body of `STORE.md` without its `## Versions` section, rendered to HTML. A relative image or link points to the file served under `/files`. |
| `license` | `license` |
| `authorURL`, `authorEmail` | `authorUrl`, `authorEmail` |
| `codeRepository` | The URL of the repository. |
| `published` | `true`. A git-backed module is published by its registration. |
| `status` | `community`. |
| `supportedByJahia`, `reviewedByJahia` | `false`. |

| Property of `jnt:forgeModuleVersion` | Source |
|---|---|
| `versionNumber` | The text of the link in the `## Versions` section. |
| `url` | `artifact` of `VERSION.md`. |
| `changeLog` | The release notes, rendered to HTML. A relative link points to the file served beside the version. |
| `references` | `dependencies` of `VERSION.md`, or of `STORE.md`. |
| `uploadDate` | `date` of `VERSION.md`, or the date of the tip of the store branch. |
| `published` | `false` when `VERSION.md` says `disabled: true`, `true` otherwise. |
| `tag`, `tagUrl`, `changelogUrl` | `tag` of `VERSION.md`, and the pages of the tag and of the changelog, named or derived. The storefront links them from the version. |

The i18n properties (`description`, `license`) hold the same value in every language.

The node identifiers are the paths inside the provider, so they are stable across restarts and
refreshes. The provider is read-only and nothing overrides what the repository says: the store
attaches no local property and no access control to a git-backed node. What the store will keep
beside a git-backed module, such as the approval and the verification verdicts, lives in the
registration.

## Registration

A registration is a mount point node of type `jnt:gitStoreMountPoint`, a child of `/mounts`,
whose `mountPoint` property references the `modules-repository` folder of the store site. Its
name is `<module name>-mount`, so the module is served at
`/sites/<siteKey>/contents/modules-repository/<module name>`.

| Property | Meaning |
|---|---|
| `j:gitUrl` | The HTTP URL of the repository. |
| `j:registeredBy` | The user the module is registered for. The module and its versions read that user as `jcr:createdBy`, which is how the storefront lists a developer's own modules. |

The definition also declares `j:tagPattern` and `j:branch`, which nothing reads or writes: an
earlier contract read versions from tags and a branch per registration, and Jahia refuses to drop
a property from a definition it has deployed.

Two entry points create the node, and both read the repository first and refuse one that fails
the contract, with the reason:

The "My modules" page marks a git-backed module with a "Git" badge, so a developer tells it
from the modules they uploaded.

- A store developer, from the "My modules" page of the storefront, posts the URL to the action
  `<modules-repository>.registerGitModule.do` over XMLHttpRequest. The action requires the
  `jahiaForgeUploadModule` permission on the repository, creates the node in a system session,
  because `/mounts` is not the developer's to write, and records the developer in
  `j:registeredBy`. A failure answers `{error}` with HTTP 200, as the upload action does.
- An administrator calls the GraphQL mutation `forge { registerGitModule }`, which requires
  the `siteAdminForgeSettings` permission on the site. `forge { unregisterGitModule }` is an
  administration mutation too, and the query `forge { gitModules }` lists the registrations of
  a site. `forge { refreshGitModule }` is open to the developer who registered the module as
  well, as the next section states.

### Removal

A registration is removed the way it was made: the developer who registered the module, or a
store administrator, presses "Remove from the store" on the page of the module, which calls the
action `<module>.unregisterGitModule.do` over XMLHttpRequest, and an administrator calls the
GraphQL mutation `forge { unregisterGitModule }`. The mount point node goes, and the repository
is untouched. A local module is deleted as a node from the same page, by its owner.

## Refresh

The store reads the repository once, when the module is first requested after the mount, and
serves that reading until a refresh. Nothing refreshes it on its own: no interval, no webhook.

A refresh reads the store branch again and replaces the reading as a whole, or keeps the previous
reading and reports the cause when the repository cannot be read or fails the contract. Two
entry points trigger one:

- The developer who registered the module, or a store administrator, presses "Refresh" on the
  page of the module in the storefront. The button calls the action
  `<module>.refreshGitModule.do` over XMLHttpRequest, which carries the CSRF token of the page.
  The action requires the `jahiaForgeUploadModule` permission, and refuses a caller who neither
  registered the module nor holds `siteAdminForgeSettings` on the site. It answers
  `{module, versions}` on success and `{error}` with HTTP 200 on a failure, as the registration
  does.
- A process outside the store, such as the release workflow of the repository, calls the GraphQL
  mutation `forge { refreshGitModule }` with the credentials of the developer who registered the
  module, or of an administrator. The CSRF guard of Jahia refuses an action called without the
  token of a page, and GraphQL is outside that guard.

  ```bash
  curl --fail --user "$STORE_USER:$STORE_PASSWORD" \
    --header "Origin: https://store.example.com" --header "Content-Type: application/json" \
    --data '{"query":"mutation { forge { refreshGitModule(siteKey: \"store\", name: \"my-module\") } }"}' \
    "https://store.example.com/modules/graphql"
  ```

  The mutation answers `true`, `false` when the module is not a git-backed module, and an error
  that names the cause when the repository cannot be read.

## Downloads

The storefront links a version of a git-backed module to the artefact URL its version node
declares, the one `VERSION.md` gives, so a visitor downloads from the repository's own host and
nothing passes through the store. A version without a declared URL keeps the proxy link below.

The catalogue JSON that Jahia servers read builds the download URL of every version from the
module coordinates, on the Maven proxy of the site:
`/modules/mavenproxy/<siteKey>/<groupId as path>/<name>/<version>/<name>-<version>.jar`.

The proxy resolves these coordinates to the version node of the site. When that node holds a
`url` and an external provider serves the module, the proxy streams the artefact from that URL
without credentials. Otherwise it streams it from the Maven repository of the site, as before. A
Jahia server therefore needs no change to install a git-backed module.

## The configuration and the cache

The configuration of the module, `org.jahia.modules.forge.git.cfg`, names the store branch
(`branch`, empty for the default branch of each repository) and the directory of the clones
(`cacheDir`, under the Jahia data directory by default).

The data source keeps a bare clone of the repository under that directory, in
`<hash of the URL>`. A reading of the module fetches the tip of the store branch, shallow, and
reads the files it needs from the clone. The reading stays in memory until a refresh; the clone
only serves the next fetch.
