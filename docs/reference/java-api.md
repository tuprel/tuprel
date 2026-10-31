[Documentation](../README.md) / Reference

# Java API reference

JavaDoc is generated from the source you build. There is no hosted API site or
stable published artifact yet.

## Generate JavaDoc

From the checkout root:

```bash
./gradlew javadoc --dependency-verification strict
```

PowerShell: `.\gradlew.bat javadoc --dependency-verification strict`.
No database is needed. Open per-module HTML locally, for example
tuprel-runtime/build/docs/javadoc/index.html or
tuprel-postgresql/build/docs/javadoc/index.html. The root has no aggregate page;
these ignored generated paths are intentionally not GitHub links.

## Entry points

| API | Source |
|---|---|
| DataSource adapter | [PostgresqlDatabase](../../tuprel-postgresql/src/main/java/dev/tuprel/postgresql/PostgresqlDatabase.java) |
| Transactions/database | [TuprelDatabase](../../tuprel-runtime/src/main/java/dev/tuprel/runtime/TuprelDatabase.java) |
| Transaction settings | [TransactionOptions](../../tuprel-runtime/src/main/java/dev/tuprel/runtime/TransactionOptions.java) |
| JDBC failures | [TuprelDatabaseException](../../tuprel-runtime/src/main/java/dev/tuprel/runtime/TuprelDatabaseException.java) |
| Typed query API | [Query package](../../tuprel-runtime/src/main/java/dev/tuprel/runtime/query/) |
| SQL preview | [RenderedSql](../../tuprel-sql/src/main/java/dev/tuprel/sql/RenderedSql.java) |
| Schema frontend | [SchemaCompiler](../../tuprel-schema/src/main/java/dev/tuprel/schema/SchemaCompiler.java) |
| Java generator | [JavaCodeGenerator](../../tuprel-codegen-java/src/main/java/dev/tuprel/codegen/JavaCodeGenerator.java) |
| Offline catalog | [MigrationDirectory](../../tuprel-migrate/src/main/java/dev/tuprel/migrate/MigrationDirectory.java) |
| History snapshot and comparison | [MigrationHistory](../../tuprel-migrate/src/main/java/dev/tuprel/migrate/MigrationHistory.java) · [MigrationStatus](../../tuprel-migrate/src/main/java/dev/tuprel/migrate/MigrationStatus.java) |
| PostgreSQL history reader | [PostgresqlMigrationHistory](../../tuprel-migrate-postgresql/src/main/java/dev/tuprel/migrate/postgresql/PostgresqlMigrationHistory.java) |
| Migration environment settings | [MigrationDatabaseSettings](../../tuprel-migrate-postgresql/src/main/java/dev/tuprel/migrate/postgresql/MigrationDatabaseSettings.java) |
| Offline PostgreSQL DDL parser | [PostgresqlDdlParser](../../tuprel-migrate-postgresql/src/main/java/dev/tuprel/migrate/postgresql/ddl/PostgresqlDdlParser.java) |
| DDL AST and preview | [DdlStatement](../../tuprel-migrate-postgresql/src/main/java/dev/tuprel/migrate/postgresql/ddl/DdlStatement.java) · [DdlParseResult](../../tuprel-migrate-postgresql/src/main/java/dev/tuprel/migrate/postgresql/ddl/DdlParseResult.java) |
| Offline migration plans | [PostgresqlMigrationPlanCompiler](../../tuprel-migrate-postgresql/src/main/java/dev/tuprel/migrate/postgresql/PostgresqlMigrationPlanCompiler.java) · [MigrationPlan](../../tuprel-migrate-postgresql/src/main/java/dev/tuprel/migrate/postgresql/MigrationPlan.java) |
| Configured target and digest | [MigrationPlanTarget](../../tuprel-migrate-postgresql/src/main/java/dev/tuprel/migrate/postgresql/MigrationPlanTarget.java) · [MigrationPlanDigest](../../tuprel-migrate-postgresql/src/main/java/dev/tuprel/migrate/postgresql/MigrationPlanDigest.java) |

