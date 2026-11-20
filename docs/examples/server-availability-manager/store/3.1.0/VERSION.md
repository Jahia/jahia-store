---
artifact: https://devtools.jahia.com/nexus/content/repositories/jahia-releases/org/jahia/modules/server-availability-manager/3.1.0/server-availability-manager-3.1.0.jar
date: 2024-05-15
requiredVersion: 8.2.0.0
dependencies:
  - graphql-dxm-provider
---

- New wiring-issue probe, and a probe that alerts on errors in the Jahia logs.
- SAM is included by default in production and development.
- The cluster probe moves into the clustering bundle.
- The datastore probe is renamed FileDatastoreProbe and its always-green status is fixed.
