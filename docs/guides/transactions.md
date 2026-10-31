[Documentation](../README.md) / Guides

# Transactions

Group operations explicitly when they must commit together. These fragments use
the [playground client](java-client.md). The transaction client shares one JDBC
connection and is confined to its callback/thread. Do not retain or share it.

## Commit and rollback

Given an existing UUID productId:

```java
Product changed = db.transaction(tx -> {
    Product current = tx.product().findById(productId).orElseThrow();
    return tx.product().updateById(current.id(), current.version(),
            ProductUpdate.builder().stock(current.stock() + 1).build()).orElseThrow();
});
```

Success commits; an exception rolls back. SQL failures may abort the transaction:
do not catch an error and assume the same scope can continue. Connection/commit
failures can leave the outcome uncertain. Reconcile before deciding to retry;
Tuprel does not automatically replay the callback.

## Savepoints

tx.transaction(inner -> ...) creates a savepoint, not an independent transaction.
A nested failure rolls back that block and rethrows. The outer callback can
catch it and continue only after successful savepoint rollback. Use inner in
the nested block. Non-default nested transaction options are rejected.

The [playground](../../examples/tuprel-playground/src/main/java/dev/tuprel/playground/Playground.java)
contains executable commit, rollback and savepoint examples.

## Isolation, read-only mode and deadlines

```java
TransactionOptions options = TransactionOptions.defaults()
        .withIsolation(IsolationLevel.REPEATABLE_READ)
        .withReadOnly(true)
        .withTimeout(Duration.ofSeconds(10));
List<Product> products = db.transaction(options, tx -> tx.product().findMany());
```

Import `TransactionOptions` and IsolationLevel from `dev.tuprel.runtime`, Duration
from java.time. Defaults preserve the connection's isolation. A timeout limits
database statements within the callback, not arbitrary application computation.

## Locks and streams

RowLock.FOR_UPDATE is available inside transactions; import RowLock from
dev.tuprel.runtime.query. Conflicts/timeouts are application decisions, not retries.
Streams require a transaction and must be closed:

```java
db.transaction(tx -> {
    try (TuprelStream<Product> products = tx.product().stream(query -> query
            .orderBy(ProductOrder.id().asc()))) {
        for (Product product : products) {
            UUID id = product.id();
        }
    }
    return null;
});
```

Import `TuprelStream` from dev.tuprel.runtime. A stream is forward-only and
single-use; do not return it from the callback. `StatementOptions` also supports
query timeouts/fetch size. See [RFC-005](../rfcs/RFC-005-transactions-concurrency.md)
for exact lifecycle and failure contracts.
