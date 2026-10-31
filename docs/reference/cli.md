[Documentation](../README.md) / Reference

# CLI reference

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
```

Every implemented command accepts `--help` or `-h`. `migrate --help` describes the
group. Global help/version must be used alone. No command reads stdin or prompts.
Output is plain text without ANSI colours.

| Command | Effect |
|---|---|
| init | Exclusively creates schema/catalog; refuses an existing tuprel path |
| validate | Parses/validates without resolving environment references |
| format | Writes formatting; `--check` compares without writing |
| generate | Writes Java under ownership control; `--check` compares without writing |
| migrate create | Writes a comment-only draft; never contacts a database |
| migrate status | Reads local files and existing PostgreSQL history; no application writes |

`--dry-run` previews init/format/generate/create without writing or reserving
anything. Ownership, edited-file and path checks still apply. Do not combine
it with `--check`. Validate and status have neither flag.

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
| 1 | Invalid schema, unsupported generation, differences in `--check`, or status integrity/order divergence |
| 2 | Invalid usage, I/O, missing file, unsafe path, ownership, status configuration/permissions/history/JDBC failure |

Help/version/success go to stdout; diagnostics to stderr. Errors use stable
codes and corrective instructions without echoing arguments, secrets, source
lines or raw driver messages. Version reports `tuprel development` until a
release version is assigned; it is not a published version number.

For status, pending migrations and UNINITIALISED history exit 0. A failed
snapshot exits 2 and reports `DRIFT_NOT_CHECKED`; no partial result is confirmed.

See [Troubleshooting](../help/troubleshooting.md) and
[Migrations](../guides/migrations.md). Future migration commands are not presented
as usable commands. Limited DDL parsing/preview is an offline Java library API,
not an additional CLI command. The internal [CLI contract](../product/CLI_EXPERIENCE.md)
also documents design decisions and the separate roadmap.
