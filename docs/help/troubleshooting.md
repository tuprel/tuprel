[Documentation](../README.md) / Help

# Troubleshooting

Start with tuprel `--help` or a command's --help. The
[CLI reference](../reference/cli.md) explains working directories and exit codes.

| Symptom | Action |
|---|---|
| Cannot find Java 21 | Check java -version and Wrapper `--version`; select JDK 21 for terminal and IDE Gradle JVM |
| tuprel is not found | Build installDist and invoke its absolute launcher path |
| TUPREL-CLI-READ | Check `--schema`, UTF-8, permissions and current directory |
| init refuses a path | Inspect existing/partial tuprel files; try a fresh project rather than deleting working files blindly |
| generate refuses output | Inspect ownership and edits; keep manual code outside the generated directory |
| `--check` exits 1 | Review differences, then use format/generate without `--check` when you intend to write |
| Invalid migration name | Use lowercase ASCII beginning with a letter; no spaces, accents or paths |
| Collision/partial creation | Inspect IDs/files and active creators; dry-run reserves nothing; do not delete an active coordination file |
| No generated client | Check for `Json` or scalar lists, outside the operational subset |
| `Relation` get() fails | Request the corresponding include; access never triggers a lazy query |
| `OptimisticLockException` | Re-read and reconcile the operation instead of blindly retrying |
| Stream/row lock rejected | Use a transaction client; consume and close streams inside its callback |
| JDBC/mapping error | Check schema/search path and reviewed table DDL; inspect safe phase/SQLState, not raw credential-bearing errors |

## Migration status

| Diagnostic | Action |
|---|---|
| TUPREL-MIGRATE-HISTORY-CONFIGURATION | Supply all three TUPREL_DATABASE_* variables and an explicit valid target schema; check the URL allowlist in the [CLI reference](../reference/cli.md#migration-status) |
| MISSING_SCHEMA | Select the intended existing schema; status never creates it |
| PERMISSION | Ask the schema owner for USAGE and SELECT on history; do not escalate privileges to bypass a failure |
| INCOMPATIBLE | Inspect the known v1 structure, constraints and row values with the owner; status does not repair them |
| TRANSACTION, TIMEOUT or JDBC | Check the idle connection contract, connectivity and concurrent work; no snapshot is confirmed and no retry is automatic |
| TUPREL-MIGRATE-STATUS-INTEGRITY | Review files against committed artifacts and recorded history; do not edit applied migrations to hide divergence |
| UNINITIALISED | No history table exists; this is normal and status writes nothing |
| DRIFT_NOT_CHECKED | Physical objects have not been compared; a clean status is not proof of schema equivalence |

Local catalog errors use the existing migration-validation diagnostics. Inspect
format-v1 files and project permissions. A file/history check cannot coordinate
concurrent local edits or administrative DDL. Redact credentials and JDBC causes
before sharing diagnostics.

## Migration deployment

| Diagnostic | Action |
|---|---|
| APPROVAL | Run deploy --dry-run and review the new SQL/risks/digest; never substitute a new digest automatically |
| BLOCKED or CATALOG | Inspect the supported SQL/physical subset and history integrity; approval cannot bypass unknown/destructive work |
| LOCK_BUSY | Inspect ongoing work and dedicated-session configuration; do not force-unlock another executor |
| PERMISSION | Check existing schema USAGE/CREATE and canonical history SELECT/INSERT with its owner; do not bypass permissions |
| TIMEOUT or STATEMENT | Inspect active work, reviewed SQL and confirmed prefix; the runner performs no automatic retry |
| OUTCOME_UNKNOWN, COMMIT, CONNECTION or ROLLBACK | Stop; inspect history/artifacts through status and a fresh deploy dry-run under lock. Do not infer rollback from a lost acknowledgement |
| CLEANUP | Inspect connection/discard behavior and current history; previously confirmed commits remain applied |

Deployment failures exit 2 and print the newly confirmed prefix. A later failure
does not roll back earlier migrations. Dry-run never bootstraps or repairs
history. See the [deployment contract](../reference/cli.md#migration-deployment).

## Playground setup

The example accepts only loopback and dedicated tuprel_devtest. Follow its
[setup](../../examples/tuprel-playground/README.md). Edit your ignored .env
locally; do not replace an existing configuration with a template.
checkConnection is read-only; run bootstraps tables and writes sample rows.
Do not use run as a connection-only check.

## Report a reproducible issue

Include source commit, Java version, OS, command, exit code and a small redacted
schema/code sample. Do not attach .env, credentials, binds, personal data or raw
JDBC exception chains. Use [Issues](https://github.com/tuprel/tuprel/issues) for
ordinary bugs and [Security](../../SECURITY.md) for vulnerabilities.
