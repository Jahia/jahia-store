---
artifact: https://devtools.jahia.com/nexus/content/repositories/jahia-releases/org/jahia/modules/server-availability-manager/3.6.0/server-availability-manager-3.6.0.jar
date: 2026-11-12
tag: 3_6_0
changelog: https://github.com/Jahia/server-availability-manager/compare/3_5_0...3_6_0
requiredVersion: 8.2.0.0
dependencies:
  - graphql-dxm-provider
---

### Bug fixes

- Restricted caching of the server health check response so each call is answered by the server, not from a cache. by @jayblanc in https://github.com/Jahia/server-availability-manager/pull/257
