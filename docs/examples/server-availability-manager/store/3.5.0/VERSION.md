---
artifact: https://devtools.jahia.com/nexus/content/repositories/jahia-releases/org/jahia/modules/server-availability-manager/3.5.0/server-availability-manager-3.5.0.jar
date: 2026-05-05
requiredVersion: 8.2.0.0
dependencies:
  - graphql-dxm-provider
---

### Improvements

* The SupportedStackJVMProbe that got disabled in previous release was updated to better handle current JS Modules requirement and was re-enabled by @baptistegrimaud in https://github.com/Jahia/server-availability-manager/pull/220

### Bug fixes

* Fixed the Health check endpoint authorization to work reliably under all security profiles by @baptistegrimaud in https://github.com/Jahia/server-availability-manager/pull/238

### Misc

* test: bump @jahia/cypress from 4.0.0 to 4.5.0 by @Fgerthoffert in https://github.com/Jahia/server-availability-manager/pull/228

**Full Changelog**: https://github.com/Jahia/server-availability-manager/compare/3_4_0...3_5_0
