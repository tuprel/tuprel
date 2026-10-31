<h1 align="center" id="tuprel">TUPREL</h1>

<p align="center"><strong>Your schema. Your Java API.</strong></p>

<p align="center">
  Define your models once. Generate typed Java clients for PostgreSQL.<br>
  Keep queries, relation loading and transactions explicit.
</p>

<p align="center">
  <a href="docs/getting-started/quickstart.md"><strong>Getting Started</strong></a> ·
  <a href="docs/README.md"><strong>Documentation</strong></a> ·
  <a href="examples/tuprel-playground/README.md"><strong>Examples</strong></a>
</p>

<p align="center">
  <a href="https://github.com/tuprel/tuprel/actions/workflows/ci.yml?query=branch%3Amain"><img src="https://img.shields.io/github/actions/workflow/status/tuprel/tuprel/ci.yml?branch=main&amp;label=CI&amp;style=flat" alt="CI on main" height="20"></a>
  <a href="https://github.com/tuprel/tuprel/actions/workflows/codeql.yml?query=branch%3Amain"><img src="https://img.shields.io/github/actions/workflow/status/tuprel/tuprel/codeql.yml?branch=main&amp;label=CodeQL&amp;style=flat" alt="CodeQL on main" height="20"></a>
  <a href="docs/getting-started/installation.md"><img src="https://img.shields.io/badge/Java-21-426A86?style=flat" alt="Java 21 baseline" height="20"></a>
  <a href="examples/tuprel-playground/README.md#configure-a-dedicated-local-database"><img src="https://img.shields.io/badge/database-PostgreSQL-426A86?style=flat" alt="Database: PostgreSQL" height="20"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-Apache%202.0-586069?style=flat" alt="Apache License 2.0" height="20"></a>
  <a href="#compatibility-and-project-status"><img src="https://img.shields.io/badge/status-Development%20Preview-91651E?style=flat" alt="Status: Development Preview" height="20"></a>
</p>

<a id="what-tuprel-is-building"></a>

Tuprel is a **schema-first data access toolkit for Java**. It turns
[schema.tuprel](docs/guides/schema.md) into immutable model values, input builders
and a typed [Java client](docs/guides/java-client.md), so you can spend less time
maintaining matching entities, mappers and repositories.

