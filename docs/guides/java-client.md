[Documentation](../README.md) / Guides

# Java client guide

The generator produces immutable model values, create/update inputs, query fields,
ordering, includes and a TuprelClient. Generate these during your build; keep
application code separate from generator-owned files.

## A working consumer project

Use the [playground](../../examples/tuprel-playground/README.md) as the reference
integration. Its [build](../../examples/tuprel-playground/build.gradle.kts):

1. Resolves Tuprel from the checkout using a Gradle composite build.
2. Runs CLI validation and generation before compilation.
3. Adds `build/generated/sources/tuprel/main` to the Java source set.
4. Includes the PostgreSQL adapter and a PostgreSQL JDBC driver at runtime.

There is no published dependency to paste into a pom.xml or automatic build plugin.
The CLI does not select an output directory by detecting Maven or Gradle.

## Construct a client

For the playground schema, the relevant imports are:

```java
import dev.tuprel.playground.generated.TuprelClient;
import dev.tuprel.playground.generated.model.Product;
import dev.tuprel.playground.generated.order.ProductOrder;
import dev.tuprel.playground.generated.where.ProductWhere;
import dev.tuprel.postgresql.PostgresqlDatabase;
import dev.tuprel.sql.RenderedSql;
import java.util.List;
import javax.sql.DataSource;
```

Given your application's `DataSource dataSource`:

```java
TuprelClient db = new TuprelClient(PostgresqlDatabase.using(dataSource));
List<Product> products = db.product().findMany(query -> query
        .where(ProductWhere.stock().gt(0))
        .orderBy(ProductOrder.price().asc())
        .take(20));
```

The `DataSource` must already select the intended database and schema/search path.
Tuprel does not create tables, manage a pool or close your DataSource. The
playground .env loader is test/example support, not production configuration.

## Lifecycle and errors

Outside a transaction, an operation owns and closes its JDBC connection,
statements and result sets. Included relations add explicit batch queries.
There is no implicit transaction around several calls. Use
[transactions](transactions.md) when calls must share one connection and commit.

Values do not track edits. Construct an update input to persist a change.
Nullable scalar accessors can return null; relation accessors return `Relation`,
and only optional singular relations contain Optional.

`TuprelDatabaseException` identifies a failure phase and SQLState. Driver causes
may contain sensitive data: avoid logging raw exception chains, connection
properties or binds. Handle optimistic-lock conflicts explicitly.

Continue with [CRUD and queries](crud-and-queries.md), [relations](relations.md)
or the [Java API reference](../reference/java-api.md).
