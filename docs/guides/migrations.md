[Documentation](../README.md) / Guides

# Migrations

**Available now:** initialise a format-v1 catalog, create comment-only drafts,
validate files through the library and compute exact SHA-256 checksums. The
read-only `migrate status` command compares those files with PostgreSQL history.

Offline Java APIs also parse/preview the limited PostgreSQL DDL subset and
compile contextual risk/digest plans. They do not add preview/deploy commands
to the CLI.

**Not available:** deploy, dev, diff, database bootstrap,
deployment locks, drift detection or reset. Open PRs are not installed CLI features.

The runner foundation now shares an internal canonical history definition with
the read-only validator. Its bootstrap statement is tested transactionally in
isolated PostgreSQL schemas. It is not a public bootstrap API: the controlled
write path must first enforce the accepted session, lock and approval gates.
Status never invokes bootstrap. The internal runner session now validates the
configured live database, principal, schema access and TLS mode, rejects active
transactions and preheld advisory locks, and acquires a session lock with a
30-second interruptible deadline. It releases only its own lock; failed cleanup
discards the connection. This foundation does not execute migration SQL or
expose a public deployment API. No migration-execution command is available yet.

## Create a draft

From a project with a fresh catalog created by tuprel init:

```bash
tuprel migrate create --name initial --dry-run
tuprel migrate create --name initial
```

On Windows use tuprel.bat or your absolute launcher path. Real creation writes
`<UTC-date>_<UTC-time>_<name>/migration.sql` with SQL comments for manual editing.
No executable SQL is invented, approved or run.

Names are 1–64 lowercase ASCII characters, start with a letter, with words
separated by _ or -. add_products-v2 is valid. Spaces, uppercase, accents and
paths are rejected, not normalised. UTC IDs have second precision; collisions fail.

## Files and review

```text
tuprel/
  schema.tuprel
  migrations/
    .tuprel-migrations
    <UTC-date>_<UTC-time>_<name>/
      migration.sql
  .migrations.create.lock
```

Keep the format marker. SQL is strict UTF-8 without BOM, NUL or CR, with final
LF. Catalog validation rejects unexpected entries, links, aliases and duplicate
IDs. Limits: 1 MiB per file, 16 MiB total, 1000 migrations. Hashes cover exact bytes.

The empty coordination file is outside the catalog. Its existence does not mean
a process holds the lock. You may explicitly add /tuprel/.migrations.create.lock
to your project's .gitignore; the CLI does not edit existing configuration.
Do not remove it while creators are active.

Inspect partial files after an error before retrying. No automatic filesystem
rollback or retry exists. Treat committed migrations as reviewed artifacts;
status can detect changes to files recorded as applied. It does not enforce
immutability or repair files or history.

## Inspect existing history

From the project directory, with a valid local format-v1 catalog:

```bash
tuprel migrate status --help
tuprel migrate status --target-schema app
```

The target schema must already exist. Configure the process environment with
`TUPREL_DATABASE_URL`, `TUPREL_DATABASE_USER` and `TUPREL_DATABASE_PASSWORD`
using your environment's secret mechanism. The URL requires an explicit port
and database, for example `jdbc:postgresql://localhost:5432/tuprel_devtest` for
the dedicated local test database. Credentials belong in the separate variables,
never in the URL, command arguments, shell history or Git.

