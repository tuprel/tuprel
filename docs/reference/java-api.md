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

The adapter's internal v1 history definition is shared with its future controlled
bootstrap path. It is package-private, with no new public execution API. Existing
reader signatures and read-only semantics are unchanged; consumers must not use
reflection into internal classes as a deployment mechanism.

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

The PostgreSQL adapter also provides `PostgresqlMigrationRunner`.
It validates the live target and uses a schema-scoped
session advisory lock, with bounded, interruptible acquisition and physical
discard on cleanup failure. It requires idle connections with finite timeouts
and physical abort/discard support; transaction-pooling is unsupported. There
is also a [CLI deployment command](cli.md#migration-deployment) over this adapter.
The internal write path rediscovers files, observes history on that connection
and recompiles/revalidates the approved digest under lock. Canonical parsed DDL
and the APPLIED row commit together. Unknown outcomes discard the session with
no retries; catalog gates reject unsupported physical structures. See the
[migration guide](../guides/migrations.md) and ADR-0017 for limits.

## Transactional migration runner

`PostgresqlMigrationRunner(DataSource, MigrationDatabaseSettings)` owns a dedicated
idle connection for each operation and validates it against the explicit settings.
The source must provide finite timeouts, the same validated TLS/certificate policy
and physical abort/discard. Use settings.dataSource() for the configured driver;
live SQL checks do not attest client-side server-certificate policy. No dotenv is loaded.

- `inspect(String targetSchema, Path directory)` returns `MigrationInspection`
  with current `MigrationPlan` and `MigrationHistory` from a fresh session under
  the deployment lock. It is read-only and never bootstraps history or applies DDL.
  `MigrationInspectionException.reason()` supplies a cause-free failure category;
  no snapshot is returned after observation/cleanup failure.
- `deploy(String targetSchema, Path directory, MigrationPlanDigest approval)`
  independently rediscovers/recompiles under lock and applies only matching
  reviewed artifacts. Each migration has an atomic DDL/history transaction.
- `MigrationExecutionResult` exposes `outcome()`, safe `failure()` and the
  immutable ordered `confirmed()` prefix newly committed in that invocation.
  Earlier commits remain confirmed after later failure. OUTCOME_UNKNOWN stops
  and discards the session, without retries. FAILED/CLEANUP can retain a prefix.

Fresh `inspect` supports explicit reconciliation after uncertainty. It neither
changes the original result nor repairs/retries; review current integrity and
a new plan before another explicit call. It does not perform full physical
drift detection or persist attempt/recovery records. See the
[reviewed Java example](../guides/migrations.md#run-reviewed-migrations-in-java).

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

## Offline logical schema comparison

The adapter package `dev.tuprel.migrate.postgresql.diff` provides:

- `SchemaSnapshot.fromParsed(SqlIdentifier, List<DdlParseResult>)`: replay complete
  parser results from an empty logical schema; any unknown/failed result refuses
  the whole state. `replay(SqlIdentifier, List<DdlStatement>)` accepts recognized
  AST nodes directly. Both validate prerequisites, names, collisions and budgets.
- `schema()` and immutable `tables()` with exact-name ordered columns/indexes.
  These are logical metadata, not database observations or schema.tuprel mappings.
- `SchemaDiff.compare(before, after)`: immutable `state()`, `changes()`, `risks()`
  and all-or-nothing canonical `preview()`. Same-schema snapshots are required.
  NO_CHANGES is logical equality; REVIEW_REQUIRED still requires review;
  BLOCKED makes preview() throw. Every result retains PHYSICAL_STATE_NOT_CHECKED.

4096 statements and 16384 objects bound reconstruction; the existing plan's
statement/byte budgets bound preview. Identifiers and SQL are omitted from
incidental toString output. Explicit metadata access/preview are for deliberate
inspection. No JDBC, filesystem, new dependency or deployment approval is involved.
See [the runnable example](../../examples/migration-diff/README.md) and
[guide](../guides/migrations.md#compare-logical-schemas-offline) for semantics and limits.

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
