# Distribution fixtures

`Verify-StagedCli.ps1` extracts and runs a source-built CLI archive from a
temporary installation. `Verify-StagedMaven.ps1` stages the SQL, runtime and
PostgreSQL JARs in a temporary file repository. It verifies artifact names,
versions and Maven dependency scopes using the synthetic group
`dev.tuprel.staging`. Both fixtures use the existing Java 21 build.

```powershell
pwsh -File test-support/distribution/Verify-StagedMaven.ps1
```

The Maven fixture applies its publishing configuration only to the isolated
test invocation.

The external Maven check uses a fresh Maven cache and file repository. It
generates and compiles Java twice, using two fixture versions built from the
same source revision and a changed consumer schema. Run it with Maven 3.9
and Java 21 using:

```powershell
pwsh -File test-support/distribution/Verify-MavenConsumer.ps1
```

This proves version selection and transitive runtime resolution. Public
coordinates and release distribution remain gated by the naming and release
policies.
