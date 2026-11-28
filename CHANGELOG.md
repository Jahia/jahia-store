# Changelog

## 5.3.0 (unreleased)

### Added

- **Community (reviewed) status.** A module or package can take the status `community-reviewed`, shown as
  *Community (reviewed)*, next to Community, Labs, Supported and Legacy. jContent now shows a label for each
  status value instead of its key.

## 5.2.0 (2026-11-18)

> **For administrators: the `store-administrator` role now has Jahia's `publish` permission.** Store
> administrators can publish any content of the store site that they can edit, not only security
> advisories. Before you upgrade, check who holds this role. If you do not want this, remove `publish`
> from the role in *Administration > Roles and permissions* after the upgrade; store administrators then
> need someone else, a site administrator for example, to publish their advisories. Do it again after
> each later upgrade of the module: like any module role, `store-administrator` is reset from the
> module's `roles.xml` when a new version of the module is installed.

### Security

- **JSTORE-SEC-005: anonymous visitors reached unpublished store content outside the module page.**
  The `published` flag of modules, packages and versions was enforced only when the module page was
  rendered. Other paths ignored it: the module-list RSS feed (`feed.xml`) listed the published
  versions of unpublished modules with their download links, the artifact of a draft version
  downloaded from `/files` and from `/modules/mavenproxy`, the module page redirected a draft to the
  home page (which told anyone that the draft existed), and the anonymous `moduleList.json` and
  `securityUpdates.json` views were open on every content folder of the repository. Every path now
  applies the same rule: a module, package or version is public only when it and everything above it
  are published.
  - RSS feed: a version is listed only when the version and its module are published, as in
    `moduleList.json`. It lists no draft, even for its owner.
  - `/files`: the artifact of a draft version, or of any version of an unpublished module, answers 404
    to anyone who cannot edit the draft.
  - `/modules/mavenproxy`: an artifact (or the `maven-metadata.xml`) of an unpublished module or
    version of the catalogue answers 404 to anyone who cannot edit it. Maven coordinates that the
    catalogue does not list are still served, as before.
  - Module and package pages: a draft answers 404, the same answer as a page that does not exist,
    instead of redirecting to the home page.
  - `moduleList.json` and `securityUpdates.json` are served only at a site's
    `contents/modules-repository` folder.
  - `moduleList.json` is unchanged. It still returns the JCR `path` of the store folder and of each
    published module: Jahia's module manager needs them.

  **Who is impacted:** every store with unpublished modules or versions in the live workspace.

  **What changes for owners:** module owners and administrators still open their draft pages and
  download their draft artifacts from `/files` and through the proxy. A visitor who follows an old
  link to an unpublished module now gets the "page not found" page instead of the home page.

  **What to do:** nothing.
- **JSTORE-SEC-012: the media MIME guard missed files written in live and files nested deep in a
  module.** The guard removes a script-capable file (SVG, HTML, XML, or bytes that start with markup)
  stored under a store module or package, so that the file servlet cannot serve it inline. It watched
  only the edit workspace, but store developers write in live through the store front-end, and it
  stopped looking for the module after 32 parent nodes, so a file nested deeper was skipped. Such a
  file stayed in live and ran as script on the store's origin when a visitor opened it. The guard now
  watches both workspaces and has no depth limit.

  SVG and HTML are no longer accepted as module media at all, even when they are declared honestly.
  The guard removes any file in `contents/modules-repository` that is SVG or HTML by its declared MIME
  type, by its extension (`.svg`, `.svgz`, `.html`, `.htm`, `.xhtml`, `.xht`, `.shtml`), or by its
  content (`<svg`, `<!doctype html`, `<html` or `<script` in its first 512 bytes; this content check
  does not apply to module archives). Module icons and screenshots must be PNG, JPEG, GIF or WebP.

  The upload action also stores a package JAR or a
  JavaScript module tgz with the content type of its extension (`application/java-archive`,
  `application/gzip`) instead of the one the browser sent, and refuses a file that does not start with
  the zip or gzip signature (`forge.uploadJar.error.wrong.format`).

  **Who is impacted:** every installation where store developers can upload, and every module that
  uses an SVG icon or screenshot: a new SVG upload is removed, and its owner must upload a raster
  instead.

  **What to do:** nothing for new files. The guard checks a file when it is written, so a file planted
  before the upgrade stays: look for files under `contents/modules-repository`, in edit and in live,
  whose `jcr:mimeType` is an SVG, HTML or XML type, whose name has an SVG or HTML extension, or whose
  content starts with `<`, and delete them. Deploy `jahia-store-template` with the same rule, which
  refuses SVG and HTML in the icon and screenshot upload form.
