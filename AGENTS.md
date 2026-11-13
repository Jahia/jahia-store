# AGENTS.md — jahia-store

Guidance for AI agents (and humans) working in this repo. Read this before editing.

## What this is

`jahia-store` is the **Jahia 8.2 Java/OSGi module** that powers the Private
App Store ("Forge"): the JCR content model, the GraphQL admin API, the Jahia
Actions for authoring, background jobs, the Maven proxy, and the
`moduleList.json` feed. The **presentation/authoring UI** for it lives in the
sibling **`jahia-store-template`** JavaScript module — this repo is the backend and the
contract.

- Maven coordinates: `org.jahia.community:jahia-store` (parent
  `org.jahia.modules:jahia-modules`). Packaging: OSGi bundle (JAR).
- Java source level is **below 16** — no `instanceof` pattern matching, no
  records in this module. Use classic `instanceof` + cast.
- This module ALSO ships the **jContent admin app** (`src/javascript/`,
  React 18 + Moonstone + Apollo, a webpack Module-Federation remote built by the
  `frontend-maven-plugin`). It registers the **Store administration** entry in the
  Jahia site administration with Settings / Categories / Roles nested under it.
  ⚠️ jContent composes a child admin route's target as **`<section>-<parentRoute>`**:
  the parent `forgeAdministration` targets `administration-sites:998`
  (`isSelectable:false`), and the three leaves target
  `administration-sites-forgeAdministration:NN` — NOT `forgeAdministration:NN`
  (which silently orphans them). Route *keys* (and thus admin URLs) are unchanged.

## Component model (how things get registered)

Two registration mechanisms coexist; know which applies before adding a class:

**OSGi Declarative Services** (`@Component`) is the ONLY registration mechanism in
this module. There is no blueprint extender and no `META-INF/spring` / hand-written
`OSGI-INF`. Everything that must activate — GraphQL extensions, the JAR-upload
**Action**, the **MavenProxy** servlet, the `ForgeSettings` service + deletion
listener, AND the **`PublishedModuleFilter`** render filter — is a `@Component`.
Which packages get DS descriptors generated is controlled by the bnd
**`_dsannotations`** instruction in `pom.xml`.

