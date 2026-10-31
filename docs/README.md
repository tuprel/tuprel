# TUPREL documentation

Define models in `schema.tuprel`, generate Java, and use an explicit PostgreSQL
client. These pages describe what is available in `main` from a source checkout.
Tuprel is a development preview; there is no stable release or published artifact.

## Getting started

1. [Install and build from source](getting-started/installation.md) with JDK 21.
2. [Run the quickstart](getting-started/quickstart.md) without a database.
3. [Explore the Java playground](../examples/tuprel-playground/README.md) when
   you want to connect to a dedicated local PostgreSQL database.

## Guides

| Topic | What you will learn |
|---|---|
| [Schema](guides/schema.md) | Models, scalar types, defaults and generation limits |
| [Java client](guides/java-client.md) | Generated types, DataSource ownership and build integration |
| [CRUD and queries](guides/crud-and-queries.md) | Writes, typed filters, pagination and SQL preview |
| [Relations](guides/relations.md) | Explicit includes, batched loading and unloaded values |
| [Transactions](guides/transactions.md) | Atomic work, savepoints, timeouts, locks and streams |
| [Migrations](guides/migrations.md) | Offline drafts/status, library preview/plans, risks and limits |

## Reference and help

- [CLI reference](reference/cli.md)
- [Java API reference](reference/java-api.md)
- [Troubleshooting](help/troubleshooting.md)
- [FAQ](help/faq.md)
- [Contributing](../CONTRIBUTING.md) and [Security](../SECURITY.md)

## Availability

| Surface | Current status |
|---|---|
| Schema validation, formatting and Java generation | Available from source |
| Generated CRUD/query client, relations and transactions | Available from source; API is preview |
| init, checks, dry-runs and offline migrate create | Available from source |
| Read-only PostgreSQL migration history/status | Available from source; no physical drift check |
| Limited PostgreSQL DDL parsing/preview | Available as an offline Java API; no CLI preview command |
| Migration risk plans and contextual SHA-256 digest | Available as an offline Java API; no execution capability |
| Migration execution, diff and physical drift detection | Not available in main |
| Automatic build plugins, Spring Boot starter and introspection | Future work |

The playground uses temporary manual bootstrap SQL. It is not a migration engine.

## For contributors

Engineering documents retain their existing paths. They include design proposals
and future contracts; use the guides above for the current user-facing API.

| Area | Entry point |
|---|---|
| Build and tests | [Development workflow](development/BUILD_AND_TEST.md) |
| Architecture | [Architecture](architecture/ARCHITECTURE.md) · [Module boundaries](architecture/MODULE_BOUNDARIES.md) |
| Decisions and proposals | [ADRs](adr/) · [RFCs](rfcs/) |
| Product direction | [Product specification](product/TUPREL_ORM_SPEC.md) · [Principles](product/PRODUCT_PRINCIPLES.md) |
| Planning | [Master plan](../plans/MASTER_PLAN.md) · [Phase 6 execution](../plans/PHASE_6_EXECUTION.md) |
| Quality and security | [Test strategy](testing/TEST_STRATEGY.md) · [Security requirements](security/SECURITY_REQUIREMENTS.md) |

Internal planning and decision documents are maintained in Portuguese. Public
guides and API examples are in English.
