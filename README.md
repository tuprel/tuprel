# Tuprel

**Modern relational data toolkit for Java.**

Tuprel is an open-source relational data toolkit for Java focused on explicit, predictable, and type-safe relational database development. Its direction is a schema-first workflow with generated Java APIs and a PostgreSQL-first runtime.

> **Early development:** Phase 1 of the schema language is implemented, but there is no stable public release or published Maven artifact. Database access, Java client generation, and migrations are not implemented yet.

## What Tuprel is building

The intended workflow starts with `schema.tuprel` as the declarative source for models and relationships. Future phases will generate strongly typed Java APIs, execute explicit queries against PostgreSQL, and provide reviewed migrations plus Gradle and Maven integrations. The core is designed to work without a framework; Spring Boot integration is planned as a separate module.

The design favors visible relational behavior over implicit database work. It calls for explicit relation loading and writes, inspectable SQL, generated code developers can understand, useful source-location diagnostics, and parameter binding for SQL values. These are design goals for future phases, not claims about features already shipped.

## What works today

Phase 1 provides:

- A UTF-8 `schema.tuprel` frontend with a deterministic lexer, recursive-descent parser, immutable AST, semantic validation, structured diagnostics with source locations, and a deterministic formatter.
- Datasource and Java generator declarations; models, enums, the initial scalar types, nullable and list cardinality, single-field IDs, unique fields, supported defaults, indexes, unique constraints, and explicit basic relation references.
- An initial CLI with `validate`, `format`, and `format --check` commands. Validation and formatting operate on schema text; they do not connect to a database.

The current modules are [`tuprel-schema`](tuprel-schema/) for the schema frontend and [`tuprel-cli`](tuprel-cli/) for the command-line adapter. The CLI has no public installer or binary release yet. The supported syntax and its limits are defined in [RFC-001](docs/rfcs/RFC-001-schema-language.md).

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

The current validator checks this declaration locally. It does not create tables, generate Java code, or read the value of `DATABASE_URL`.

## CLI from a source checkout

The commands are:

```text
tuprel validate [schema.tuprel]
tuprel format [schema.tuprel]
tuprel format --check [schema.tuprel]
```

Without a path, they use `tuprel/schema.tuprel` relative to the CLI process. `format` writes the formatted file; `format --check` only checks it. Until a distribution is published, run the CLI through the checked-in Gradle Wrapper. Gradle starts the `run` task in `tuprel-cli/`, so use `../` for a schema at the repository root:

```bash
./gradlew :tuprel-cli:run --args="validate ../tuprel/schema.tuprel"
```

```powershell
.\gradlew.bat :tuprel-cli:run --args="validate ../tuprel/schema.tuprel"
```

Replace `validate` with `format` or `format --check` to run the other commands. See [Build and Test](docs/development/BUILD_AND_TEST.md) for the full development workflow.

## Roadmap

The engineering foundation and Phase 1 schema language are in place. The [master plan](plans/MASTER_PLAN.md) then calls for Java code generation and a client API, a PostgreSQL runtime, type-safe querying, relations and transactions, migrations, and developer integrations. Broader tooling, including Studio, is later work. No release dates are promised.

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