## Read-only migration history

`PostgresqlMigrationHistory(DataSource).read(String targetSchema)` implements
the pure [MigrationHistoryReader](../../tuprel-migrate/src/main/java/dev/tuprel/migrate/MigrationHistoryReader.java)
port. The adapter owns and closes each borrowed connection; supply an idle
auto-commit connection supporting read-only repeatable-read transactions.
Do not pass a caller's active transaction. Supply the PostgreSQL JDBC driver
at runtime; the CRUD adapter does not depend on migration tooling.

`MigrationStatus.compare(List<MigrationFile>, MigrationHistory)` compares a
validated catalog with a confirmed snapshot without I/O. Inspect `hasProblems()`
and all entry flags; `DRIFT_NOT_CHECKED` remains the physical-schema limitation.
`MigrationHistoryException.reason()` provides safe failure categories without
credential-bearing JDBC causes.

For explicit environment configuration, `MigrationDatabaseSettings.fromEnvironment(Map<String, String>)`
validates the [status variables and URL policy](cli.md#migration-status) before
connecting; `dataSource()` supplies fresh connections. No .env loader is included
in this production API. Neither reader nor comparison creates history or executes
migrations.

## Offline DDL preview

`PostgresqlDdlParser.parse(MigrationFile)` consumes an immutable validated
artifact and returns a complete `DdlParseResult`. `isSupported()` means syntax
recognition for the limited grammar, never execution permission. On failure,
`diagnostic()` exposes a category and one-based line/UTF-16 column without source
text; `statements()` is empty and `preview()` refuses to return partial SQL.

The sealed `DdlStatement` AST represents CreateTable, AddColumn and CreateIndex
only, with validated SqlIdentifier components and immutable bounded lists.
Successful preview uses canonical LF and qualified pg_catalog built-in types.
No method opens files, connects to PostgreSQL, applies DDL or proves physical
drift. See the [migration guide](../guides/migrations.md#preview-the-supported-ddl-subset-in-java)
for a compilable fragment and the exact limits in ADR-0015.

Grammar/preview version strings retain their original `-proposal` suffix as
normative protocol identifiers after contract acceptance. Renaming those bytes
would change the future approval context; it is not part of this integration.

## Offline migration plan and digest

`MigrationDatabaseSettings.planTarget(String schema)` derives a redacted
configured fingerprint without opening a connection. Passwords are excluded;
this does not identify or attest a live server.

`PostgresqlMigrationPlanCompiler.compile(MigrationPlanTarget, List<MigrationFile>, MigrationHistory)`
returns an immutable `MigrationPlan` with state, ordered script metadata, risks
and `MigrationPlanDigest`. `preview()` yields canonical SQL only for a complete,
unblocked plan; BLOCKED throws instead of exposing a supported prefix.
NO_CHANGES yields empty text and still reports PHYSICAL_DRIFT_NOT_CHECKED.

The compiler is stateless and performs no filesystem/JDBC I/O. Callers supply
validated snapshots; execution must not trust their provenance. The digest
binds original artifacts and observed context, but grants no execution capability.
See the [migration guide](../guides/migrations.md#compile-an-offline-plan) for a
fragment, limitations and the normative encoding in ADR-0016. Grammar/preview/
policy version strings are preserved exactly after acceptance.

## Generated APIs belong to your schema

`TuprelClient`, ProductClient, ProductCreate, ProductUpdate and selectors are
generated into your configured package, not provided by a published client JAR.

Compile the [playground](../../examples/tuprel-playground/README.md) to inspect
real generated sources under its build/generated/sources/tuprel/main. Do not
link to ignored files as committed GitHub documentation. Working fragments and
imports are in the [client guide](../guides/java-client.md).
