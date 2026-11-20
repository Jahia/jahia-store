---
artifact: https://devtools.jahia.com/nexus/content/repositories/jahia-releases/org/jahia/modules/server-availability-manager/2.8.0/server-availability-manager-2.8.0.jar
date: 2024-07-05
requiredVersion: 8.1.5.1
dependencies:
  - graphql-dxm-provider
---

- A wiring-issue probe, a SNAPSHOT-in-production probe, and node cache load metrics.
- The health status reports only the highest status across probes, and takes an `includes` parameter.
- A database check in the file datastore probe.
- The JahiaErrors probe priority is lowered to DEBUG, and the always-green datastore status is fixed.
