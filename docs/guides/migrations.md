[Documentation](../README.md) / Guides

# Migrations

**Available now:** initialise a format-v1 catalog, create comment-only drafts,
validate files through the library and compute exact SHA-256 checksums. All offline.

**Not available in main:** history/status, deploy, dev, diff, database bootstrap,
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
this version does not yet track applied files or enforce database immutability.

[ADR-0012](../adr/ADR-0012-offline-cli-and-migration-files.md) defines the exact
format. The playground still uses
[manual bootstrap SQL](../../examples/tuprel-playground/src/main/resources/bootstrap.sql).
Draft creation neither replaces that bootstrap nor migrates existing data.
