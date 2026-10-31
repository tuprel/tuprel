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

`--dry-run` previews init/format/generate/create without writing or reserving
anything. Ownership, edited-file and path checks still apply. Do not combine
it with `--check`. Validate has neither flag.

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
| 1 | Invalid schema, unsupported generation, or differences in `--check` |
| 2 | Invalid usage, I/O, missing file, unsafe path or ownership refusal |

Help/version/success go to stdout; diagnostics to stderr. Errors use stable
codes and corrective instructions without echoing arguments, secrets, source
lines or raw driver messages. Version reports `tuprel development` until a
release version is assigned; it is not a published version number.

See [Troubleshooting](../help/troubleshooting.md) and
[Migrations](../guides/migrations.md). Future migration commands are not presented
as usable commands. The internal [CLI contract](../product/CLI_EXPERIENCE.md)
also documents design decisions and the separate roadmap.
