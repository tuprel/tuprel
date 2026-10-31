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
