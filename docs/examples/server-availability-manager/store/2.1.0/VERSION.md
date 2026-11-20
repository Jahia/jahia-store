---
artifact: https://devtools.jahia.com/nexus/content/repositories/jahia-releases/org/jahia/modules/server-availability-manager/2.1.0/server-availability-manager-2.1.0.jar
date: 2022-01-07
tag: 2_1_0
requiredVersion: 8.1.0.0
dependencies:
  - graphql-dxm-provider
---

- Added a probe for upgrade patch failures.
- Added a message node that explains the state of a probe.
- Fixed the access to /modules/healthcheck, and improved the ModuleState probe.
- Updated for the GraphQL library upgrades.
