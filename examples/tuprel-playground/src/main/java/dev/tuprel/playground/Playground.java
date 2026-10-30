package dev.tuprel.playground;

import dev.tuprel.playground.generated.TuprelClient;
import dev.tuprel.playground.generated.create.CustomerCreate;
import dev.tuprel.playground.generated.create.OrderItemCreate;
import dev.tuprel.playground.generated.create.ProductCreate;
import dev.tuprel.playground.generated.create.SalesOrderCreate;
import dev.tuprel.playground.generated.include.OrderItemInclude;
import dev.tuprel.playground.generated.include.SalesOrderInclude;
import dev.tuprel.playground.generated.model.Customer;
import dev.tuprel.playground.generated.model.OrderItem;
import dev.tuprel.playground.generated.model.Product;
import dev.tuprel.playground.generated.model.SalesOrder;
import dev.tuprel.playground.generated.order.ProductOrder;
import dev.tuprel.playground.generated.update.ProductUpdate;
import dev.tuprel.playground.generated.where.ProductWhere;
import dev.tuprel.postgresql.PostgresqlDatabase;
import dev.tuprel.runtime.TuprelDatabaseException;
import dev.tuprel.runtime.query.CursorPage;
import dev.tuprel.runtime.query.OptimisticLockException;
import dev.tuprel.sql.RenderedSql;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.postgresql.ds.PGSimpleDataSource;

/** Executable consumer application using only the APIs implemented in PR #11. */
public final class Playground {
    private Playground() { }

    public static void main(String[] arguments) {
        try {
            run();
        } catch (Exception failure) {
            // Driver exceptions may contain credentials or input data. Keep diagnostics bounded.
            String state = failure instanceof TuprelDatabaseException databaseFailure ? databaseFailure.sqlState()
                    : failure instanceof SQLException sqlFailure ? sqlFailure.getSQLState() : "n/a";
            System.err.println("Playground failed (" + failure.getClass().getSimpleName() + ", SQLState=" + state
                    + "). Check the three TUPREL_TEST_* variables, local service and dedicated database."
                    + " Details are available in the IntelliJ debugger; credentials are never printed.");
            System.exit(1);
        }
    }

