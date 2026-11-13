<a href="https://www.jahia.com/">
    <img src="https://www.jahia.com/modules/jahiacom-templates/images/jahia-3x.png" alt="Jahia logo" title="Jahia" align="right" height="60" />
</a>

Private App store
======================

The **Private App Store** ("Forge") backend — a Jahia 8.2 Java/OSGi module
providing the JCR content model, the GraphQL admin API, the Jahia Actions for
authoring (module upload, reviews, …), background jobs, a Maven proxy, the
`moduleList.json` feed and the `securityUpdates.json` feed.

Its website and in-site administration UI are provided by the sibling
**[`jahia-store-template`](../store-template)** JavaScript module.

## Security updates feed

`/contents/modules-repository.securityUpdates.json` tells Jahia instances which updates fix security
issues. It sits next to `moduleList.json`, which it does not change.

- **Advisories** (`advisories`): the security team creates a *Security advisory* in the site's
  `contents/security-advisories` folder in jContent, then publishes it. Customers see it only once
  it is published with no pending change. The feed reads advisories from the edit workspace, so a
  change made directly in live does not reach customers.
  - `advisoryId`: `JSA-YYYY-NNNN`, a CVE or a GHSA identifier.
  - `severity`: `low`, `medium`, `high` or `critical`.
  - `fixedIn`: one value per fixed component and maintained line, written
    `[groupId:]component@version`. The component is a Store module name, or `jahia-core` for the
    platform. Examples: `graphql-dxm-provider@3.10.0`, `org.jahia.modules:graphql-dxm-provider@2.24.0`,
    `jahia-core@8.2.3.7`.
  - Optional: `cvss`, `cves`, `advisoryUrl`, `publishedDate`, `summary`.
- **Flagged versions** (`flaggedVersions`): a module owner can mark a version as containing
  security fixes (`securityFix`, with an optional `securitySeverity`, `securityReferences` and
  `securityNote`). Published versions of published modules only, as in `moduleList.json`.

```json
{"schemaVersion": 1,
 "advisories": [{"id": "JSA-2026-0011", "title": "...", "severity": "high", "cvss": 7.5,
                 "cves": ["CVE-2026-1234"], "url": "https://...", "published": "2026-11-03",
                 "summary": "...",
                 "fixes": [{"component": "graphql-dxm-provider", "groupId": "org.jahia.modules",
                            "fixedVersions": ["3.10.0", "2.24.0"]},
                           {"component": "jahia-core", "fixedVersions": ["8.2.3.7"]}]}],
 "flaggedVersions": [{"groupId": "org.jahia.community", "name": "my-module", "version": "1.4.2",
                      "severity": "medium", "references": ["GHSA-..."], "note": "..."}]}
```

The view is cached for 10 minutes. When the repository cannot be read, it answers `503` rather than
an empty feed, which would tell every client that nothing needs fixing.

## Build & test

```bash
mvn clean install                 # build the OSGi bundle (Java 11)
cd tests && npx cypress run        # end-to-end suite (Jahia on localhost:8080)
```

The `tests/` harness (`@jahia/cypress`) provisions Jahia + Nexus, installs the
`javascript-modules-engine`, this module's JAR, and the `jahia-store-template` `.tgz`.

## Documentation

- **[AGENTS.md](./AGENTS.md)** — component model (OSGi DS vs blueprint), the
  Actions-vs-GraphQL permission wall, the JCR content model, build/test, and
  SonarQube setup. **Start here before modifying this module.**

## Open-Source

This is an Open-Source module, you can find more details about Open-Source @ Jahia [in this repository](https://github.com/Jahia/open-source).