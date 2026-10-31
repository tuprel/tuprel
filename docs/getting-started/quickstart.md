[Documentation](../README.md) / Getting started

# Quickstart

Create and check files in a new temporary project. No database, credentials or
Docker are needed. First [build the CLI](installation.md), then run these from
the repository root.

## Create a fresh project

**Bash**

```bash
tuprel_cli="$PWD/tuprel-cli/build/install/tuprel/bin/tuprel"
demo="$(mktemp -d)"
cd "$demo" || exit 2
"$tuprel_cli" --help
"$tuprel_cli" init --dry-run
"$tuprel_cli" init
"$tuprel_cli" validate
"$tuprel_cli" format --check
"$tuprel_cli" generate
"$tuprel_cli" generate --check
```

**PowerShell**

```powershell
$tuprelCli = (Resolve-Path '.\tuprel-cli\build\install\tuprel\bin\tuprel.bat').Path
$demo = Join-Path ([IO.Path]::GetTempPath()) ('tuprel-demo-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $demo | Out-Null
Set-Location $demo
& $tuprelCli --help
& $tuprelCli init --dry-run
& $tuprelCli init
& $tuprelCli validate
& $tuprelCli format --check
& $tuprelCli generate
& $tuprelCli generate --check
```

Use a new terminal to return to the checkout, or change back to its directory.
These commands leave the temporary project for inspection.

<details>
<summary>CLI output captured from a real PowerShell run</summary>

For `--version`, validate and format `--check` respectively:

```text
tuprel development
Schema is valid.
Schema is formatted.
```

Output was captured from the source launcher; it is not a release number.

</details>

## Inspect the result

`init` writes tuprel/schema.tuprel with a minimal User model and generator
package dev.example.tuprel. Change that package before adopting the file.
It also writes the marker tuprel/migrations/.tuprel-migrations.

`generate` writes Java into build/generated/sources/tuprel/main relative to
your project, recording file ownership. `generate --check` succeeds when the
output matches the schema. Do not edit generated Java by hand.

The commands do not compile your application or configure dependencies. For
that step use the [Java client guide](../guides/java-client.md) and
[playground consumer build](../../examples/tuprel-playground/build.gradle.kts).

## Try an offline draft

In this temporary project, invoke the same launcher with:

```text
migrate create --name initial --dry-run
migrate create --name initial
```

Real creation writes a UTC-named directory with comment-only migration.sql.
It neither derives SQL from the schema nor applies anything. Read
[Migrations](../guides/migrations.md) before editing or committing the draft.

## Connect to data when ready

The Java guides use the richer
[playground schema](../../examples/tuprel-playground/tuprel/schema.tuprel):
products, customers, orders and order items. The
[playground setup](../../examples/tuprel-playground/README.md) explains its
dedicated database and the effects of running the demonstration.