    private static void run() throws Exception {
        PGSimpleDataSource source = LocalDatabase.connect();
        LocalDatabase.bootstrap(source);
        TuprelClient db = new TuprelClient(PostgresqlDatabase.using(source));
        UUID customerId = UUID.randomUUID();
        UUID keyboardId = UUID.randomUUID();
        UUID mouseId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        String email = "customer-" + customerId + "@example.invalid";

        db.transaction(tx -> {
            Customer customer = tx.customer().create(CustomerCreate.builder()
                    .id(customerId).email(email).name("Cliente de demonstração").build());
            require(customer.id().equals(customerId), "Customer insert");
            long products = tx.product().createMany(List.of(
                    product(keyboardId, "Teclado", "49.90"), product(mouseId, "Rato", "19.90")));
            require(products == 2, "Product batch");
            tx.salesOrder().create(order(orderId, customerId));
            tx.orderItem().createMany(List.of(item(orderId, keyboardId, 1, "49.90"),
                    item(orderId, mouseId, 2, "19.90")));
            return null;
        });

        SalesOrder loaded = db.salesOrder().findById(orderId, query -> query
                .include(SalesOrderInclude.customer())
                .include(SalesOrderInclude.items(items -> items.include(OrderItemInclude.product())))).orElseThrow();
        require(loaded.items().get().size() == 2, "Nested include");
        System.out.println("Encomenda " + loaded.id() + " — " + loaded.customer().get().name());
        for (OrderItem item : loaded.items().get()) {
            System.out.println("  " + item.quantity() + " x " + item.product().get().name() + " @ " + item.unitPrice());
        }

        List<Product> page = db.product().findMany(query -> query
                .where(ProductWhere.id().in(List.of(keyboardId, mouseId)))
                .orderBy(ProductOrder.price().desc(), ProductOrder.id().asc()).skip(0).take(1));
        require(page.size() == 1 && page.getFirst().id().equals(keyboardId), "Filter, ordering and offset page");
        CursorPage<Product> first = db.product().findManyCursor(query -> query
                .where(ProductWhere.id().in(List.of(keyboardId, mouseId))).take(1));
        CursorPage<Product> second = db.product().findManyCursor(query -> query
                .where(ProductWhere.id().in(List.of(keyboardId, mouseId))).take(1)
                .after(first.nextCursor().orElseThrow()));
        require(second.items().size() == 1 && !first.items().getFirst().id().equals(second.items().getFirst().id()),
                "Cursor pagination");
        RenderedSql preview = db.product().preview(query -> query.where(ProductWhere.id().eq(keyboardId)));
        System.out.println("SQL parametrizado: " + preview.text());

        Product before = db.product().findById(keyboardId).orElseThrow();
        Product updated = db.product().updateById(keyboardId, before.version(),
                ProductUpdate.builder().stock(9).build()).orElseThrow();
        require(updated.stock() == 9 && updated.version() == before.version() + 1, "Versioned update");
        try {
            db.product().updateById(keyboardId, before.version(), ProductUpdate.builder().stock(0).build());
            throw new IllegalStateException("Expected optimistic lock conflict");
        } catch (OptimisticLockException expected) {
            System.out.println("Conflito de versão detectado.");
        }

        UUID rolledBackId = UUID.randomUUID();
        try {
            db.transaction(tx -> {
                tx.salesOrder().create(order(rolledBackId, customerId));
                throw new CancelOrder();
            });
        } catch (CancelOrder expected) {
            require(db.salesOrder().findById(rolledBackId).isEmpty(), "Transaction rollback");
        }

        UUID discardedId = UUID.randomUUID();
        UUID keptId = UUID.randomUUID();
        db.transaction(tx -> {
            try {
                tx.transaction(inner -> {
                    inner.product().create(product(discardedId, "Descartado", "1.00"));
                    throw new CancelOrder();
                });
            } catch (CancelOrder expected) {
                require(tx.product().findById(discardedId).isEmpty(), "Savepoint rollback");
            }
            tx.product().create(product(keptId, "Temporário", "1.00"));
            return null;
        });
        require(db.product().deleteById(keptId) && db.product().findById(keptId).isEmpty(), "Delete");

        expectSqlState("23505", () -> db.customer().create(CustomerCreate.builder()
                .id(UUID.randomUUID()).email(email).name("Duplicado").build()));
        expectSqlState("23503", () -> db.salesOrder().create(order(UUID.randomUUID(), UUID.randomUUID())));
        expectSqlState("23514", () -> db.orderItem().create(item(orderId, keyboardId, -1, "49.90")));
        try {
            CustomerCreate.builder().id(UUID.randomUUID()).build();
            throw new IllegalStateException("Missing required field was accepted");
        } catch (IllegalStateException expected) {
            require("Required create field missing: email".equals(expected.getMessage()), "Required input validation");
            System.out.println("Campo obrigatório rejeitado pelo input gerado antes de executar SQL.");
        }

        System.out.println("OK: CRUD, filtros, paginação, relações, batch, commit, rollback, savepoint e erros.");
        System.out.println("Dados conservados em tuprel_devtest, schema tuprel_playground. Customer id: " + customerId);
    }

    private static ProductCreate product(UUID id, String name, String price) {
        return ProductCreate.builder().id(id).name(name).price(new BigDecimal(price)).stock(10).build();
    }

    private static SalesOrderCreate order(UUID id, UUID customerId) {
        return SalesOrderCreate.builder().id(id).customerId(customerId).status("NEW").createdAt(Instant.now()).build();
    }

    private static OrderItemCreate item(UUID orderId, UUID productId, int quantity, String price) {
        return OrderItemCreate.builder().id(UUID.randomUUID()).orderId(orderId).productId(productId)
                .quantity(quantity).unitPrice(new BigDecimal(price)).build();
    }

    private static void expectSqlState(String state, Runnable operation) {
        try {
            operation.run();
            throw new IllegalStateException("Expected SQLState " + state);
        } catch (TuprelDatabaseException expected) {
            require(state.equals(expected.sqlState()), "Expected SQLState " + state);
            System.out.println("Erro esperado confirmado: SQLState " + state);
        }
    }

    private static void require(boolean condition, String description) {
        if (!condition) {
            throw new IllegalStateException(description + " failed");
        }
    }

    private static final class CancelOrder extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
