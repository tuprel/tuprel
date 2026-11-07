# Staged Java consumer

This fixture validates a small Java 21 sales application with PostgreSQL 17.
The integration test copies the installed CLI and runtime JARs to a temporary
directory. It copies this project to a separate directory, uses the installed
launcher to validate and generate, then invokes `javac` and `java` using only
the staged JARs. The consumer has no Gradle composite dependency on Tuprel.

```powershell
.\gradlew.bat :tuprel-postgresql:integrationTest --tests '*StagedConsumerIntegrationTest' --dependency-verification strict -PtuprelTestDatabase=local
```

On Bash, use `./gradlew` in place of `.\gradlew.bat`. Follow the
[test database setup](../../docs/development/BUILD_AND_TEST.md) for local mode.
CI runs this test with an isolated PostgreSQL 17 instance. The launcher and
runtime get separate process inputs. The test supplies fixture credentials to
the runtime over a private pipe and checks its output for accidental disclosure.

The fixture exercises typed CRUD, filtering and ordering, an explicit relation,
and transaction commit and rollback. The test creates and cleans up its own
schema. It bootstraps two tables for this runtime check. The managed migration
consumer proof will replace that setup in its own test.
