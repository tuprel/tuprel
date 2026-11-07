package example.sales;

import dev.tuprel.postgresql.PostgresqlDatabase;
import example.sales.generated.TuprelClient;
import example.sales.generated.create.ProductCreate;
import example.sales.generated.create.PurchaseCreate;
import example.sales.generated.include.PurchaseInclude;
import example.sales.generated.model.Product;
import example.sales.generated.model.Purchase;
import example.sales.generated.order.ProductOrder;
import example.sales.generated.update.ProductUpdate;
import example.sales.generated.where.ProductWhere;
import java.io.DataInputStream;
import java.util.List;
import org.postgresql.ds.PGSimpleDataSource;

/** Runtime smoke test for an independently compiled client in an isolated database fixture. */
public final class Consumer {
    private Consumer() { }

    public static void main(String[] arguments) {
        try (DataInputStream settings = new DataInputStream(System.in)) {
            PGSimpleDataSource dataSource = new PGSimpleDataSource();
            dataSource.setURL(settings.readUTF());
            dataSource.setUser(settings.readUTF());
            dataSource.setPassword(settings.readUTF());
            String schema = settings.readUTF();
            if (!schema.matches("tuprel_it_[a-f0-9]{32}(?:_c[0-9]+)?")) {
                throw new IllegalArgumentException("Expected an isolated consumer fixture");
            }
            dataSource.setCurrentSchema(schema);
            dataSource.setConnectTimeout(5);
            run(new TuprelClient(PostgresqlDatabase.using(dataSource)));
            System.out.println("Staged Java consumer passed");
        } catch (Exception failure) {
            System.err.println("Staged Java consumer failed. Inspect the fixture and installed libraries.");
            System.exit(1);
        }
    }

    private static void run(TuprelClient db) {
        db.transaction(tx -> {
            tx.product().create(product(1, "Keyboard", 3));
            tx.product().create(product(2, "Mouse", 2));
            tx.purchase().create(PurchaseCreate.builder().id(1).productId(1).quantity(2).build());
            return null;
        });
        List<Product> page = db.product().findMany(query -> query.where(ProductWhere.stock().gte(2))
                .orderBy(ProductOrder.stock().desc(), ProductOrder.id().asc()).take(1));
        require(page.size() == 1 && page.getFirst().id() == 1);
        Purchase purchase = db.purchase().findById(1, query -> query.include(PurchaseInclude.product())).orElseThrow();
        require(purchase.product().get().name().equals("Keyboard"));
        require(db.product().updateById(1, ProductUpdate.builder().stock(1).build()).orElseThrow().stock() == 1);
        require(db.product().deleteById(2));
        try {
            db.transaction(tx -> {
                tx.product().create(product(9, "Cancelled", 0));
                throw new CancelPurchase();
            });
            throw new IllegalStateException("Transaction completed without the cancellation");
        } catch (CancelPurchase expected) {
            require(db.product().findById(9).isEmpty());
        }
    }

    private static ProductCreate product(int id, String name, int stock) {
        return ProductCreate.builder().id(id).name(name).stock(stock).build();
    }

    private static void require(boolean condition) {
        if (!condition) { throw new IllegalStateException("Unexpected staged consumer result"); }
    }

    private static final class CancelPurchase extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
