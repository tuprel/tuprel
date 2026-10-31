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
