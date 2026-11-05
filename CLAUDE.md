# CLAUDE.md — jahia-store

> **Read [AGENTS.md](./AGENTS.md) first.** It covers the component model, the
> Actions-vs-GraphQL permission wall, the JCR content model, the build/test loop,
> and the SonarQube setup. This file lists only the highest-risk gotchas.

## Must-knows before editing

- **`_dsannotations` in `pom.xml` must list EVERY package holding a `@Component`:**
  `…graphql.*`, `…actions.*`, `…proxy.*`, `…settings.*`, **and `…filters.*`**.
  It is an explicit allow-list (it replaces bnd's default `*`), so any package left
  out is silently NOT scanned and its `@Component` never registers — this is exactly
  how `PublishedModuleFilter` was left unregistered (draft modules anonymously
  readable, SECURITY-571 #54). After any pom change, verify the generated
  `OSGI-INF/*.xml` descriptor exists for each component (including
  `…filters.PublishedModuleFilter.xml`).
- **Write features with a Java side = Jahia Action, not GraphQL.** `/modules/graphql`
  is permission-gated (ordinary users denied). See `actions/CreateEntryFromJar.java`
  (module-JAR upload: Maven deploy + node creation, runs in the posting workspace).
  There is only one Action class (`CreateEntryFromJar`); publishing is done through
  the GraphQL/JCR publication path and the storefront UI, not a dedicated action.
- **Action `.do` POSTs need CSRF via XMLHttpRequest** (CSRFGuard patches XHR, not
  `fetch`, not plain `<form>` posts). The `jahia-store-template` client already does this.
  `/modules/graphql` is not CSRF-gated.
- **Java source level < 16**: no `instanceof` pattern matching, no records.
- **Don't break the `moduleList.json` contract** consumed by `jahia-store-template`.

## SonarQube

- Project key: `org.jahia.community:jahia-store`.
- Scan: `mvn -B clean install sonar:sonar` (Java 11; parent pins the scanner;
  `sonar` profile in `~/.m2/settings.xml` provides URL + token).
- Build site JCR paths from platform constants — `JahiaSitesService.SITES_JCR_PATH
  + FileSystem.SEPARATOR` (jackrabbit) — never hard-code `"/sites/"` or `"/"`
  (rule `S1075` flags both the URI and the path-delimiter).

## Standing repo rules

- Commit each change immediately (`git commit -s`), staging only the files you
  changed. Feature branch: `SECURITY-571-js-module-migration`.
- Never print or commit secrets (the SonarQube token, `tests/.env` credentials).