[See it in action](#see-tuprel-in-action) · [Get started](#getting-started) ·
[Explore the guides](#documentation-navigation) · [Check compatibility](#compatibility-and-project-status)

## Why TUPREL?

- **Let the schema shape your API.** Generated fields, filters and ordering carry
  Java types into the places where you read and change data.
- **Choose when database work happens.** Relations load through explicit includes;
  getters never issue queries. Transaction boundaries belong in your code.
- **Keep SQL within reach.** Preview a query before running it. Values travel as
  bound parameters, separate from SQL text.
- **Use your application's infrastructure.** Supply a JDBC `DataSource`; the core
  has no dependency on JPA, Hibernate or Spring.

<a id="what-works-today"></a>
<a id="generated-client-example"></a>
<a id="a-query-in-java"></a>

## See TUPREL in Action

Find in-stock products, ordered by price with an ID tie-breaker, using the
[playground's generated client](examples/tuprel-playground/README.md):

```java
TuprelClient db = new TuprelClient(PostgresqlDatabase.using(dataSource));

List<Product> available = db.product().findMany(query -> query
        .where(ProductWhere.stock().gt(0)
                .and(ProductWhere.name().startsWith("Key")))
        .orderBy(ProductOrder.price().asc(), ProductOrder.id().asc())
        .take(20));
```

`dataSource` is provided by your application and selects an existing database
and schema with matching tables. Tuprel does not create those tables or manage
your connection pool. The [client guide](docs/guides/java-client.md) covers setup
and lifecycle; [queries](docs/guides/crud-and-queries.md) explains filtering and pagination.

<details>
<summary>Imports for the Java examples on this page</summary>

```java
import dev.tuprel.playground.generated.TuprelClient;
import dev.tuprel.playground.generated.include.SalesOrderInclude;
import dev.tuprel.playground.generated.model.Customer;
import dev.tuprel.playground.generated.model.Product;
import dev.tuprel.playground.generated.model.SalesOrder;
import dev.tuprel.playground.generated.order.ProductOrder;
import dev.tuprel.playground.generated.update.ProductUpdate;
import dev.tuprel.playground.generated.where.ProductWhere;
import dev.tuprel.postgresql.PostgresqlDatabase;
import dev.tuprel.sql.RenderedSql;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
```

The snippets share `db`. Relation and transaction examples also assume existing
`UUID orderId` and `UUID productId` values, respectively.

</details>

<a id="what-you-can-do"></a>

## What You Can Build

- **Product catalogs and back-office tools:** typed CRUD, composed filters,
  projections, counts and deterministic pagination.
- **Customer and order workflows:** explicitly include related data and group
  dependent writes in transactions, with savepoints and optimistic locking.
- **Java services and command-line applications:** use generated clients with
  your own `DataSource`, build and application lifecycle.

The [runnable playground](examples/tuprel-playground/README.md) brings these
pieces together. Tuprel is currently a development preview; evaluate its
[supported surface](#compatibility-and-project-status) before adopting it.

<a id="cli-from-a-source-checkout"></a>
<a id="build-from-source"></a>
<a id="try-the-cli"></a>

## Getting Started

**Start offline with JDK 21 and Git.** Build the CLI from source, then generate
Java in a fresh temporary project. This path needs no database, credentials or Docker.

<details open>
<summary>Bash</summary>

```bash
git clone https://github.com/tuprel/tuprel.git
cd tuprel
./gradlew :tuprel-cli:installDist --dependency-verification strict
tuprel_cli="$PWD/tuprel-cli/build/install/tuprel/bin/tuprel"
demo="$(mktemp -d)"
cd "$demo" || exit 2
"$tuprel_cli" init --dry-run
"$tuprel_cli" init
"$tuprel_cli" validate
"$tuprel_cli" generate
"$tuprel_cli" generate --check
```

</details>

<details>
<summary>PowerShell</summary>

```powershell
git clone https://github.com/tuprel/tuprel.git
Set-Location tuprel
.\gradlew.bat :tuprel-cli:installDist --dependency-verification strict
$tuprelCli = (Resolve-Path '.\tuprel-cli\build\install\tuprel\bin\tuprel.bat').Path
$demo = Join-Path ([IO.Path]::GetTempPath()) ('tuprel-demo-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $demo | Out-Null
Set-Location $demo
& $tuprelCli init --dry-run
& $tuprelCli init
& $tuprelCli validate
& $tuprelCli generate
& $tuprelCli generate --check
```

</details>

Inspect `tuprel/schema.tuprel` and `build/generated/sources/tuprel/main` in that
temporary project. `init` starts with a minimal User model; it refuses an existing
`tuprel` path and does not change your build or install dependencies.

Continue with the [Quickstart](docs/getting-started/quickstart.md) for an explanation
of each file, or [Installation](docs/getting-started/installation.md) to use the
launcher in an existing Java project. For database access, follow the
[PostgreSQL setup](examples/tuprel-playground/README.md#configure-a-dedicated-local-database).

<a id="schema-example"></a>

## Schema and Java Client

Describe models, scalar types and relations in one file. Here is a compact
version of the [playground schema](examples/tuprel-playground/tuprel/schema.tuprel)
that supports the queries on this page:

<details>
<summary>schema.tuprel — products, customers and orders</summary>

```tuprel
generator java {
    package = "dev.tuprel.playground.generated"
}

model Product {
    id UUID @id
    name String
    price Decimal
    stock Int
    version Int @version @default(0)
}

model Customer {
    id UUID @id
    email String @unique
    name String
    orders SalesOrder[]
}

model SalesOrder {
    id UUID @id
    customerId UUID
    status String
    createdAt Instant
    customer Customer @relation(fields: [customerId], references: [id])
}
```

</details>

Save it as `tuprel/schema.tuprel` in your own project. With the built launcher's
`bin` directory on your PATH, generate and check the client:

```bash
tuprel validate --schema tuprel/schema.tuprel
tuprel generate --schema tuprel/schema.tuprel
tuprel generate --check --schema tuprel/schema.tuprel
```

On Windows, use `tuprel.bat` or invoke `$tuprelCli` with `&` as above. Generation
produces records, create/update builders, filters, ordering, includes and the
client. Keep generated files separate from application code. The
[schema guide](docs/guides/schema.md) defines the supported language; the
[Java client guide](docs/guides/java-client.md) shows how to compile and consume it.

## Queries, Relations and Transactions

### Inspect a query before running it

```java
RenderedSql preview = db.product().preview(query -> query
        .where(ProductWhere.name().eq("Keyboard")));
String sql = preview.text();
```

Preview renders the root SELECT without executing it. Parameter values stay
separate; avoid logging binds. This is SQL preview, not a PostgreSQL execution
plan. Explore [CRUD and queries](docs/guides/crud-and-queries.md) for projections,
batch operations and pagination.

### Load the relations you ask for

```java
SalesOrder order = db.salesOrder().findById(orderId, query -> query
        .include(SalesOrderInclude.customer())).orElseThrow();
Customer customer = order.customer().get();
```

[Relations](docs/guides/relations.md) load in batches for the requested paths.
Reading an unloaded relation fails without I/O. Includes are explicit extra
queries, not implicit JOINs; the guide covers their consistency and pagination limits.

### Make transaction boundaries visible

```java
Product changed = db.transaction(tx -> {
    Product current = tx.product().findById(productId).orElseThrow();
    return tx.product().updateById(current.id(), current.version(),
            ProductUpdate.builder().stock(current.stock() + 1).build()).orElseThrow();
});
```

Success commits; an exception rolls back. The Product model's `@version` enables
an atomic expected-version check. Nested transactions use savepoints. The
[transaction guide](docs/guides/transactions.md) explains isolation, deadlines,
streams and uncertain connection/commit outcomes; callbacks are not automatically retried.

## CLI Experience

Use `tuprel` on Bash or `tuprel.bat` on Windows after
[building the distribution](docs/getting-started/installation.md).

```bash
tuprel --help
tuprel --version
tuprel format --check
tuprel generate --dry-run
tuprel migrate create --name initial --dry-run
tuprel migrate create --name initial
```

Run project commands from the directory containing `tuprel/schema.tuprel`.
Checks detect differences without writing; dry-runs preview supported changes.
Commands have individual help, plain-text output and documented exit codes for
scripts. The [CLI reference](docs/reference/cli.md) lists the complete available surface.

**[Migrations](docs/guides/migrations.md) are offline drafts today.** `create`
writes a UTC-named folder and a comment-only `migration.sql` for manual editing.
It neither derives SQL from your schema nor applies it to a database.

<a id="learn-and-explore"></a>

## Documentation Navigation

**Learn:** [Installation](docs/getting-started/installation.md) ·
[Quickstart](docs/getting-started/quickstart.md) · [Schema](docs/guides/schema.md) ·
[Java client](docs/guides/java-client.md)

**Build:** [CRUD and queries](docs/guides/crud-and-queries.md) ·
[Relations](docs/guides/relations.md) · [Transactions](docs/guides/transactions.md) ·
[Migrations](docs/guides/migrations.md)

**Look up:** [CLI reference](docs/reference/cli.md) ·
[Java API reference](docs/reference/java-api.md) ·
[Troubleshooting](docs/help/troubleshooting.md) · [FAQ](docs/help/faq.md)

The [documentation hub](docs/README.md) includes a learning path and a separate
entry for contributor architecture, decisions and plans.

## Examples and Playground

The [Java playground](examples/tuprel-playground/README.md) is a standalone consumer
project for products, customers, orders and order items. Its composite build
generates and compiles a client directly from the checkout, without publishing artifacts.

Build it without a database, or follow its local PostgreSQL instructions to
exercise CRUD, includes, transactions, savepoints and optimistic locking.
Running the demonstration bootstraps tables and writes sample data: review its
instructions and use the dedicated development database. No Docker is needed locally.

<a id="roadmap"></a>
<a id="compatibility-and-status"></a>

## Compatibility and Project Status

**Development Preview — built from source.** There is no stable public release,
Maven Central artifact or public installer. APIs and formats may change.

- **Java:** JDK 21 baseline, synchronous JDBC and an application-provided `DataSource`.
- **Database:** PostgreSQL; the local playground is exercised with PostgreSQL 17.
- **Platforms:** Windows/PowerShell locally and Linux/Bash in CI. macOS validation is pending.
- **Migrations:** offline file creation and validation. History/status, deploy,
  dev, diff and drift detection are not available in `main`.

Spring Boot Starter, `db pull`, Query Inspector and Studio are planned, not
available features. Open PRs do not expand the supported surface. Read the
[FAQ](docs/help/faq.md) for current limitations and the
[contributor roadmap](plans/MASTER_PLAN.md) for proposed work.

<a id="contributing-and-security"></a>
<a id="contribute-get-help-and-report-security-issues"></a>

## Contributing, Security and License

Questions, bug reports and reproducible examples belong in
[Issues](https://github.com/tuprel/tuprel/issues). To contribute, start with
[Contributing](CONTRIBUTING.md) and the [contributor documentation](docs/README.md#for-contributors).
Report vulnerabilities privately through [Security](SECURITY.md).

<a id="license"></a>

Tuprel is licensed under the [Apache License 2.0](LICENSE).
