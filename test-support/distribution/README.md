# Distribution fixtures

The Gradle plugin LTS fixture resolves the published plugin marker without
composite builds or TestKit classpath injection. It stages one plugin build
and verifies Gradle 9.7.1, 9.8.2 and 9.9.1 against Java 17, 21 and 25. See the
[plugin guide](../../tuprel-gradle-plugin/README.md#lts-certification-fixture)
for reproduction commands, Java selection and the isolated Linux single-JDK
proof. Its Gradle ZIP hashes come from the official distribution checksum
endpoints. Staging publication remains local and temporary.

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

The Linux CI job also runs the fixture with an absolute GnuPG executable.

```powershell
pwsh -File test-support/distribution/Verify-StagedMaven.ps1 -Gpg /usr/bin/gpg
```

This mode stages the JARs, sources, Javadoc, POMs and Gradle module metadata.
It checks SHA-256, produces detached OpenPGP signatures with an ephemeral
key, verifies them with a separate public-only keyring, and rejects changed
bytes and substituted signatures. This verifies the release envelope locally. Central Portal namespace
verification, signing identity, authentication and upload remain separate.

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

Pass `-ReplayJdk25 <absolute JDK 25 home>` to run the pinned Java 21 binary
on Java 25 against the new runtime without recompiling it.

### D1-A Maven and Java LTS

Use a verified Maven 3.9.16 installation and explicit homes for all three JDKs.
The proof builds with JDK 21, stages the libraries once, and installs the CLI
ZIP outside the checkout. Each Maven/JVM pair runs with its own empty cache,
compiles with release 17, and verifies the resolved library hashes. The script
prints the JDK, artifact hashes, generated file count and binary replay results.

```powershell
pwsh -File test-support/distribution/Verify-MavenLts.ps1 `
  -Maven $maven -Jdk17 $jdk17 -Jdk21 $jdk21 -Jdk25 $jdk25 -Postgresql
```

The optional PostgreSQL proof reuses the staged sales example against fresh
schemas in a loopback PostgreSQL 17 `tuprel_devtest` database. It reads the
existing ignored playground `.env` or test environment variables, sends
credentials through the child's standard input and removes its temporary
schemas. Specify an absolute `-Gpg` path to include ephemeral signing.
The Linux Staged CLI workflow runs both options using disposable fixtures.
The matrix certifies the Maven fixture. The product's public minimum remains
Java 21 pending the separate LTS activation.

### D1-C Spring Boot and Java LTS

`Verify-SpringLts.ps1 -Maven $maven -Jdk17 $jdk17 -Jdk21 $jdk21 -Jdk25 $jdk25`
stages the [products API](../../examples/spring-products-api/README.md) outside
the checkout. It builds the starter and libraries once with JDK 21, resolves
them through Maven 3.9.16 in each JVM, generates release-17 sources and tests
HTTP, PostgreSQL, DataSource ownership and transaction boundaries. The packaged
application runs after Maven exits; the Java 17 JAR is replayed unchanged.

Linux CI adds `-Java17Container` with a pinned unprivileged Java 17-only
container, offline Maven and host networking solely to reach the loopback test
database. No host JDK or checkout is mounted. Windows CI initializes its own
temporary PostgreSQL cluster using the runner's preinstalled version 17.
`SpringPostgresql-CI.ps1` refuses execution outside disposable Windows Actions
runners and never starts the developer's installed PostgreSQL service.

`Build-SpringCodeql.ps1` stages the dependencies but compiles the repository's
REST sources with Maven `test-compile`, allowing CodeQL to map findings to
the PR. It is an analysis build, not the external consumer certification.

These are two source revisions with fixture versions. The test exercises
version selection, the generated API used by the consumer and transitive
runtime resolution. A public release requires verification of the candidate
namespace, authenticated publication and signed artifacts under the naming
and release policies.

The candidate group for public coordinates is dev.tuprel, subject to domain
ownership verification. The proposed artifact IDs are tuprel-sql,
tuprel-runtime and tuprel-postgresql. The fixture group remains
dev.tuprel.staging.
