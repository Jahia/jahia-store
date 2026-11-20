---
artifact: https://devtools.jahia.com/nexus/content/repositories/jahia-releases/org/jahia/modules/server-availability-manager/3.7.0/server-availability-manager-3.7.0.jar
date: 2026-11-16
tag: 3_7_0
changelog: https://github.com/Jahia/server-availability-manager/compare/3_6_0...3_7_0
disabled: true
requiredVersion: 8.2.0.0
dependencies:
  - graphql-dxm-provider
---

### Improvements

- Added a health check probe that reports a module whose internal components failed to start. A module could previously be reported as started while a component inside it never ran, with no probe covering that state. by @jkevan in https://github.com/Jahia/server-availability-manager/pull/265

### Bug fixes

- Fixed a configuration change so it updates the probes registry and the health check servlet in place instead of restarting them. During the restart the health check could report no probes and the `/healthcheck` endpoint could answer 404. by @baptistegrimaud in https://github.com/Jahia/server-availability-manager/pull/263
- Fixed the JahiaErrors health check probe so it counts a log line only by its level, instead of flagging any line that merely contains the word ERROR (such as the startup lines of the request-safety-patch module). by @x0h01 in https://github.com/Jahia/server-availability-manager/pull/270
- Fixed the health check response when a probe message contains a non-ASCII character: the response is no longer cut short and the character is preserved. by @jkevan in https://github.com/Jahia/server-availability-manager/pull/266

### Misc

- Added maven.jahia.com to the Maven repositories used to download the module's dependencies. by @rknj in https://github.com/Jahia/server-availability-manager/pull/271
