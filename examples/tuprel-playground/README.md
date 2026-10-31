<a id="tuprel-playground--postgresql-local-java-21"></a>

# Java playground

A standalone Java 21 consumer application using Tuprel from this checkout.
It demonstrates products, customers and orders with local PostgreSQL, without
Docker or Testcontainers.

[Documentation](../../docs/README.md) · [Schema](tuprel/schema.tuprel) · [Application](src/main/java/dev/tuprel/playground/Playground.java)

## Build without a database

From the repository root:

```bash
./gradlew -p examples/tuprel-playground build --dependency-verification strict
```

PowerShell: `.\gradlew.bat -p examples/tuprel-playground build --dependency-verification strict`.

This validates the schema, generates/compiles the Java client and runs two local
target-safety unit tests. It does not run the database demonstration. Run the
root and playground builds sequentially.

The [build](build.gradle.kts) consumes Tuprel through `includeBuild("../..")`.
No artifact publication or product Gradle plugin is required. Open this directory
as a Gradle project in your IDE; select JDK 21 for both the project and Gradle JVM.
The example has its own Wrapper, fixed to the same version as the root Wrapper.

<a id="preparação-no-windows"></a>

## Configure a dedicated local database

Use local PostgreSQL and a dedicated database named `tuprel_devtest`, owned by
your test user. If it does not exist, an administrator must create it once.
The application never creates/deletes databases or changes users/permissions.
If it already exists, retain its data and settings; do not recreate it for this example.

For a new checkout, copy the template without overwriting an existing .env:

```powershell
Copy-Item .\examples\tuprel-playground\.env.example .\examples\tuprel-playground\.env -Confirm
```

Then edit the ignored .env locally. The expected entries are:

```dotenv
TUPREL_TEST_JDBC_URL=jdbc:postgresql://localhost:5432/tuprel_devtest
TUPREL_TEST_DB_USER=
TUPREL_TEST_DB_PASSWORD=
```

Fill the username/password in your editor. Do not put them in Git, command-line
arguments, screenshots or issue attachments. Adjust the port if your local
installation uses another one. Only the dedicated database on loopback is accepted.

The test/example loader reads this exact file. Non-empty process environment
values take precedence. It accepts literal values, optional surrounding quotes,
blank lines and full-line comments; it does not execute shell interpolation,
export statements or variable substitution. It is not a production configuration API.

## Check the connection, then run deliberately

From the root, a read-only connection check is:

```bash
./gradlew -p examples/tuprel-playground checkConnection
```

PowerShell uses .\gradlew.bat. A successful check verifies connectivity only.

The next command **runs bootstrap SQL and writes demonstration rows** into
the dedicated database's `tuprel_playground` schema:

```bash
./gradlew -p examples/tuprel-playground run --dependency-verification strict
```

It adds new customers, orders and products on each run, preserving them for
inspection. It only deletes its own temporary product used to demonstrate DELETE.
Existing schema/table/data are not dropped. Do not run against production or
use this as a connection-only probe.

<a id="intellij"></a>

For direct IDE execution, use JDK 21 and set the working directory to this
example directory. Start with dev.tuprel.playground.CheckConnection, then
dev.tuprel.playground.Playground only when you intend the writes above.

<a id="o-que-comprova"></a>

## What it demonstrates

- CLI validation/generation and compilation of the generated Java 21 client.
- Transactional writes across customers, products, orders and items.
- Typed filters, ordering, offset/cursor pagination and createMany.
- Explicit nested includes, without lazy loading.
- Versioned updates, optimistic-lock conflicts and deletion.
- Full rollback and nested savepoint rollback with outer continuation.
- Unique/FK/CHECK errors and required-field validation before SQL.
- SQL text with placeholders, without printing binds or credentials.

The demonstration fails if its assertions fail. A successful build alone does
not establish that these database paths were executed.

<a id="limitações-deliberadas"></a>

## Bootstrap is temporary

[src/main/resources/bootstrap.sql](src/main/resources/bootstrap.sql) is reviewed
manual DDL, executed transactionally in the example schema. CREATE TABLE IF NOT
EXISTS does not evolve tables, validate drift, record migration history or check
migration checksums. CHECK constraints here are manual SQL, not generated schema DDL.

Offline migration drafts do not replace this bootstrap. Adoption of a migration
engine needs its own reviewed baseline and data-preservation task; existing
playground data must not be reset to demonstrate it.

## Generated files and tests

compileJava runs validateSchema/generateClient. Generated files live under
build/generated/sources/tuprel/main; never edit or commit them.

The example's dependency-verification metadata mirrors the root and is compared
by CI. Its consumed dependencies have their own lockfile. The internal PostgreSQL
integration suites use isolated test schemas, separate from persisted playground
rows. Contributor setup is in [Build and Test](../../docs/development/BUILD_AND_TEST.md).
