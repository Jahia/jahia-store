---
artifact: https://devtools.jahia.com/nexus/content/repositories/jahia-releases/org/jahia/modules/server-availability-manager/3.2.0/server-availability-manager-3.2.0.jar
date: 2024-08-01
requiredVersion: 8.2.0.0
dependencies:
  - graphql-dxm-provider
---

- New probes: ModulesSpringUsage, MultipleBundleVersions, MultipleModuleVersions, RenderingChain, SnapshotInProduction, SupportedStackJSModules, SupportedStackJVM.
- Probe filtering by health status, and an `includes` parameter.
- The JahiaErrors probe priority is lowered to DEBUG.
- A database connectivity check in the file datastore probe, and node cache load metrics.
- The overall status message is deprecated.
