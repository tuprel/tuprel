# TUPREL

**Typed Java data access, defined in a schema.**

[Get started](docs/getting-started/quickstart.md) · [Documentation](docs/README.md) · [Java playground](examples/tuprel-playground/README.md)

[![CI](https://github.com/tuprel/tuprel/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/tuprel/tuprel/actions/workflows/ci.yml?query=branch%3Amain)
[![CodeQL](https://github.com/tuprel/tuprel/actions/workflows/codeql.yml/badge.svg?branch=main)](https://github.com/tuprel/tuprel/actions/workflows/codeql.yml?query=branch%3Amain)

<a id="what-tuprel-is-building"></a>

Tuprel turns `schema.tuprel` into Java models and typed PostgreSQL clients.
Define your data once, then query it without writing a separate set of entities,
mappers and repositories. SQL parameters, relation loading and transaction
boundaries stay explicit.

<a id="what-works-today"></a>

## What you can do

- **Generate Java APIs** for models, create/update inputs, filters and ordering.
- **Read and write PostgreSQL data** with typed queries, pagination, projections,
  batch operations and SQL preview. Values use prepared-statement parameters.
- **Load relations deliberately** with include, and group work in transactions
  with savepoints, timeouts and optimistic locking.
- **Work from the terminal:** initialise a project, validate and format schemas,
  generate Java, check generated files and create offline migration drafts.

<a id="generated-client-example"></a>

## A query in Java

Using the [playground's Product model](examples/tuprel-playground/tuprel/schema.tuprel)
and an application-provided `DataSource`:

```java
TuprelClient db = new TuprelClient(PostgresqlDatabase.using(dataSource));

List<Product> available = db.product().findMany(query -> query
        .where(ProductWhere.stock().gt(0))
        .orderBy(ProductOrder.price().asc(), ProductOrder.id().asc())
        .take(20));

RenderedSql preview = db.product().preview(query -> query
        .where(ProductWhere.name().startsWith("Keyboard")));
```

The generated field types constrain the operators you can use. Relations are
loaded only when included; reading an unloaded relation never runs a query.
See the [Java client guide](docs/guides/java-client.md) for imports, setup and lifecycle.

<a id="cli-from-a-source-checkout"></a>
<a id="build-from-source"></a>

## Try the CLI

Install JDK 21 and Git. Build the launcher from source, then initialise a fresh
temporary project. This path needs no PostgreSQL server and makes no database calls.

**Bash**

```bash
git clone https://github.com/tuprel/tuprel.git
cd tuprel
./gradlew :tuprel-cli:installDist
tuprel_cli="$PWD/tuprel-cli/build/install/tuprel/bin/tuprel"
demo="$(mktemp -d)"
cd "$demo" || exit 2
"$tuprel_cli" init
"$tuprel_cli" validate
"$tuprel_cli" generate
"$tuprel_cli" generate --check
```

<details>
<summary>PowerShell</summary>

```powershell
git clone https://github.com/tuprel/tuprel.git
Set-Location tuprel
.\gradlew.bat :tuprel-cli:installDist
$tuprelCli = (Resolve-Path '.\tuprel-cli\build\install\tuprel\bin\tuprel.bat').Path
$demo = Join-Path ([IO.Path]::GetTempPath()) ('tuprel-demo-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $demo | Out-Null
Set-Location $demo
& $tuprelCli init
& $tuprelCli validate
& $tuprelCli generate
& $tuprelCli generate --check
```

</details>

init creates a minimal User schema and an empty migration catalog. Generated
Java goes in `build/generated/sources/tuprel/main` inside the new project.
It does not install libraries or configure a Java build. The
[quickstart](docs/getting-started/quickstart.md) explains the files and next steps;
[build from source](docs/getting-started/installation.md) covers prerequisites.

<a id="schema-example"></a>

## Learn and explore

| Goal | Start here |
|---|---|
| Define models and types | [Schema guide](docs/guides/schema.md) |
| Query and change data | [Java client](docs/guides/java-client.md) · [CRUD and queries](docs/guides/crud-and-queries.md) |
| Work with related data | [Relations](docs/guides/relations.md) · [Transactions](docs/guides/transactions.md) |
| Understand migration support | [Migrations](docs/guides/migrations.md) |
| Look up commands and APIs | [CLI reference](docs/reference/cli.md) · [Java API reference](docs/reference/java-api.md) |
| Resolve a problem | [Troubleshooting](docs/help/troubleshooting.md) · [FAQ](docs/help/faq.md) |

The [Java playground](examples/tuprel-playground/README.md) is a runnable consumer
application with products, customers and orders. It demonstrates generation,
CRUD, relations and transactions with local PostgreSQL, without Docker.

<a id="roadmap"></a>

## Compatibility and status

Tuprel is a **development preview built from source**. There is no stable public
release, published Maven artifact or public installer. Java 21 is the baseline;
PostgreSQL is the supported database. Windows/PowerShell is exercised locally
and Linux/Bash in CI; macOS validation is still pending.

Migration support currently creates and validates offline files. Applying SQL,
database migration history, drift detection and migrate deploy are not available
in main. Proposed work in open PRs is not part of this documentation's supported surface.

<a id="contributing-and-security"></a>

## Contribute, get help and report security issues

Use [Issues](https://github.com/tuprel/tuprel/issues) for bugs and usage questions.
Read [Contributing](CONTRIBUTING.md) for the engineering workflow and
[Security](SECURITY.md) for private vulnerability reporting.
Architecture, decisions and plans are linked from the
[contributor documentation](docs/README.md#for-contributors).

<a id="license"></a>

Tuprel is licensed under [Apache License 2.0](LICENSE).
