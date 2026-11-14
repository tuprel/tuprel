# Products REST API

A standalone Spring Boot 4.1.2 application for Java 21 and PostgreSQL 17.
Spring owns the pooled DataSource. The Tuprel starter adapts it and the
application injects its generated client into the service. Each write is a
single Tuprel operation using the current JDBC ownership contract. Spring
transaction participation is outside this example.

Request validation uses Jakarta Validation with Apache BVal.

## Build outside the Tuprel checkout

From a Tuprel development checkout, stage the sample, CLI and Maven
artifacts into a new directory. Java 21, Maven and PowerShell 7 are required.
Use a fresh Maven cache so that fixture coordinates from another checkout
cannot be resolved from a previous run.

```powershell
$stage = ./test-support/distribution/Stage-SpringProductsApi.ps1 -Destination (Join-Path ([IO.Path]::GetTempPath()) "tuprel-products-$([guid]::NewGuid())")
$env:TUPREL_FIXTURE_REPOSITORY = $stage.Repository
$env:TUPREL_CLI_HOME = $stage.CliHome
Set-Location -LiteralPath $stage.Project
mvn --strict-checksums "-Dmaven.repo.local=$($stage.Project)/target-maven-cache" -DskipTests package
```

The `generate-sources` phase invokes the staged CLI using Java directly so
the same Maven configuration works on Windows and Linux. The output goes
under `build/generated/sources/tuprel/main`. Tuprel fixture coordinates
resolve from the staged file repository. Spring Boot and other third-party
dependencies resolve from Maven Central.

## Start the API

In a disposable PostgreSQL 17 database, create a dedicated schema and run
`sql/products.sql` with that schema selected as the search path. Review the
SQL before running it. The application does not create or migrate tables.

For example, from the staged project directory, connect with `psql` to your
disposable database and run:

```sql
CREATE SCHEMA products_demo;
SET search_path TO products_demo;
\i sql/products.sql
```

Configure the Spring DataSource with `SPRING_DATASOURCE_URL` (including
`?currentSchema=<your-schema>`), `SPRING_DATASOURCE_USERNAME` and
`SPRING_DATASOURCE_PASSWORD`. Supply the password through your environment
or secret manager. Use a role with the required product table privileges.

```text
java -jar target/spring-products-api-0.0.1-SNAPSHOT.jar
```

The example listens on `127.0.0.1:8080` and is intended for local development.
It has no authentication layer.

| Method | Path | Success |
| --- | --- | --- |
| POST | `/products` | 201 with the product and its Location |
| GET | `/products/{id}` | 200 with the product |
| GET | `/products?page=0&size=20` | 200 with items and hasNext |
| PUT | `/products/{id}` | 200 with the replaced product |
| DELETE | `/products/{id}` | 204 |

POST and PUT accept JSON with a nonblank name up to 120 characters, a
nonnegative price with at most two fractional digits, and nonnegative
integer stock. The server assigns the UUID. Pagination is zero-based,
ordered by UUID and capped at 100 items per request. Missing products and
invalid requests return problem details.

Each offset page is a separate query. Concurrent inserts or deletes can
shift offsets between requests, so clients can see duplicates or miss rows.
The API provides no snapshot across pages.

```powershell
Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8080/products -ContentType application/json -Body '{"name":"Keyboard","price":49.90,"stock":3}'
Invoke-RestMethod -Uri 'http://127.0.0.1:8080/products?page=0&size=20'
```

## Test and integration paths

For `mvn verify`, provide `TUPREL_TEST_JDBC_URL` (a loopback JDBC URL to
`tuprel_devtest` with an explicit port), `TUPREL_TEST_DB_USER` and
`TUPREL_TEST_DB_PASSWORD`. The role must be able to create schemas in that
test database. Tests start the actual server on a random port, use HTTP
and clean up their isolated schema.

The existing Gradle plugin can generate and register sources from a local
fixture marker. To port the build, stage the marker with
`test-support/distribution/staged-gradle-plugin.init.gradle` and add that
repository to `pluginManagement.repositories`. Apply
`dev.tuprel.codegen` at `0.0.0-fixture`, set
`tuprelCodegen.cliClasspath` to the JARs in the staged CLI's `lib`
directory, and resolve the starter from the staged Maven repository.
The plugin wires generation to Java compilation. The plugin and marker
are tested separately in an external Gradle project. This REST application
is tested with Maven. Public coordinates, plugin resolution without local
staging and official installer downloads depend on publication.

From the Tuprel checkout, `./test-support/distribution/Verify-SpringProductsApi.ps1`
stages a temporary consumer and runs the HTTP test. It accepts the existing
local test settings used by the Tuprel integration suite. The application
itself uses Spring's DataSource configuration.

After Maven exits, the verification script inspects the executable archive
and launches it in a separate `java -jar` process. It waits for a successful
HTTP query, verifies request validation, creates and reads a product, and
checks the persisted row. The proof stops the child process and drops its
dedicated schema. The Linux staging workflow runs this against disposable
PostgreSQL 17.
