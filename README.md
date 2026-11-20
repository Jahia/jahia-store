<a href="https://www.jahia.com/">
    <img src="https://www.jahia.com/modules/jahiacom-templates/images/jahia-3x.png" alt="Jahia logo" title="Jahia" align="right" height="60" />
</a>

Private App store
======================

The **Private App Store** ("Forge") backend — a Jahia 8.2 Java/OSGi module
providing the JCR content model, the GraphQL admin API, the Jahia Actions for
authoring (module upload, reviews, …), background jobs, a Maven proxy, and the
`moduleList.json` feed.

Its website and in-site administration UI are provided by the sibling
**[`jahia-store-template`](../store-template)** JavaScript module.

## Build & test

```bash
mvn clean install                 # build the OSGi bundle (Java 11)
cd tests && npx cypress run        # end-to-end suite (Jahia on localhost:8080)
```

The `tests/` harness (`@jahia/cypress`) provisions Jahia + Nexus, installs the
`javascript-modules-engine`, this module's JAR, and the `jahia-store-template` `.tgz`.

## Manage a store at git level

A module can live in its own git repository instead of being uploaded: the store reads a
`STORE.md` and one folder per version from one branch of the repository, and serves them as a
read-only module, with its versions, its icon, its screenshots and its tabs.
[`docs/git-backed-modules.md`](docs/git-backed-modules.md) is the contract of the repository,
and [`docs/examples/server-availability-manager/`](docs/examples/server-availability-manager/)
holds the store data of one real module written to it. A version reaches the public once the
store validated it, as [`docs/validation.md`](docs/validation.md) states.

Every call below goes to `<store>/modules/graphql` with the credentials of a store developer or
of an administrator, and an `Origin` header naming the store. The storefront offers the same
actions on the "My modules" page and on the page of the module.

### Add a module

Push the store data to the repository, then register its URL. The store reads the repository
at once, refuses one that fails the contract with the reason, and requests the validation of
every version it declares. [Registration](docs/git-backed-modules.md#registration) states the
permissions and the mount point the store creates.

```bash
curl --fail --user "$STORE_USER:$STORE_PASSWORD" \
  --header "Origin: https://store.example.com" --header "Content-Type: application/json" \
  --data '{"query":"mutation { forge { registerGitModule(siteKey: \"store\", gitUrl: \"https://github.com/acme/my-module.git\") { name } } }"}' \
  "https://store.example.com/modules/graphql"
```

### Refresh an existing module

The store reads the repository once and serves that reading until a refresh: after a release
that adds a version folder, or a change of `STORE.md`, call the refresh, from the release
workflow of the repository for instance. A version the store never saw is validated on the
way. [Refresh](docs/git-backed-modules.md#refresh) states who may call it and what it answers.

```bash
curl --fail --user "$STORE_USER:$STORE_PASSWORD" \
  --header "Origin: https://store.example.com" --header "Content-Type: application/json" \
  --data '{"query":"mutation { forge { refreshGitModule(siteKey: \"store\", name: \"my-module\") } }"}' \
  "https://store.example.com/modules/graphql"
```

### Trigger the verification of a version

A verification runs every check of the store on the version again and replaces its record:
the owner of the module and an administrator may ask for it, on the page of the version or from
outside. The answer carries the status of the version and of each check, which the page of the
version shows as a chain of steps. [The entry points](docs/validation.md#the-entry-points)
state the whole API, the dashboard where an administrator answers a check that waits for a
human, and the invalidation of a version.

```bash
curl --fail --user "$STORE_USER:$STORE_PASSWORD" \
  --header "Origin: https://store.example.com" --header "Content-Type: application/json" \
  --data '{"query":"mutation { forge { requestValidation(siteKey: \"store\", moduleName: \"my-module\", version: \"1.2.0\") { status checks { key label status comments } } } }"}' \
  "https://store.example.com/modules/graphql"
```

## Documentation

- **[AGENTS.md](./AGENTS.md)** — component model (OSGi DS vs blueprint), the
  Actions-vs-GraphQL permission wall, the JCR content model, build/test, and
  SonarQube setup. **Start here before modifying this module.**
- **[docs/git-backed-modules.md](docs/git-backed-modules.md)** — the contract of a module
  read from a git repository: the files, the node mapping, the registration, the refresh, the
  downloads and the cache.
- **[docs/validation.md](docs/validation.md)** — the validation of a version before it reaches
  the public: the checks, the record, the gate, the API, the storefront and the dashboard.
- **[docs/adr/](docs/adr/README.md)** — the architecture decisions.

## Open-Source

This is an Open-Source module, you can find more details about Open-Source @ Jahia [in this repository](https://github.com/Jahia/open-source).