- **JSTORE-SEC-001: a store's root category could point at another site's categories.**
  The category operations of the store administration run in a system session, which ignores
  permissions. `forge.setRootCategory` accepted any category, so a user with the store settings
  permission (`siteAdminForgeSettings`) on one site could set as root a category of another site,
  then read, add, rename and delete categories there. `forge.addCategory` also accepted a name such
  as `../x`, which created the node above the root category.
  - The root category must now be a category under `/sites/systemsite/categories`, the tree where
    Jahia keeps categories, or that node itself. `forge.setRootCategory` refuses any other category
    with an error and keeps the current root: a category of another site, one of the store's own
    site, or any node outside that tree.
  - The rule is checked again each time the stored root is used, because the settings file can hold
    a root set before this version. If the root is outside that tree, the *Categories* page shows no
    root and no category, `forge.addCategory`, `forge.updateCategoryTitles` and
    `forge.deleteCategory` are refused, and the module logs a warning that names the site.
  - A category name must be a single name: `.`, `..`, the characters `/ \ : [ ] | *` and control
    characters are refused with an error, and nothing is created.

  **Who is impacted:** installations where a user holds the store settings permission on a site but
  must not change the categories of other sites.

  **What to do:** if a store's root category is not under `/sites/systemsite/categories`, the module
  logs `The root category of site <siteKey> is not under /sites/systemsite/categories` or
  `The root category of site <siteKey> is not a category under /sites/systemsite/categories`. Create
  the store's categories under `/sites/systemsite/categories` and set the new root in
  *Store administration > Categories*. Modules keep the categories already assigned to them, but
  the store only offers the children of the new root.

- **JSTORE-SEC-017: only the owner of a module or a store administrator can flag its versions as security
  fixes.** The security flags of a module version (`securityFix`, `securitySeverity`,
  `securityReferences`, `securityNote`), which `securityUpdates.json` sends to every Jahia instance,
  could be written by any store developer on any module, because `store-developer` writes live content
  in the whole site. A developer could flag, change or unflag the versions of a module they do not own,
  and the feed did not say who did it. The module now refuses a change of these properties unless the
  writer holds the `owner` role on the module node (granted to the developer who uploaded the module) or
  `jahiaForgeModerateModule` there (`store-administrator`). Each accepted change records who made it and
  when, for audit, in two hidden properties of the version (`securityFlaggedBy`, `securityFlaggedDate`)
  that only the module sets. The feed does not include them and its format is unchanged.

  **Who is impacted:** stores that run a 5.2.0 snapshot. Released versions do not have the security
  flags.

  **What changes:** a store developer who uploaded only a version of another developer's module (SEC-381)
  can no longer flag it; the module owner or a store administrator can. A version flagged before this
  change has no `securityFlaggedBy`, so the feed does not list it and the module logs
  `records nobody setting its security flag` with its path: have the owner save its security
  information again. `jahia-store-template` still shows the security editor to every store developer; a
  developer who does not own the module gets an error when saving.

### Added

- **Security updates feed.** `/contents/modules-repository.securityUpdates.json` lists the security
  advisories published by the security team and the module versions their owners flag as containing
  security fixes, so that Jahia instances can warn their administrators. See the README.
  - New content type *Security advisory* (`jnt:forgeSecurityAdvisory`), created in the
    `contents/security-advisories` folder by store or site administrators. An advisory is listed
    once it is published in Jahia. Edits are listed when the advisory is published again. Unpublish it
    to withdraw it. Mark a draft *Work in progress* to keep it out: Jahia does not publish it, not even
    with the whole folder or site.
  - The `store-administrator` role gets Jahia's `publish` permission, so that store administrators
    can publish the advisories they write. New installations get it from the module's roles. On an
    existing installation, the module adds it to `/roles/store-administrator` the first time this
    version starts and logs `Added the publish permission to role store-administrator`. If it logs
    `Could not add the publish permission to role store-administrator` instead, add `publish` to the
    role by hand.
  - Store developers cannot create, change or delete advisories, in the edit or the live workspace:
    the module denies the `store-developer` role on the advisories folder to each user and group
    that holds it on the site, and keeps this up to date. Store developers keep their write access
    elsewhere in the site. An advisory written directly in live is ignored.
  - Optional *Introduced in* property on advisories (`introducedIn`): the first version of a
    component that has the problem, written like `fixedIn` (`[groupId:]component@version`), one
    value per component listed in `fixedIn`. The feed adds it to that component's fix as
    `introducedVersion`, so clients stop reporting the versions below it. Without it, every version
    below a fixed version counts as affected. The field is optional (`schemaVersion` stays 1).
    jContent refuses a malformed value. A value that names a component not in `fixedIn` or gives
    another groupId than `fixedIn`, and every value of a component that has more than one, are
    ignored: that component has no `introducedVersion`, and the module logs a warning with the
    advisory path and the reason. Unlike an invalid `fixedIn`, an invalid `introducedIn` never stops
    the feed.
  - The version in `fixedIn` and `introducedIn` follows the security-updates client rule (a number, then
    optionally `.`, `-`, `_` or `+` and a letter or digit; at most 64 characters, 9 digits per leading
    number): jContent refuses `3a` or `3-`, and the feed treats such a value as malformed.
  - New optional properties on module versions: `securityFix`, `securitySeverity`,
    `securityReferences` and `securityNote`.
  - `moduleList.json` is unchanged.

  **What to do:** deploy `jahia-store-template` 5.1.0 or later, which creates the
  `security-advisories` folder in new store sites. In an existing store site, create a content
  folder named `security-advisories` under `contents` before adding the first advisory, and publish
  it. The module locks the folder as soon as it is created, and again at each module start and each
  role change.

