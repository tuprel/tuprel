[Documentation](../README.md) / Help

# FAQ

## Is Tuprel a stable production dependency?

There is no stable release or compatibility guarantee yet. Evaluate from a
source checkout and review the subset before adoption.

## Does it require JPA, Hibernate or Spring?

No. The runtime uses JDBC and an application-owned DataSource. Framework
integration is future work in separate modules.

## Does generation create the database?

No. Validation, formatting, generation and draft creation are offline.
Tables/defaults/constraints currently require explicit reviewed SQL.

## Can I deploy migrations?

No. Main offers offline drafts, catalog validation and read-only PostgreSQL
history/status. Execution, diff and dev remain unavailable. Design documents and open
PRs do not represent commands in the installed main launcher.

## Does a migration plan or digest approve execution?

No. The [offline plan API](../guides/migrations.md#compile-an-offline-plan) reports
risks and binds artifacts to observed context. It does not prove human review,
physical drift, snapshot provenance or live-server identity. No executor or
deploy command is available.

## Can I try it without Docker or PostgreSQL?

Yes: [CLI quickstart](../getting-started/quickstart.md). Real queries can use
the [local PostgreSQL playground](../../examples/tuprel-playground/README.md)
without Docker. CI uses isolated real PostgreSQL tests.

## Does it read DATABASE_URL from the schema?

No. Validation checks the declaration without resolving it. The application
supplies a `DataSource`; the example/test .env loader is not a production API.
Migration status separately requires explicit `TUPREL_DATABASE_*` environment
variables and a target schema; it never loads that .env automatically.

## Does reading a relation issue a query?

No. Request includes while building a query. Unloaded access fails instead of
contacting the database. Includes themselves use explicit batch statements.

## Where are the Maven artifacts and build plugins?

They are not published. Use the source composite build demonstrated by the
playground; its provisional coordinates are not Maven Central dependencies.

## Which platforms and databases are tested?

Java 21 and PostgreSQL are the baseline. Windows/PowerShell is exercised locally,
Linux/Bash in CI. macOS validation is pending. Other dialects and H2 compatibility
are not part of the supported subset.

## Where can I ask for help?

[Issues](https://github.com/tuprel/tuprel/issues) for questions/bugs;
[Contributing](../../CONTRIBUTING.md) for changes;
[Security](../../SECURITY.md) for private reports. No separate community service
or support SLA is promised.