Status does **not** load a .env file, the playground configuration or the
datasource declaration in schema.tuprel. Only `sslmode` is accepted as a URL
option: remote connections require `verify-full`; loopback defaults to `disable`.
See the [CLI reference](../reference/cli.md#migration-status) for configuration,
timeouts and exit codes.

The command reads `tuprel/migrations` and the explicit schema's existing
`_tuprel_migrations` table in one `REPEATABLE READ, READ ONLY` transaction.
It checks the known v1 table shape, constraints and stored values. Unknown
structures fail rather than being interpreted or repaired. Use a role with
USAGE on the target schema, SELECT on history and standard pg_catalog read access.

| Report | Meaning |
|---|---|
| UNINITIALISED | No history table; local files are pending |
| APPLIED | The migration ID appears in confirmed history |
| PENDING | The migration ID exists only in the local catalog |
| MODIFIED | Exact file checksum differs from the recorded checksum |
| REMOVED | An applied ID is absent locally |
| RENAMED | The name changed for the same applied ID |
| OUT_OF_ORDER | Applied IDs are not a chronological prefix, or recorded executions overlap |

Flags can accumulate. APPLIED alone does not prove matching bytes: also inspect
divergences. Pending work and an uninitialised history are normal outcomes;
integrity/order problems exit 1. Configuration, permission, incompatible-history
and JDBC failures exit 2 without confirming a snapshot.

Status always reports `DRIFT_NOT_CHECKED`: file/history integrity does not prove
that physical database objects match the expected schema. It never creates a
schema, table, draft or history row, applies SQL, takes a deployment lock, or
repairs discrepancies. Avoid concurrent local catalog edits while inspecting
status; the filesystem and database are not one atomic snapshot. Coordinate
administrative DDL separately. See [Troubleshooting](../help/troubleshooting.md#migration-status).

## Preview the supported DDL subset in Java

The `tuprel-migrate-postgresql` library includes `PostgresqlDdlParser`, an offline
parser for a deliberately small whole-script grammar. It accepts qualified,
quoted identifiers and these operations:

- CREATE TABLE with built-in types and optional inline primary key / NOT NULL.
- ALTER TABLE ADD COLUMN with a nullable built-in type and no default.
- Ordinary CREATE INDEX on a nonempty list of plain columns.

Supported types are UUID, BOOLEAN, SMALLINT, INTEGER, BIGINT, TEXT, DATE and
TIMESTAMPTZ (also TIMESTAMP WITH TIME ZONE). The parser reuses format-v1
MigrationFile snapshots and the portable ASCII identifier contract. Defaults,
foreign keys, expressions, DML, explicit transaction control, concurrent indexes
and other SQL are rejected. It is not a general PostgreSQL parser.

```java
import dev.tuprel.migrate.MigrationDirectory;
import dev.tuprel.migrate.MigrationFile;
import dev.tuprel.migrate.postgresql.ddl.DdlParseResult;
import dev.tuprel.migrate.postgresql.ddl.PostgresqlDdlParser;
import java.nio.file.Path;
import java.util.List;

List<MigrationFile> files = MigrationDirectory.discover(Path.of("tuprel/migrations"));
DdlParseResult result = new PostgresqlDdlParser().parse(files.getFirst());
if (result.isSupported()) {
    String sqlPreview = result.preview();
    // Present this text for review; nothing is executed.
} else {
    DdlParseResult.Diagnostic diagnostic = result.diagnostic().orElseThrow();
    // Report the category and location; the diagnostic contains no source SQL.
}
```

This fragment requires a nonempty validated catalog; a comment-only create
draft produces EMPTY_SCRIPT. A failed script returns no partial AST or SQL
preview. Preview normalises supported syntax to LF and explicitly qualified
pg_catalog types; it never rewrites the original migration bytes or checksum.
Parsing consumes the complete input, including comments and terminators, with
bounded statements, tokens and comment nesting.

Syntax recognition is not a safety verdict, execution approval or physical
drift check. Even an ordinary index can block writes. Review the exact subset
and limits in [ADR-0015](../adr/ADR-0015-postgresql-ddl-subset.md) and the
[Java API reference](../reference/java-api.md#offline-ddl-preview).

## Compile an offline plan

`PostgresqlMigrationPlanCompiler` combines a complete validated catalog, a
configured target and an observed history snapshot. It analyses pending files,
preserves migration/statement order, and binds original file checksums and
canonical preview separately. Applied SQL is not reparsed or reexecuted.

In this fragment, `environment` is a caller-supplied `Map<String, String>` with
the three explicit TUPREL_DATABASE_* settings. `observedHistory` is a confirmed
MigrationHistory snapshot from the read-only reader above. Neither is inferred
from .env. The compiler itself does not connect or attest snapshot provenance:

```java
import dev.tuprel.migrate.MigrationDirectory;
import dev.tuprel.migrate.MigrationFile;
import dev.tuprel.migrate.postgresql.MigrationDatabaseSettings;
import dev.tuprel.migrate.postgresql.MigrationPlan;
import dev.tuprel.migrate.postgresql.MigrationPlanDigest;
import dev.tuprel.migrate.postgresql.MigrationPlanTarget;
import dev.tuprel.migrate.postgresql.PostgresqlMigrationPlanCompiler;
import java.nio.file.Path;
import java.util.List;

MigrationPlanTarget target = MigrationDatabaseSettings.fromEnvironment(environment)
        .planTarget("app");
List<MigrationFile> files = MigrationDirectory.discover(Path.of("tuprel/migrations"));
MigrationPlan plan = new PostgresqlMigrationPlanCompiler()
        .compile(target, files, observedHistory);
if (plan.state() == MigrationPlan.State.REVIEW_REQUIRED) {
    String sqlPreview = plan.preview();
    MigrationPlanDigest digest = plan.digest();
    // Present the preview, all risks and this digest together for review.
}
```

Plans report NO_CHANGES, REVIEW_REQUIRED or BLOCKED, never SAFE. Unknown or
destructive SQL, empty drafts, integrity/order divergence, target mismatches,
reserved engine objects, known plan conflicts and resource limits block the
whole plan. `preview()` refuses a blocked plan. Known syntax still reports
DDL locks; ordinary indexes additionally report write blocking and build cost.
Physical drift is always unverified, including NO_CHANGES.

The versioned SHA-256 digest binds the configured endpoint/schema/principal/TLS,
history presence and every recorded field, file identities/names/checksums,
grammar/preview/policy versions, risks and canonical preview. Passwords are
excluded. Reordering equivalent catalog/history snapshots preserves the digest;
changing bound bytes or context invalidates it. The digest is not a signature,
proof of human review, live-server attestation or one-use execution token.

No plan method applies SQL. A future runner must independently observe live
state and recompile under the approved session-lock and transaction contract;
caller-supplied snapshots are not execution authority. See
[ADR-0016](../adr/ADR-0016-offline-migration-plan-digest.md) for the exact encoding
and limits, and [ADR-0017](../adr/ADR-0017-transactional-runner-gates.md) for the
accepted future execution gates. Deploy, dev, diff and physical drift remain
unavailable.

[ADR-0012](../adr/ADR-0012-offline-cli-and-migration-files.md) defines the exact
format. The playground still uses
[manual bootstrap SQL](../../examples/tuprel-playground/src/main/resources/bootstrap.sql).
Draft creation neither replaces that bootstrap nor migrates existing data.
