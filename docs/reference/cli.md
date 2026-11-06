[Documentation](../README.md) / Reference

# CLI reference

Offline logical comparison is available through migrate diff and the
[Java API](java-api.md#offline-logical-schema-comparison).

These commands are available in main. Build the local tuprel / tuprel.bat
launcher following [Installation](../getting-started/installation.md).

## Commands

```text
tuprel --help
tuprel --version
tuprel init [--dry-run]
tuprel validate [--schema <file> | schema.tuprel]
tuprel format [--check | --dry-run] [--schema <file> | schema.tuprel]
tuprel generate [--check | --dry-run] [--schema <file> | schema.tuprel]
tuprel migrate create --name <name> [--dry-run]
tuprel migrate status --target-schema <name>
tuprel migrate diff --target-schema <name> [--schema <path>]
tuprel migrate dev --name <name> --target-schema <name> --confirm-managed-history [--schema <path>]
tuprel migrate deploy --target-schema <name> --dry-run
tuprel migrate deploy --target-schema <name> --approve-digest <sha256>
```

Every implemented command accepts `--help` or `-h`. `migrate --help` describes the
group. Global help/version must be used alone. No command reads stdin or prompts.
Output is plain text without ANSI colours.

The [Java migration runner](java-api.md#transactional-migration-runner) is also
available for embedding; the CLI delegates execution and safety rules to it.

| Command | Effect |
|---|---|
| init | Exclusively creates schema/catalog; refuses an existing tuprel path |
| validate | Parses/validates without resolving environment references |
| format | Writes formatting; `--check` compares without writing |
| generate | Writes Java under ownership control; `--check` compares without writing |
| migrate create | Writes a comment-only draft; never contacts a database |
| migrate status | Reads local files and existing PostgreSQL history; no application writes |
| migrate deploy | Inspects a plan or applies existing explicitly approved migration files |
| migrate diff | Compares all local migration artifacts with the supported schema subset, offline |
| migrate dev | Previews supported additions against a verified applied prefix |

`--dry-run` previews init/format/generate/create without writing or reserving
files/IDs. Ownership, edited-file and path checks still apply. Do not combine
it with `--check`. Validate and status have neither flag.

Deploy dry-run is a database operation: it requires explicit configuration and
temporarily takes the session advisory lock. It writes no files, schema/history
objects or migration rows. It does not take the writer's DDL relation locks.

## Migration status

Run `tuprel migrate status --target-schema <name>` from the project directory.
The fixed local catalog is `tuprel/migrations`; `--schema` selects a schema
file for other commands and is not a status option. The explicit target must
be one valid SQL identifier and an existing PostgreSQL schema.

Provide these process environment variables through your secret mechanism:

| Variable | Contract |
|---|---|
| TUPREL_DATABASE_URL | `jdbc:postgresql://host:port/database`, with explicit port and database |
| TUPREL_DATABASE_USER | Database user; never echoed |
| TUPREL_DATABASE_PASSWORD | Nonempty secret; never echoed |

No automatic dotenv loading, schema datasource resolution or fallback to
`TUPREL_TEST_*` occurs. URLs reject embedded credentials and arbitrary driver
options. Only `sslmode=verify-full`, or `sslmode=disable` on loopback, is accepted.
Remote TLS defaults to verify-full and requires suitable trusted certificates;
loopback defaults to disabled TLS. Connect/login timeouts are 5 seconds, query
timeout 10 seconds and socket timeout 30 seconds. There are no automatic retries.

`status --help` does not read the environment, files or database. Normal status
reads existing history in `REPEATABLE READ, READ ONLY`, rolls back the read
transaction and closes the connection before returning a confirmed result.
It requires USAGE on the target schema, SELECT on existing history and standard
pg_catalog read access; it never initialises history or repairs objects.

Output lists UTC IDs, names and state flags, followed by applied/pending counts
and `DRIFT_NOT_CHECKED`. See the [migration guide](../guides/migrations.md#inspect-existing-history)
for integrity states and limitations. No physical drift check is performed.

## Migration deployment

Use the same three explicit environment settings and an existing target schema
as [status](#migration-status). Run from the project containing the format-v1
catalog at `tuprel/migrations`. No dotenv, schema datasource, upward search or
`--schema` option is used. Deploy never generates SQL from schema.tuprel.

1. Edit and review existing migration files using the supported DDL subset.
2. Run `tuprel migrate deploy --target-schema app --dry-run` to inspect current
   history, the complete canonical preview, risks and approval digest.
3. After review, pass that exact lowercase SHA-256 with `--approve-digest`.
   The two flags are exclusive. There is no implicit approval or generic --yes.

Dry-run uses a fresh read-only history snapshot under the session advisory lock.
It can wait up to the lock polling deadline or return LOCK_BUSY. It never
bootstraps history or applies migration SQL; it does not reserve the plan.
Actual deployment independently reobserves files/history and revalidates the
complete digest under lock. Changed/blocked plans are refused before DDL.
Unknown or destructive SQL has no partial preview or approval bypass.

Output reports APPLIED, NO_CHANGES, REFUSED, FAILED or OUTCOME_UNKNOWN and the
newly confirmed prefix. Each migration has its own atomic DDL/history transaction;
earlier confirmed migrations survive later failure. Normal deployment diagnostics
never include SQL or raw driver messages. Only explicit dry-run displays canonical
SQL. DRIFT_NOT_CHECKED always remains visible.

After OUTCOME_UNKNOWN, stop and inspect current history/artifacts. Use status
and a fresh deploy dry-run under lock before reviewing another explicit invocation.
Never assume rollback or repeat the confirmed prefix. There is no automatic retry,
repair, durable attempt log or automatic crash recovery. A deterministic digest
does not attest human review and can remain identical after unchanged rollback.

Timeouts are separate: connect/login 5s, session-lock polling 30s, DDL lock 10s,
statement 20s, socket 30s. These do not impose one wall-clock deadline on the
entire sequence. See [Migrations](../guides/migrations.md#deploy-reviewed-files-from-the-cli)
for shell examples, physical catalog restrictions and recovery limits.

## Read-only migration development preview

`migrate dev` reads the desired schema and compares it with a verified applied
prefix from `tuprel/migrations`. It requires `--confirm-managed-history` for
each invocation. Use the flag only when history provenance is trusted and
an administrative window excludes external DDL. Configure the migration
database environment as for `migrate status`.

Missing history, drift, incomplete evidence, pending artifacts or unsupported
changes block the complete proposal. The command prints canonical SQL and
risks only after successful validation and replay. It emits no artifact or
approval digest. Exit codes are 0 for a preview or no changes, 1 for invalid
schema syntax or semantics, and 2 for usage, I/O or a safety refusal. See
[the migration guide](../guides/migrations.md#preview-a-development-migration).

## Paths and output

Default: `tuprel/schema.tuprel` relative to the process directory, with no upward
search. `--schema` and a positional path are exclusive. Quote paths with spaces;
`--` ends options for positional paths beginning with a hyphen.

Generation targets `build/generated/sources/tuprel/main` relative to the project.
Edited/unowned output is refused; no generic force overwrite exists. Gradle
`:tuprel-cli:run` uses `tuprel-cli/` as its process directory, so a root schema path
needs `../`. Prefer the packaged launcher when working in another Java project.

## Exit codes and diagnostics

| Code | Meaning |
|---|---|
| 0 | Success, valid dry-run, or check with no differences |
| 1 | Invalid schema, unsupported generation, differences in `--check`, status integrity/order divergence, or previewable migration diff |
| 2 | Invalid usage, I/O, unsafe path/ownership, database failure, blocked/stale deployment approval or OUTCOME_UNKNOWN |

Help/version/success go to stdout; diagnostics to stderr. Errors use stable
codes and corrective instructions without echoing arguments, secrets, source
lines or raw driver messages. Version reports `tuprel development` until a
release version is assigned; it is not a published version number.

For status, pending migrations and UNINITIALISED history exit 0. A failed
snapshot exits 2 and reports `DRIFT_NOT_CHECKED`; no partial result is confirmed.

See [Troubleshooting](../help/troubleshooting.md) and
[Migrations](../guides/migrations.md). Future migration commands are not presented
as usable commands. Deploy dry-run uses the same limited DDL preview and
contextual risk/digest contracts as the Java library. The internal [CLI contract](../product/CLI_EXPERIENCE.md)
also documents design decisions and the separate roadmap.

## Offline migration diff

`tuprel migrate diff --target-schema app [--schema tuprel/schema.tuprel]`
compares the replay of **all** local tuprel/migrations files with the declared
schema. Files already recorded as applied and pending files are both included;
no database or history is consulted. Use status for history integrity. Logical
equality says nothing about the current physical database or physical drift.

Output identifies both sources, known exact-name changes and risks. Only fully
supported additions receive canonical SQL. Removals, type/constraint/index
changes and unknown input block the whole preview, including independent adds.
No rename inference, approval digest, generated artifact or SQL execution.

Exit 0: logical equality. Exit 1: previewable differences, or invalid schema
with safe compiler diagnostic codes/positions. Exit 2: invalid options, I/O,
incomplete/unsupported states or blocked preview. No --dry-run is necessary;
the command always reads only. --help/-h never read files or environment.

Schema input is strict UTF-8, at most 1 MiB; CRLF is accepted. Links, aliases,
traversal and ordinary detected concurrent changes are refused. Work in a
developer-controlled workspace with concurrent writers excluded; this is not
an OS sandbox or filesystem reservation. Catalog v1 checks and budgets remain
those of MigrationDirectory. No .env or TUPREL_DATABASE_* configuration is read.
See the [guide](../guides/migrations.md#compare-migrations-with-schematuprel).
