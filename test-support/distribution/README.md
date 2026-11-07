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

CI also runs the same check with the baseline pinned to
`2b9ef01baa96633efc630cae1d288c061a94256b` and the candidate built from
the current checkout. The check runs the previously compiled consumer against
the candidate libraries, recompiles the old generated sources, then regenerates
from the updated schema and builds again.

```powershell
git worktree add --detach ../tuprel-maven-baseline 2b9ef01baa96633efc630cae1d288c061a94256b
pwsh -File test-support/distribution/Verify-MavenConsumer.ps1 -PreviousCheckout ../tuprel-maven-baseline
```

These are two source revisions with fixture versions. The test exercises
version selection, the generated API used by the consumer and transitive
runtime resolution. A public release requires verification of the candidate
namespace, authenticated publication and signed artifacts under the naming
and release policies.

The candidate group for public coordinates is dev.tuprel, subject to domain
ownership verification. The proposed artifact IDs are tuprel-sql,
tuprel-runtime and tuprel-postgresql. The fixture group remains
dev.tuprel.staging.