### Fixed

- **The SEC-366 migration now removes the stale grant from `j:permissionNames`, the list Jahia
  reads.** The startup migration added in 5.1.2 removed `jcr:modifyAccessControl_live` only from the
  role's `j:permissions` references. Jahia reads a role's `j:permissionNames` first and uses
  `j:permissions` only when `j:permissionNames` is absent, and a role imported from `roles.xml` has
  `j:permissionNames`: on an installation where the 5.1.2 `roles.xml` import did not run (a
  redeployed snapshot, a failed import, a role changed after the import), the migration found nothing
  and `store-developer` kept its live ACL authority. The migration now removes the permission from
  `j:permissionNames` (and from `j:permissions` when present), flushes Jahia's role-privilege caches,
  and runs at each module start. It logs
  `SEC-366: removed jcr:modifyAccessControl_live from role store-developer` when it removed something.

  **Who is impacted:** installations that upgraded to 5.1.2 without the `roles.xml` import running.
  A normal upgrade imports the roles of the new version and was already fixed.

  **What to do:** nothing. If the module logs `SEC-366: could not remove ...`, remove
  `jcr:modifyAccessControl_live` from `/roles/store-developer` by hand.

## 5.1.2 (2026-09-23)

Deploy with `jahia-store-template` 5.0.6, which displays the upload date and handles the public edge
added here.

### Security

- **The release workflow no longer opens a remote debugging session.** On a failed step,
  `on-release.yml` started a tmate session with no access limit, while the CI account's SSH key was loaded
  in the agent: anyone who read the public job log could connect. The step is removed. Every action of the
  workflows is now pinned to a commit SHA (Dependabot keeps the pins current). Pre-release audit finding
  JSTORE-SEC-019.
- **SEC-366 / GHSA-f882-3xwv-3439: the `store-developer` role could rewrite the site's live ACL.**
  The role carried `jcr:modifyAccessControl_live` at site scope, so a store developer could grant
  themselves `store-administrator` or remove the site administrators, in live, without going
  through publication. The role no longer has that permission. Uploads still make the uploader
  owner of what they upload: that grant is now applied by the module itself.

  **Who is impacted:** every installation that grants `store-developer`.

  **What to do:** nothing in most cases. On startup, the module removes the permission from the
  role and logs `SEC-366: removed jcr:modifyAccessControl_live from role store-developer`. If it
  logs `SEC-366: could not remove ...` instead, the installation is still exposed: remove
  `jcr:modifyAccessControl_live` from `/roles/store-developer` by hand.

- **SEC-381: an upload grants the `owner` role only on the nodes it creates.** Before this
  change, a store developer who uploaded a new version of a module (or package) that another
  developer had created was also granted `owner` on that module node.

  **Who is impacted:** stores where several developers upload versions of the same module.
  Released versions (5.1.1 and earlier) are not affected: the elevated owner grant that carried
  the flaw was introduced after 5.1.1 by the SEC-366 fix.

  **What changes:** the developer who first uploads a module still owns the module node and every
  version they upload. Another developer who uploads a version of that module now owns **only the
  version node** they created, not the module node. Store developers can still edit the
  information of each other's modules, as before: that comes from the `store-developer` role,
  not from the `owner` grant.

  **What to do:** nothing. No configuration or migration step is required.

### Added

- **Release dates come from the upload time.** Module and package versions get an `uploadDate`
  property, stamped once when the version is uploaded. Before, the storefront used
  `jcr:lastModified`, so any later edit re-dated the release.

  **What to do:** existing versions have no `uploadDate` until you run
  `scripts/backfill-upload-date.groovy`, which copies their current `jcr:lastModified`. Run it
  once per workspace (`default` and `live`: versions uploaded from the live storefront exist only
  in live), then check that `jcr:lastModified` did not change.

- **Public-edge support (SUPPORT-687).** A request that the reverse proxy marks with
  `X-Jahia-Edge: public` is always served as guest, whatever credentials it carries, and the
  fragment cache keeps public and VPN renders apart.

  **Who is impacted:** only installations whose proxy sets `X-Jahia-Edge`. Without that header,
  behaviour is unchanged.
