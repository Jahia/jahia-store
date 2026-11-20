The module ships with Jahia 8.2 and later. Install it, or upgrade it, through the module manager
of the administration, or through the provisioning API:

```yaml
- installOrUpgradeBundle: "mvn:org.jahia.modules/server-availability-manager/3.6.0"
  autoStart: true
```

It depends on the GraphQL core provider (`graphql-dxm-provider`), which every Jahia ships.

Once started, the health check answers on `/modules/healthcheck` and through the GraphQL query
`admin { jahia { healthCheck } }`. The
[monitoring page of the Jahia Academy](https://academy.jahia.com/documentation/jahia-cms/jahia-8.2/system-administrator/monitoring-your-jahia-servers)
describes the probes, their severity and their configuration.
