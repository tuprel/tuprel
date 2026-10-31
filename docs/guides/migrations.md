[Documentation](../README.md) / Guides

# Migrations

**Available now:** initialise a format-v1 catalog, create comment-only drafts,
validate files through the library and compute exact SHA-256 checksums. The
read-only `migrate status` command compares those files with PostgreSQL history.

**Not available:** deploy, dev, diff, database bootstrap,
deployment locks, drift detection or reset. Open PRs are not installed CLI features.

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

[ADR-0012](../adr/ADR-0012-offline-cli-and-migration-files.md) defines the exact
format. The playground still uses
[manual bootstrap SQL](../../examples/tuprel-playground/src/main/resources/bootstrap.sql).
Draft creation neither replaces that bootstrap nor migrates existing data.
