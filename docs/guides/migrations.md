[Documentation](../README.md) / Guides

# Migrations

**Available now:** initialise a format-v1 catalog, create comment-only drafts,
validate files through the library and compute exact SHA-256 checksums. The
read-only `migrate status` command compares those files with PostgreSQL history.

Offline Java APIs also parse/preview the limited PostgreSQL DDL subset and
compile contextual risk/digest plans. `migrate deploy --dry-run` inspects the
same limited subset; applying it requires an explicitly reviewed digest.

The Java API also compares fully known logical schemas reconstructed from the
accepted DDL AST. See [offline comparison](#compare-logical-schemas-offline).

**Not available as CLI commands:** dev, diff or reset.
Physical drift detection remains unavailable. Open PRs are not installed CLI features.

## Compare logical schemas offline

`SchemaSnapshot.fromParsed(SqlIdentifier, List<DdlParseResult>)` reconstructs a
complete logical state from an empty schema, in statement order. Every script
must parse completely. Unknown SQL and comment-only drafts refuse the entire
state; missing tables/columns, collisions and reserved relations are rejected.
An empty list explicitly means an empty logical schema, not an empty database.

`SchemaDiff.compare(before, after)` compares tables, columns, built-in types,
nullability, inline primary keys and explicit plain indexes by exact names.
It reports removals/additions separately and never infers renames. Declaration
order of tables/columns is ignored; index key order is significant. Snapshots
are bounded, immutable logical representations, not PostgreSQL catalog reads.

Only additions expressible in the existing grammar receive canonical preview:
tables, nullable columns without defaults/PKs, and plain indexes. Required new
columns on existing tables, removals and property changes block the **whole**
preview. Type changes remain potentially destructive even for numeric widening.
Lock warnings distinguish ADD COLUMN's ACCESS EXCLUSIVE from normal index
construction's write blocking/cost. Unsupported strategies have unmodeled lock impact.

NO_CHANGES means logical equality. REVIEW_REQUIRED means a complete proposal
can be shown, not permission to execute. BLOCKED refuses preview(). Every result
retains PHYSICAL_STATE_NOT_CHECKED; defaults, foreign keys, physical column
ordinals, implicit index names and other catalog properties are not modeled.
Do not use this API to discard unknown properties when constructing a state.
The comparison creates no files, approval digest or database connection.

Run the [complete Java example](../../examples/migration-diff/README.md) without
a database. The [F6-004A contract](../adr/ADR-0018-logical-schema-diff.md)
records the limits and next slices. schema.tuprel projection, CLI diff, physical
drift and migrate dev remain separate work; the existing reviewed-file deploy
protocol is unchanged.

The Java `PostgresqlMigrationRunner` can inspect and apply reviewed migration
files transactionally. It rediscovers files and observes
history on the owned connection under the session lock, recompiles the whole
plan and compares the approved digest before bootstrap or migration DDL.
Every migration has its own transaction. A failure stops the sequence and
preserves earlier confirmed migrations; there is no global rollback promise.
Status never invokes bootstrap. The owned session validates the
configured live database, principal, schema access and TLS mode, rejects active
transactions and preheld advisory locks, and acquires a session lock with a
30-second interruptible deadline. It releases only its own lock; failed cleanup
discards the connection. The write path executes only canonical parsed SQL and
commits DDL with its APPLIED row in the same transaction. Known statement
failures roll back; commit/connection/rollback uncertainty discards the session
and reports OUTCOME_UNKNOWN, with no automatic retry.

Existing tables must be persistent heap tables without inheritance, partitions,
RLS, rules or custom triggers. Relation locks precede validation, with OID checks
after acquisition. Index columns require admitted built-in types, their default
collation and a known pg_catalog btree default operator class; explicit alternate
collations are conservatively rejected. Active event triggers and extra history
indexes block execution. These gates are not full drift detection or protection
against privileged concurrent DDL; controlled ownership/ACLs and an operational
window remain required.

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

No plan method applies SQL. The runner independently observes live
state and recompiles under the approved session-lock and transaction contract;
caller-supplied snapshots are not execution authority. See
[ADR-0016](../adr/ADR-0016-offline-migration-plan-digest.md) for the exact encoding
and limits, and [ADR-0017](../adr/ADR-0017-transactional-runner-gates.md) for the
accepted execution gates. Dev, diff and physical drift remain
unavailable.

## Deploy reviewed files from the CLI

Build the [packaged launcher](../getting-started/installation.md) and supply the
three TUPREL_DATABASE_* process settings through your secret mechanism. The
CLI never loads .env or TUPREL_TEST_* automatically. The explicit target schema
must already exist. Use isolated schemas in the dedicated test database during
development; these commands do not create databases/schemas or adopt playground data.

Create a draft, then manually edit its migration.sql using the supported subset.
Qualify every table with the exact target schema. `deploy` reads existing files;
it never derives changes from schema.tuprel or silently approves generated SQL.

PowerShell, from the consumer project with the packaged launcher on PATH:

```powershell
tuprel.bat migrate deploy --target-schema app --dry-run
# Review the complete canonical SQL, every risk and the current target/history.
$reviewedDigest = Read-Host 'Paste the digest only after reviewing the plan'
tuprel.bat migrate deploy --target-schema app --approve-digest $reviewedDigest
$LASTEXITCODE
```

The prompt above belongs to the shell example; the CLI itself never reads stdin.
In a pipeline, supply the reviewed digest as an explicit argument from your
approval process. Do not automatically extract-and-apply a fresh digest.

Bash, after reviewing the dry-run output:

```bash
tuprel migrate deploy --target-schema app --dry-run
read -r -p 'Paste the digest only after reviewing the plan: ' reviewed_digest
tuprel migrate deploy --target-schema app --approve-digest "$reviewed_digest"
```

`--dry-run` takes the accepted session advisory lock for a consistent fresh
inspection, but never bootstraps history, applies DDL or writes files. It can
wait for another executor; status remains the non-deployment-lock observer.
The dry-run is not a reservation: actual deployment reacquires the lock,
rediscovers files and compares the newly compiled digest before any DDL.
`--dry-run` and `--approve-digest` are exclusive. Invalid, stale or blocked
approval exits 2; no --yes or unsafe escape hatch bypasses the contract.

Successful deployment exits 0. Failed/refused/uncertain deployment exits 2 and
retains the confirmed prefix in its output. After OUTCOME_UNKNOWN, use status
and a new dry-run to inspect history/artifacts under a fresh session lock, then
review the current plan before another explicit invocation. Do not assume
rollback or repeat earlier confirmed migrations. See the
[CLI reference](../reference/cli.md#migration-deployment) for diagnostics and
the [uncertainty contract](#partial-failures-and-uncertain-outcomes) below.

## Run reviewed migrations in Java

`PostgresqlMigrationRunner.inspect` opens a fresh dedicated session, acquires
the schema-scoped session lock and reads files/history consistently. It never
bootstraps or applies SQL. Review its complete plan, canonical preview, risks
and digest. A BLOCKED plan has no executable preview. Physical drift remains
unverified, including when there are no pending files.

Pass an independently reviewed digest to `deploy`; do not automatically approve
the digest returned by a fresh inspection. The runner reacquires the lock and
revalidates the complete approval against current artifacts/history before
writing. This compilable example exposes those as two separate application
operations, using caller-supplied production environment configuration:

```java
import dev.tuprel.migrate.MigrationHistoryException;
import dev.tuprel.migrate.postgresql.MigrationDatabaseSettings;
import dev.tuprel.migrate.postgresql.MigrationExecutionResult;
import dev.tuprel.migrate.postgresql.MigrationInspection;
import dev.tuprel.migrate.postgresql.MigrationInspectionException;
import dev.tuprel.migrate.postgresql.MigrationPlanDigest;
import dev.tuprel.migrate.postgresql.PostgresqlMigrationRunner;
import java.nio.file.Path;
import java.util.Map;

public final class ReviewedMigrations {
    private ReviewedMigrations() { }

    public static MigrationInspection inspect(Map<String, String> environment,
            String targetSchema, Path directory)
            throws MigrationHistoryException, MigrationInspectionException {
        MigrationDatabaseSettings settings = MigrationDatabaseSettings.fromEnvironment(environment);
        PostgresqlMigrationRunner runner = new PostgresqlMigrationRunner(settings.dataSource(), settings);
        return runner.inspect(targetSchema, directory);
    }

    public static MigrationExecutionResult applyReviewed(Map<String, String> environment,
            String targetSchema, Path directory, MigrationPlanDigest approvedDigest)
            throws MigrationHistoryException {
        MigrationDatabaseSettings settings = MigrationDatabaseSettings.fromEnvironment(environment);
        PostgresqlMigrationRunner runner = new PostgresqlMigrationRunner(settings.dataSource(), settings);
        return runner.deploy(targetSchema, directory, approvedDigest);
    }
}
```

Use the three explicit TUPREL_DATABASE_* settings described above, not the test
dotenv loader. Custom DataSources must use the same TLS/certificate policy and
finite timeouts as the expected settings, with physical abort/discard support.
Live SQL checks observe encryption usage; they cannot attest client certificate
verification policy. The settings factory configures the validated JDBC policy.

### Partial failures and uncertain outcomes

`MigrationExecutionResult.confirmed()` contains only newly confirmed migrations
from that invocation. APPLIED and NO_CHANGES are successful outcomes. REFUSED
means a safety/configuration gate stopped further work; FAILED includes a known
statement failure or cleanup error. An unsuccessful invocation can still have
a confirmed prefix. Never repeat that prefix.

OUTCOME_UNKNOWN means the runner could not establish commit/connection/rollback
outcome. It discards the connection and stops, without retries or later migrations.
Run `inspect` in a new invocation/session to reconcile the live history under
the same lock. A committed APPLIED row records the atomic DDL/history transaction;
absence must be assessed against the expected prefix and known history. A
blocked/incompatible snapshot requires investigation, not automatic repair.
This inspection does not rewrite the earlier result or prove full physical drift.

Review a new plan before another explicit deployment. A confirmed prefix or
committed bootstrap changes the digest; confirmed rollback without changes can
produce the same digest. The digest is not a one-use token or proof of renewed
human review. There is no durable attempt log or automatic crash recovery in
this subset. After process loss, inspect status/metadata and the original
artifacts explicitly; do not infer rollback from a lost acknowledgement.

Locks and failures are bounded by the accepted server/driver timeouts: 30-second
session-lock polling, 10-second DDL lock timeout, 20-second statement timeout and
30-second driver socket timeout. These are separate limits, not a promise of a
single wall-clock deadline for an entire deployment. SQL and driver messages are
excluded from normal diagnostics; preview is an explicit inspection operation.

[ADR-0012](../adr/ADR-0012-offline-cli-and-migration-files.md) defines the exact
format. The playground still uses
[manual bootstrap SQL](../../examples/tuprel-playground/src/main/resources/bootstrap.sql).
Draft creation neither replaces that bootstrap nor migrates existing data.
