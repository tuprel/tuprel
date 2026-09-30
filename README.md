# Tuprel

**Modern relational data toolkit for Java.**

Tuprel is an open-source relational data toolkit for Java focused on explicit, predictable, and type-safe relational database development. Its direction is a schema-first workflow with generated Java APIs and a PostgreSQL-first runtime.

> **Early development:** Schema validation, Java source generation, and a generated type-safe PostgreSQL client are available from a source checkout. There is no stable public release or published Maven artifact. Relations, transactions, and migrations are not implemented yet.

## What Tuprel is building

The workflow starts with `schema.tuprel` as the declarative source for models and relationships. Tuprel generates Java domain types and a type-safe client that runs explicit queries against PostgreSQL through a caller-supplied `DataSource`. Future phases will add explicit relation loading, transactions, reviewed migrations, and Gradle and Maven integrations. The core is designed to work without a framework; Spring Boot integration is planned as a separate module.

The design favors visible relational behavior over implicit database work. Every client method runs one parameterized statement you can preview; there is no lazy loading, dirty checking, or hidden session. Relation loading and migration behavior remain future work.

## What works today

The implemented foundation provides:

- A UTF-8 `schema.tuprel` frontend with a deterministic lexer, recursive-descent parser, immutable AST, semantic validation, structured diagnostics with source locations, and a deterministic formatter.
- Datasource and Java generator declarations; models, enums, the initial scalar types, nullable and list cardinality, single-field IDs, unique fields, supported defaults, indexes, unique constraints, and explicit basic relation references.
- An initial CLI with `validate`, `format`, and `format --check` commands. Validation and formatting operate on schema text; they do not connect to a database.
- Java 21 source generation from a validated schema through `tuprel-codegen-java`, with immutable model values, enums, typed field metadata, and create/update inputs.
- A generated type-safe client per model:
  - `create`, `findById`, `findMany`, `findFirst`, and cursor pagination;
  - projections, `count`, `exists`, `updateById`, `deleteById`, and SQL preview.

  Conditions are typed by model and column type, so a condition from another model or an operator that does not fit the column does not compile.
- `generate` and `generate --check` CLI commands. Generated files have an ownership manifest and are written under `build/generated/sources/tuprel/main` relative to the CLI process.
- A PostgreSQL runtime with prepared statements for every value, explicit JDBC resource ownership, typed row mapping without reflection, and real PostgreSQL integration tests.

The current modules are [`tuprel-schema`](tuprel-schema/), [`tuprel-codegen-java`](tuprel-codegen-java/), [`tuprel-cli`](tuprel-cli/), [`tuprel-sql`](tuprel-sql/), [`tuprel-runtime`](tuprel-runtime/), and [`tuprel-postgresql`](tuprel-postgresql/). The CLI has no public installer or binary release yet. The supported schema and Java mapping are defined in [RFC-001](docs/rfcs/RFC-001-schema-language.md) and [RFC-002](docs/rfcs/RFC-002-java-client-api.md). [ADR-0009](docs/adr/ADR-0009-runtime-postgresql-foundation.md) defines the runtime subset, and [RFC-004](docs/rfcs/RFC-004-type-safe-query-api.md) defines the generated client and query API.

## Schema example

For the source-checkout command below, save this as `tuprel/schema.tuprel` at the repository root:

```tuprel
datasource db {
    provider = "postgresql"
    url = env("DATABASE_URL")
}

generator java {
    package = "dev.example.generated"
}

enum UserStatus {
    ACTIVE
    BLOCKED
}

model User {
    id UUID @id @default(uuid())
    email String @unique
    status UserStatus @default(ACTIVE)
    posts Post[]
}

model Post {
    id UUID @id @default(uuid())
    title String
    authorId UUID
    author User @relation(fields: [authorId], references: [id])

    @@index([authorId])
}
```

The validator checks this declaration locally. `generate` can produce Java source from it without creating tables or reading the value of `DATABASE_URL`.

## Generated client example

Given a `model Customer` with `name String`, `age Int?`, and `createdAt Instant` fields, the generated client is used like this. The same calls are compiled and run against PostgreSQL by the integration tests:

```java
TuprelDatabase database = PostgresqlDatabase.using(dataSource);
TuprelClient db = new TuprelClient(database);

List<Customer> adults = db.customer().findMany(query -> query
        .where(CustomerWhere.age().gte(18).and(CustomerWhere.name().startsWith("A")))
        .orderBy(CustomerOrder.createdAt().desc())
        .take(20));

long withoutAge = db.customer().count(query -> query.where(CustomerWhere.age().isNull()));

RenderedSql preview = db.customer().preview(query -> query.where(CustomerWhere.name().eq("Ana")));
// SELECT "id", ... FROM "Customer" WHERE "name" = ?   (the value stays a bound parameter)
```

The application owns the `DataSource`. Each call runs one statement on its own connection, and tables and columns use the schema names until name mapping and migrations exist.

## CLI from a source checkout

The commands are:

```text
tuprel validate [schema.tuprel]
tuprel format [schema.tuprel]
tuprel format --check [schema.tuprel]
tuprel generate [schema.tuprel]
tuprel generate --check [schema.tuprel]
```

Without a path, they use `tuprel/schema.tuprel` relative to the CLI process. `format` writes the formatted file; `format --check` only checks it. Until a distribution is published, run the CLI through the checked-in Gradle Wrapper. Gradle starts the `run` task in `tuprel-cli/`, so use `../` for a schema at the repository root:

```bash
./gradlew :tuprel-cli:run --args="validate ../tuprel/schema.tuprel"
```

```powershell
.\gradlew.bat :tuprel-cli:run --args="validate ../tuprel/schema.tuprel"
```

Replace `validate` with `format`, `format --check`, `generate`, or `generate --check` to run the other commands. With Gradle's `:tuprel-cli:run`, the process works in `tuprel-cli/`, so generated Java appears in `tuprel-cli/build/generated/sources/tuprel/main/`. See [Build and Test](docs/development/BUILD_AND_TEST.md) for the full development workflow.

## Roadmap

The engineering foundation, Phase 1 schema language, Phase 2 Java source generation, Phase 3 minimal PostgreSQL runtime, and Phase 4 type-safe query API are in place. The [master plan](plans/MASTER_PLAN.md) next calls for relations and transactions, migrations, and developer integrations. Broader tooling, including Studio, is later work. No release dates are promised.

## Build from source

Use Java 21 and the checked-in Gradle Wrapper; a global Gradle installation is not required. A Docker-compatible container runtime is needed for the PostgreSQL integration tests included in `check`.

```bash
./gradlew check
```

```powershell
.\gradlew.bat check
```

The root `check` includes the product modules and the `build-logic` TestKit suite. Build and testing details are in [Build and Test](docs/development/BUILD_AND_TEST.md).

## Contributing and security

Contributions are welcome. Read [CONTRIBUTING.md](CONTRIBUTING.md) before opening a pull request; design-sensitive changes should be discussed before implementation. Report vulnerabilities privately according to [SECURITY.md](SECURITY.md), not in a public issue.

## License

Tuprel is licensed under the [Apache License 2.0](LICENSE) (`Apache-2.0`).
