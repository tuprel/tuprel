# Tuprel

**Modern relational data toolkit for Java.**

Tuprel is an open-source relational data toolkit for Java focused on explicit, predictable, and type-safe relational database development. Its direction is a schema-first workflow with generated Java APIs and a PostgreSQL-first runtime.

> **Early development:** The schema language and Java source generation are available from a source checkout, but there is no stable public release or published Maven artifact. Database access, an operational client, and migrations are not implemented yet.

## What Tuprel is building

The workflow starts with `schema.tuprel` as the declarative source for models and relationships. Tuprel can now generate Java domain types from that schema. Future phases will add an operational Java client, execute explicit queries against PostgreSQL, and provide reviewed migrations plus Gradle and Maven integrations. The core is designed to work without a framework; Spring Boot integration is planned as a separate module.

The design favors visible relational behavior over implicit database work. It calls for explicit relation loading and writes, inspectable SQL, generated code developers can understand, useful source-location diagnostics, and parameter binding for SQL values. These are design goals for future phases, not claims about features already shipped.

## What works today

The implemented foundation provides:

- A UTF-8 `schema.tuprel` frontend with a deterministic lexer, recursive-descent parser, immutable AST, semantic validation, structured diagnostics with source locations, and a deterministic formatter.
- Datasource and Java generator declarations; models, enums, the initial scalar types, nullable and list cardinality, single-field IDs, unique fields, supported defaults, indexes, unique constraints, and explicit basic relation references.
- An initial CLI with `validate`, `format`, and `format --check` commands. Validation and formatting operate on schema text; they do not connect to a database.
- Java 21 source generation from a validated schema through `tuprel-codegen-java`, with immutable model values, enums, typed field metadata, create/update inputs, and structural where accessors. The generated `TuprelSchema` describes models but does not execute queries.
- `generate` and `generate --check` CLI commands. Generated files have an ownership manifest and are written under `build/generated/sources/tuprel/main` relative to the CLI process.

The current modules are [`tuprel-schema`](tuprel-schema/) for the schema frontend, [`tuprel-codegen-java`](tuprel-codegen-java/) for Java generation, and [`tuprel-cli`](tuprel-cli/) for the command-line adapter. The CLI has no public installer or binary release yet. The supported syntax and Java mapping are defined in [RFC-001](docs/rfcs/RFC-001-schema-language.md) and [RFC-002](docs/rfcs/RFC-002-java-client-api.md).

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

The engineering foundation, Phase 1 schema language, and Phase 2 Java source generation are in place. The [master plan](plans/MASTER_PLAN.md) next calls for a PostgreSQL runtime and operational client, type-safe querying, relations and transactions, migrations, and developer integrations. Broader tooling, including Studio, is later work. No release dates are promised.

## Build from source

Use Java 21 and the checked-in Gradle Wrapper; a global Gradle installation is not required.

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
