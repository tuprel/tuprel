[Documentation](../README.md) / Guides

# CRUD and queries

These fragments use TuprelClient db from the [client guide](java-client.md) and
the [playground schema](../../examples/tuprel-playground/tuprel/schema.tuprel).
Generated types live under `dev.tuprel.playground.generated`: model, create,
update, where and order. JDK types include UUID, List, `Optional` and BigDecimal.

## Create and read

```java
Product product = db.product().create(ProductCreate.builder()
        .id(UUID.randomUUID())
        .name("Keyboard")
        .price(new BigDecimal("49.90"))
        .stock(10)
        .build());
Optional<Product> found = db.product().findById(product.id());
```

Builders reject missing required create values before SQL is sent. `findById`
and `findFirst` return `Optional`; `findMany` returns a list.

## Filter, order and page

```java
List<Product> page = db.product().findMany(query -> query
        .where(ProductWhere.stock().gt(0)
                .and(ProductWhere.name().startsWith("Key")))
        .orderBy(ProductOrder.price().asc(), ProductOrder.id().asc())
        .skip(0)
        .take(20));
long available = db.product().count(query -> query.where(ProductWhere.stock().gt(0)));
boolean any = db.product().exists(query -> query.where(ProductWhere.stock().gt(0)));
```

Conditions belong to one model. Text operators treat % and _ as literal input,
not a raw LIKE pattern. Comparisons do not match NULL; use isNull()/isNotNull().
Empty IN lists are rejected. Use an explicit total order for consistent pages.

Cursor pagination requires take, cannot combine with skip, and defaults to ID
ascending. A custom order must end with the ID and cannot contain nullable keys.
`CursorPage` and Cursor are in dev.tuprel.runtime.query.

```java
CursorPage<Product> first = db.product().findManyCursor(query -> query.take(20));
Optional<Cursor> next = first.nextCursor();
```

Request a following page only when nextCursor is present, with the same filter
and ordering. Cursors are not offsets or transferable between models.

## Update and delete

Given an existing UUID productId:

```java
Product current = db.product().findById(productId).orElseThrow();
Optional<Product> updated = db.product().updateById(current.id(), current.version(),
        ProductUpdate.builder().stock(current.stock() + 1).build());
```

Product has `@version`: the expected version is checked and incremented atomically.
A stale version raises `OptimisticLockException` when the row still exists;
a missing row returns `Optional.empty()`. Models without `@version` expose the
overload without expectedVersion.

`deleteById(id)` returns whether a row was deleted. `updateMany(condition, input)`
and `deleteMany(condition)` require explicit conditions and return row counts.
`createMany` accepts non-empty compatible batches. No automatic graph writes or cascades.
Batch updates increment version fields but do not compare an expected version per row.

## Inspect SQL

```java
RenderedSql preview = db.product().preview(query -> query
        .where(ProductWhere.name().eq("Keyboard")));
String sql = preview.text();
```

Values stay separate bind parameters. Preview does not execute SQL; avoid
logging binds. It describes the root SELECT, not included-relation statements
or a PostgreSQL execution plan.

Typed projections use select(Projection, query); consult
[Projection](../../tuprel-runtime/src/main/java/dev/tuprel/runtime/query/Projection.java)
and [RFC-004](../rfcs/RFC-004-type-safe-query-api.md) for exact signatures.
Aggregations beyond count and upsert are not implemented.
