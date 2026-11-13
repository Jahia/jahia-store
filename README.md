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

- **Advisories** (`advisories`): a store or site administrator creates a *Security advisory* in the
  site's `contents/security-advisories` folder in jContent, checks its **Published in the security
  feed** box, and publishes the advisory in Jahia.
  - The feed reads advisories from the live workspace. It lists an advisory only when it is published
    in Jahia **and** its box is checked in the published version. An edit changes nothing until you
    publish the advisory again. To withdraw an advisory, unpublish it, or uncheck the box and publish.
  - Publishing needs Jahia's `publish` permission. Since 5.2.0 the `store-administrator` role includes
    it, so store administrators publish advisories themselves. **This widens the role: a store
    administrator can now publish any content of the store site that they can edit.**
  - Store developers cannot create, change or delete anything in the advisories folder, in the edit
    or the live workspace. The module puts a DENY of the `store-developer` role on the folder for each
    user and group that has this role on the site. Store developers keep their write access in the rest
    of the site. Store and site administrators keep their rights in the folder.
  - The module updates this lock when it starts, when the store's role administration grants or revokes
    `store-developer`, when the ACL of the site changes by other means, and when the folder is created.
  - An advisory written directly in the live workspace instead of being published from edit is
    ignored, and the module logs a warning. Jahia marks such content (`jmix:liveProperties`,
    `j:originWS=live`) and keeps the mark when the advisory is published again. To list it again,
    delete the advisory, publish the deletion, and create and publish a new one.
  - `advisoryId`: `JSA-YYYY-NNNN`, a CVE or a GHSA identifier.
  - `cves`: the vulnerabilities the advisory covers, as CVE identifiers or Jahia vulnerability identifiers
    `JSA-YYYY-NNNN.NNNN` (for example `JSA-2026-0036.1066`). A module version's `securityReferences` accept
    the same identifiers.
  - `severity`: `low`, `medium`, `high` or `critical`.
  - `fixedIn`: one value per fixed component and maintained line, written
    `[groupId:]component@version`. The component is a Store module name, or `jahia-core` for the
    platform. Examples: `graphql-dxm-provider@3.10.0`, `org.jahia.modules:graphql-dxm-provider@2.24.0`,
    `jahia-core@8.2.3.7`.
  - `introducedIn` (optional): the first version of a component that has the problem, in the same
    format, at most one value per component. Versions below it are not affected, so clients do not
    report them. The feed adds it to that component's fix as `introducedVersion`. Each value must name
    a component listed in `fixedIn`; it may omit the groupId, but when it gives one it must be the
    groupId of that component's `fixedIn` values. Example: `graphql-dxm-provider@2.0.0`, for a flaw
    that exists from 2.0.0 and is fixed in 2.24.0 and 3.10.0: a client that runs 1.4.0 is not affected.
  - Optional: `cvss`, `cves`, `advisoryUrl`, `publishedDate`, `summary`, `introducedIn`.
- **Flagged versions** (`flaggedVersions`): a module owner can mark a version as containing
  security fixes (`securityFix`, with an optional `securitySeverity`, `securityReferences` and
  `securityNote`). Published versions of published modules only, as in `moduleList.json`. Any store
  developer can set these flags, so clients must show them as reported by the module owner, not as
  Jahia advisories.

```json
{"schemaVersion": 1,
 "advisories": [{"id": "JSA-2026-0011", "title": "...", "severity": "high", "cvss": 7.5,
                 "cves": ["CVE-2026-1234"], "url": "https://...", "published": "2026-11-03",
                 "summary": "...",
                 "fixes": [{"component": "graphql-dxm-provider", "groupId": "org.jahia.modules",
                            "fixedVersions": ["3.10.0", "2.24.0"], "introducedVersion": "2.0.0"},
                           {"component": "jahia-core", "fixedVersions": ["8.2.3.7"]}]}],
 "flaggedVersions": [{"groupId": "org.jahia.community", "name": "my-module", "version": "1.4.2",
                      "severity": "medium", "references": ["GHSA-..."], "note": "..."}]}
```

`introducedVersion` is present only when the advisory has an `introducedIn` value for that component;
without it, clients treat every version below a fixed version as affected. The field is optional and
additive: `schemaVersion` stays 1 and older clients ignore it.

The view is not cached. When the repository cannot be read, or when a published advisory is not
valid (a malformed `fixedIn` or `introducedIn` value, or an `introducedIn` value that breaks the rules
above), it answers `503` with `{"error": "security feed unavailable"}` rather than a partial or empty
feed, which would tell every client that nothing needs fixing.

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