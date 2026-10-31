[Documentation](../README.md) / Guides

# Relations

Relations are requested in the query and loaded in batches. Model getters never
open a connection. The [playground schema](../../examples/tuprel-playground/tuprel/schema.tuprel)
defines SalesOrder → Customer and SalesOrder → OrderItem → Product.

## Include related values

Given `TuprelClient` db and an existing UUID orderId:

```java
SalesOrder order = db.salesOrder().findById(orderId, query -> query
        .include(SalesOrderInclude.customer())
        .include(SalesOrderInclude.items(items -> items
                .include(OrderItemInclude.product()))))
        .orElseThrow();
Customer customer = order.customer().get();
List<OrderItem> items = order.items().get();
```

Import models from dev.tuprel.playground.generated.model and selectors from
dev.tuprel.playground.generated.include.

get() on an unloaded relation throws and identifies the include to request.
A loaded optional singular relation contains `Optional`; a list relation contains
a list, including an empty list for no matches. `isLoaded()` inspects the state
without running database work.

## Query behaviour

Includes run batch queries for requested paths rather than a statement per
parent row. They do not imply JOINs or change root pagination. Included lists
may filter/order, but do not support per-parent pagination. Singular relations
do not accept a separate filter/order.

Separate statements can observe concurrent changes outside a transaction. Use
[a transaction and suitable isolation](transactions.md) for a consistent snapshot.

## Write through foreign keys

Writes use scalar FK inputs: customerId, orderId and productId. No nested
create/connect/disconnect API or implicit cascade exists. Enforce FKs with
reviewed database DDL and group dependent writes in a transaction.

1:1, 1:N and N:N through an explicit join model are supported. Automatic
join-table creation and traversal shortcuts are not implemented. See
[RFC-003](../rfcs/RFC-003-relation-loading.md) for pairing/validation rules.
