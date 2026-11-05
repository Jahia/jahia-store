# Changelog

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
