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
test invocation. The next gate is to resolve the staged libraries from a
separate Maven build and compile generated application sources.
