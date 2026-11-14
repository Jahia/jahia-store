# Changelog

## 5.2.0 (unreleased)

> **For administrators: the `store-administrator` role now has Jahia's `publish` permission.** Store
> administrators can publish any content of the store site that they can edit, not only security
> advisories. Before you upgrade, check who holds this role. If you do not want this, remove `publish`
> from the role in *Administration > Roles and permissions* after the upgrade; store administrators then
> need someone else, a site administrator for example, to publish their advisories. Do it again after
> each later upgrade of the module: like any module role, `store-administrator` is reset from the
> module's `roles.xml` when a new version of the module is installed.

### Added

- **Security updates feed.** `/contents/modules-repository.securityUpdates.json` lists the security
  advisories published by the security team and the module versions their owners flag as containing
  security fixes, so that Jahia instances can warn their administrators. See the README.
  - New content type *Security advisory* (`jnt:forgeSecurityAdvisory`), created in the
    `contents/security-advisories` folder by store or site administrators. An advisory is listed
    once it is published in Jahia with its *Published in the security feed* box checked. Edits are
    listed when the advisory is published again. Unpublish it, or uncheck the box and publish, to
    withdraw it.
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