- ⚠️ **CRITICAL GOTCHA**: `_dsannotations` is an explicit allow-list that *replaces*
  bnd's default `*`, so it must enumerate **every** package that holds a `@Component`:
  `org.jahia.modules.forge.graphql.*`, `…actions.*`, `…proxy.*`, `…settings.*`, `…filters.*`,
  **and `…security.*`** (the advisories lock and its listeners). Any package left out
  is silently NOT scanned and its component never registers. This has bitten the module twice: a past commit narrowed it to
  graphql-only (un-registering every `@Component(service=Action.class)`), and
  `filters.*` was missing (so `PublishedModuleFilter` never registered → draft modules
  were anonymously readable, SECURITY-571 #54). Tests that only assert "the form
  exists" do not catch this — verify the generated `OSGI-INF/*.xml` descriptors after
  touching the pom.

## Actions vs GraphQL (the permission wall)

The Jahia `/modules/graphql` endpoint is **permission-gated**: ordinary
authenticated users are denied (`GqlAccessDeniedException`). This shapes the API:

- **Admin/owner operations** (Forge settings, categories, roles, owner metadata
  edits) go through GraphQL extensions in `…/graphql/` (require a privileged role,
  or rely on JCR ACLs for generic mutations).
- **Any authenticated user operations** must be **Jahia Actions**
  (`org.jahia.bin.Action`, `@Component(service=Action.class)`), invoked through
  the render servlet at `…/<node>.<ActionName>.do`.
  - The flagship example is **`CreateEntryFromJar`** (`actions/CreateEntryFromJar.java`):
    an authenticated user uploads a module JAR/WAR/TGZ; the action runs a Maven
    `deploy:deploy-file` to the site's forge URL, then creates the `jnt:forgeModule`
    + `jnt:forgeModuleVersion` nodes under `modules-repository` and grants the
    uploader the `owner` role. It runs in the workspace the `.do` is posted from
    (live, if posted from the public site). **`PublishModule`** (`.PublishModule.do`)
    flips a module's `published` flag once `CalculateCompletion.isPublishable`
    passes, and auto-publishes the latest version.
  - **CSRF**: Action `.do` POSTs are protected by OWASP CSRFGuard, which patches
    **XMLHttpRequest only** (not `fetch`, not plain `<form>` posts). The
    `jahia-store-template` client posts to actions via XHR; a `fetch`/form POST is
    rejected ("Required Token is missing" / "Request Token does not match Page
    Token"). The `/modules/graphql` endpoint is not CSRF-gated.

## JCR content model (essentials)

- `jnt:forgeModule` / `jnt:forgePackage` extend `jnt:post`, are `jmix:forgeElement`,
  and carry a `published` (boolean) flag. They still **declare** the legacy
  `jmix:reviews` / `jmix:rating` mixins in the CND, but the **review feature was
  removed** (no UI, no actions) — those mixins and `jnt:review*` are now dormant.
- `jnt:post` carries `jcr:title` + `content`.
- No same-name siblings — `addNode` with an existing name throws
  `ItemExistsException` (used for atomic per-user dedup).
- **Forge settings are NOT stored in JCR.** The `jmix:forgeSettings` mixin is dormant (like
  `jnt:review*` above). Per-site settings — the forge connection (`url`/`id`/`user`/`password`
  base64 + `rootCategoryUuid`) and the storefront branding read by the jahia-store-template chrome
  (`logoPath` plus footer strings `copyright` / `privacyUrl` / `termsUrl` / `cookiesUrl` /
  `facebookUrl` / `linkedinUrl` / `twitterUrl` / `youtubeUrl`) — live in a per-site **OSGi factory
  configuration** `<karaf.etc>/org.jahia.modules.forge.forgeSettings-<siteKey>.cfg`. They are served
  by `ForgeSettingsServiceImpl`, a `ManagedServiceFactory` keyed by the `siteKey` property: Felix
  FileInstall delivers each `.cfg` to `updated()` into a live per-site map; `save()`/`delete()`
  write/remove that file atomically. File-backed, so settings survive restarts and redeploys (the
  earlier programmatic `ConfigurationAdmin.createFactoryConfiguration` instances were lost on
  restart). The logo is a plain DAM **path string**, not a JCR weakreference. Exposed/edited via the
  `forge { settings }` query + `forge { updateSettings }` mutation (`GqlForgeSettings` uses a
  builder); the SSR chrome reads branding in-process via
  `server.osgi.getService("org.jahia.modules.forge.settings.ForgeSettingsService")` (the
  permission-gated GraphQL field is unusable for anonymous storefront rendering). All store GraphQL
  operations are namespaced under a single `forge` field on the root Query/Mutation
  (`ForgeQueryExtension`/`ForgeMutationExtension` → `ForgeQuery`/`ForgeMutation` containers),
  rather than flat top-level fields.

## Directory map

```
src/main/java/org/jahia/modules/forge/
  actions/     Jahia Actions — CreateEntryFromJar (@Component); ForgeMediaMimeListener; MagicByteImageValidator; ActionSecurityUtils
  graphql/     GraphQL extensions (ForgeSettings, CategorySettings, ManageRoles)
  filters/     PublishedModuleFilter — render filter (@Component service=RenderFilter.class)
  proxy/       MavenProxy — artifact-download servlet (@Component)
  settings/    ForgeSettingsService + ForgeSiteDeletionListener (@Component)
  security/    securityUpdates.json feed (SecurityFeed, LiveChangeDetector) + AdvisoriesLock and its
               per-workspace ACL listeners (@Component): store developers locked out of the advisories folder
src/main/resources/   CND, views, i18n, moduleList rendering
tests/                Cypress E2E (+ env tooling) — see below
```

## Build / test

- **Build**: `mvn clean install` (Java 11). Produces the OSGi bundle JAR.
- **E2E**: `tests/` uses `@jahia/cypress`. `env.run.sh` spins up Jahia + Nexus
  (docker-compose); `ci.build.sh` copies the locally-built `jahia-store` JAR
  *and* the sibling `jahia-store-template` `.tgz` into `tests/artifacts/`;
  `assets/provisioning.yml` installs the `javascript-modules-engine` + deps.
  Run: `cd tests && npx cypress run` (`baseUrl` localhost:8080). Suite = 20 specs,
  must stay green. Credentials come from `tests/.env` via `set-env.sh` (do not
  print or commit secrets).
  - **Accessibility gate** (`20-accessibility.cy.ts`): runs axe-core (cypress-axe)
    against the jahia-store-template pages (home grid, module detail, my-modules,
    in-site admin) at the full WCAG ladder up to **AAA** + landmark/region best
    practices. This is the enforced version of what used to be a manual EqualWeb
    audit — keep it green, and treat new AAA failures as real (the empty home
    page hides card/detail issues that only surface with seeded content).

## SonarQube

- Project key (derive as `<groupId>:<artifactId>`): **`org.jahia.community:jahia-store`**.
- Scan: `mvn -B clean install sonar:sonar` — the `jahia-modules` parent pins a
  Java-11-compatible scanner, so this runs on the host's default Java 11. The
  `sonar` profile (host `~/.m2/settings.xml`) supplies the URL + token.
- The gate counts **new-code** issues; keep new Java clean (`PublishModule` /
  `CreateEntryFromJar` are the reference — small focused methods, constants over
  literals, no redundant casts).
- **Build site JCR paths from platform constants** (rule `S1075`): use
  `JahiaSitesService.SITES_JCR_PATH + FileSystem.SEPARATOR + siteKey`
  (`org.apache.jackrabbit.core.fs.FileSystem`), never a hard-coded `"/sites/"` or
  a bare `"/"` — S1075 flags both the URI literal *and* the path-delimiter literal.
  `SITES_JCR_PATH` is `"/sites"` (no trailing slash).

## Conventions & contracts

- Layered: keep Actions/GraphQL thin; business logic in services/helpers.
- Domain errors as unchecked exceptions; log server-side, return safe messages.
- **Do not break the `moduleList.json` external contract** — it is consumed by
  `jahia-store-template` (and possibly external clients).

### Test invariant — spec 25 guards the sibling module, not this one (SEC-375)

`tests/cypress/e2e/25-fragmentCacheIdentity.cy.ts` covers a defect in
**`jahia-store-template`** (GHSA-g6wp-ghxm-mx76: Jahia's fragment cache key models
permissions but not identity, so one logged-in user was served another's username
and another's module list). It lives here because the E2E harness does, so from
this repo it can look orphaned — it is not; do not drop it when pruning specs.

Two properties of that spec are load-bearing and are easy to "clean up" into
uselessness. Its two users must keep the **same** role, and nothing may flush the
render cache or vary the URL between its arms. Either change lets a fully broken
build pass. Its file header explains why — read it before editing.

### Deployment invariant — MavenProxy on a PUBLIC store (SECURITY-571 #57)

`MavenProxy` deliberately serves **raw** Maven coordinates (not only catalogued
modules) and, on a world-readable `modules-repository`, serves **anonymous**
callers while using the site's stored Nexus credentials server-side. That is the
public-app-store download use case. The authorization gate is therefore
**repository-level** (can the caller read `…/contents/modules-repository`?), not
artifact-level — an artifact-level gate was tried and **broke** the jnt:file-less
proxy download (E2E spec 14), so do **not** re-introduce one.

The residual is a *confused-deputy on a misconfigured deployment*, not an in-module
code bug. Operators MUST honour this invariant:

- **Never co-locate private binaries under the same Nexus repository root as a
  public catalogue.** Anything resolvable under `ForgeSettings.repositoryUrl` is
  reachable through the proxy with the site's credentials.
- When the catalogue is public but some artifacts must stay private, apply a
  restrictive **ACL on the `modules-repository` node** (deny guest READ) — that
  makes the repository-level gate return 403 for anonymous callers.
- Prefer a dedicated **least-privilege upstream credential** scoped to the public
  catalogue only.
- Commit each change immediately (`git commit -s`), staging only changed files.
  Feature branch: `SECURITY-571-js-module-migration`.
