[Documentation](../README.md) / Guides

# Schema guide

`schema.tuprel` describes models and generated Java types. Validation and
generation do not create or compare database tables.

## Define a model

This complete small schema can be validated and generated offline:

```tuprel
generator java {
    package = "dev.example.catalog"
}

model Product {
    id UUID @id
    name String
    price Decimal
    stock Int
    version Int @version @default(0)
}
```

The [playground schema](../../examples/tuprel-playground/tuprel/schema.tuprel)
extends this model with relations. The Java guides use that playground package,
`dev.tuprel.playground.generated`.

## Types and cardinality

| Schema type | Java value |
|---|---|
| String | String |
| Boolean | boolean / nullable Boolean |
| Short, Int, Long | short, int, long / nullable boxed types |
| Float, Double, Decimal | float, double, BigDecimal / nullable boxed or reference types |
| UUID | java.util.UUID |
| Bytes | Generated TuprelBytes wrapper, with defensive byte-array copies |
| Instant, LocalDateTime, LocalDate, LocalTime | Corresponding java.time type |
| Enum | Generated Java enum; current PostgreSQL storage uses text |

`String?` is nullable: its generated accessor can return null, not `Optional`.
`OrderItem[]` is a relation collection. Scalar lists and `Json` are represented
by the frontend/model generator, but a model containing them does not receive
an operational client in the current subset. They do not imply PostgreSQL
arrays or JSONB runtime support.

## IDs, defaults and constraints

- One field carries `@id`; composite primary keys are not supported.
- `@unique`, `@@unique([fieldA, fieldB])` and `@@index([field])` describe intent.
- Supported defaults include literals, enum members, uuid(), now() and
  autoincrement() where valid for the field's type. `@version` is an Int or Long field.
- Defaults are metadata: generated inputs do not execute uuid()/now(). An omitted
  defaulted field is left for database-side behaviour. Install the matching
  database defaults and constraints in your reviewed DDL.

Names are case-sensitive ASCII identifiers. Physical tables/columns use schema
names. Mapping annotations, custom native type annotations and automatic naming
strategies are not implemented. SQL identifiers are quoted by the adapter.

## Relations and connections

Relations pair scalar FK fields with explicit `@relation(fields: [...],
references: [...])` references. Follow the [relations guide](relations.md).
There are no implicit cascades or nested writes.

An optional datasource declaration uses `provider = "postgresql"` and `url = env("DATABASE_URL")`.
Validation does not read the variable. The generated client does not configure
a connection from it: your application supplies a DataSource.

The [CLI reference](../reference/cli.md) covers validate/format/generate.
Exact grammar and mapping contracts are in [RFC-001](../rfcs/RFC-001-schema-language.md)
and [RFC-002](../rfcs/RFC-002-java-client-api.md).
