package dev.tuprel.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuprel.it.generated.TuprelClient;
import dev.tuprel.it.generated.create.CustomerCreate;
import dev.tuprel.it.generated.create.NoteCreate;
import dev.tuprel.it.generated.create.NoteTagCreate;
import dev.tuprel.it.generated.create.ProductCreate;
import dev.tuprel.it.generated.create.ProfileCreate;
import dev.tuprel.it.generated.create.TagCreate;
import dev.tuprel.it.generated.include.CustomerInclude;
import dev.tuprel.it.generated.include.NoteInclude;
import dev.tuprel.it.generated.include.NoteTagInclude;
import dev.tuprel.it.generated.model.Customer;
import dev.tuprel.it.generated.model.Note;
import dev.tuprel.it.generated.model.NoteTag;
import dev.tuprel.it.generated.model.Product;
import dev.tuprel.it.generated.model.Role;
import dev.tuprel.it.generated.order.CustomerOrder;
import dev.tuprel.it.generated.order.NoteOrder;
import dev.tuprel.it.generated.update.CustomerUpdate;
import dev.tuprel.it.generated.update.ProductUpdate;
import dev.tuprel.it.generated.where.CustomerWhere;
import dev.tuprel.it.generated.where.NoteWhere;
import dev.tuprel.it.generated.where.ProductWhere;
import dev.tuprel.runtime.IsolationLevel;
import dev.tuprel.runtime.TransactionOptions;
import dev.tuprel.runtime.TuprelDatabaseException;
import dev.tuprel.runtime.TuprelStream;
import dev.tuprel.runtime.query.OptimisticLockException;
import dev.tuprel.runtime.query.RowLock;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Relations, transactions, locks, streaming, batches and optimistic locking on real PostgreSQL. */
class RelationsAndTransactionsIntegrationTest {
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-trixie");
    private static final Instant CREATED = Instant.parse("2026-09-30T10:00:00Z");
    private static final AtomicInteger STATEMENTS = new AtomicInteger();
    private static PGSimpleDataSource dataSource;
    private static TuprelClient db;

    @BeforeAll
    static void startDatabase() throws SQLException {
        POSTGRES.start();
        dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        db = new TuprelClient(PostgresqlDatabase.using(counting(dataSource)));
        execute("""
                CREATE TABLE "Customer" (
                    "id" UUID PRIMARY KEY, "email" TEXT NOT NULL UNIQUE, "name" TEXT NOT NULL, "age" INTEGER,
                    "role" TEXT NOT NULL, "balance" NUMERIC NOT NULL, "active" BOOLEAN NOT NULL,
                    "createdAt" TIMESTAMPTZ NOT NULL, "avatar" BYTEA
                );
                CREATE TABLE "Profile" (
                    "id" BIGINT PRIMARY KEY, "customerId" UUID NOT NULL UNIQUE REFERENCES "Customer" ("id"),
                    "bio" TEXT NOT NULL
                );
                CREATE TABLE "Note" (
                    "id" BIGINT PRIMARY KEY, "customerId" UUID NOT NULL REFERENCES "Customer" ("id"),
                    "body" TEXT NOT NULL
                );
                CREATE TABLE "Tag" ("id" BIGINT PRIMARY KEY, "label" TEXT NOT NULL UNIQUE);
                CREATE TABLE "NoteTag" (
                    "id" BIGINT PRIMARY KEY, "noteId" BIGINT NOT NULL REFERENCES "Note" ("id"),
                    "tagId" BIGINT NOT NULL REFERENCES "Tag" ("id"), UNIQUE ("noteId", "tagId")
                );
                CREATE TABLE "Product" (
                    "id" BIGINT PRIMARY KEY, "name" TEXT NOT NULL, "stock" INTEGER NOT NULL,
                    "version" INTEGER NOT NULL DEFAULT 0
                );
                """);
    }

    @AfterAll
    static void stopDatabase() {
        POSTGRES.stop();
    }

    @BeforeEach
    void clearTables() throws SQLException {
        execute("TRUNCATE \"NoteTag\", \"Tag\", \"Note\", \"Profile\", \"Customer\", \"Product\"");
    }

    @Test
    void includesLoadOneToOneOneToManyAndManyToManyWithOneQueryPerRelationLevel() {
        Customer ana = customer("ana@example.com", "Ana");
        Customer bruno = customer("bruno@example.com", "Bruno");
        customer("carla@example.com", "Carla");
        db.profile().create(ProfileCreate.builder().id(1L).customerId(ana.id()).bio("writer").build());
        for (long id = 1; id <= 3; id++) {
            db.note().create(NoteCreate.builder().id(id).customerId(ana.id()).body("ana " + id).build());
        }
        db.note().create(NoteCreate.builder().id(4L).customerId(bruno.id()).body("bruno 4").build());
        db.tag().create(TagCreate.builder().id(1L).label("java").build());
        db.tag().create(TagCreate.builder().id(2L).label("sql").build());
        db.noteTag().create(NoteTagCreate.builder().id(1L).noteId(1L).tagId(1L).build());
        db.noteTag().create(NoteTagCreate.builder().id(2L).noteId(1L).tagId(2L).build());
        db.noteTag().create(NoteTagCreate.builder().id(3L).noteId(4L).tagId(2L).build());

        STATEMENTS.set(0);
        List<Customer> customers = db.customer().findMany(query -> query
                .orderBy(CustomerOrder.name().asc())
                .include(CustomerInclude.profile())
                .include(CustomerInclude.notes(notes -> notes
                        .where(NoteWhere.id().lte(3L).or(NoteWhere.id().eq(4L)))
                        .orderBy(NoteOrder.id().desc())
                        .include(NoteInclude.tags(tags -> tags.include(NoteTagInclude.tag()))))));
        assertEquals(5, STATEMENTS.get(), "customers, profiles, notes, note tags and tags: one query each");

        assertEquals(List.of("Ana", "Bruno", "Carla"), customers.stream().map(Customer::name).toList());
        Customer loadedAna = customers.getFirst();
        assertEquals("writer", loadedAna.profile().get().orElseThrow().bio());
        assertEquals(List.of(3L, 2L, 1L), loadedAna.notes().get().stream().map(Note::id).toList());
        Note first = loadedAna.notes().get().getLast();
        assertEquals(List.of("java", "sql"), first.tags().get().stream()
                .map(noteTag -> noteTag.tag().get().label()).toList());
        assertTrue(customers.get(1).profile().get().isEmpty());
        assertEquals(List.of("sql"), customers.get(1).notes().get().getFirst().tags().get().stream()
                .map(noteTag -> noteTag.tag().get().label()).toList());
        assertEquals(List.of(), customers.get(2).notes().get());
        assertFalse(first.customer().isLoaded(), "Only requested relations are loaded");
        IllegalStateException unloaded = assertThrows(IllegalStateException.class, () -> first.customer().get());
        assertEquals("Note.customer was not loaded. Add include(NoteInclude.customer()) to the query.",
                unloaded.getMessage());

        Note owned = db.note().findById(4L, query -> query.include(NoteInclude.customer())).orElseThrow();
        assertEquals("Bruno", owned.customer().get().name());
        List<NoteTag> links = db.noteTag().findMany(query -> query.include(NoteTagInclude.note(
                note -> note.include(NoteInclude.customer()))));
        assertTrue(links.stream().allMatch(link -> link.note().get().customer().isLoaded()));
    }

    @Test
    void includesStayBatchedAcrossManyParents() {
        List<CustomerCreate> inputs = new ArrayList<>();
        for (int index = 0; index < 1_200; index++) {
            inputs.add(CustomerCreate.builder().id(UUID.randomUUID()).email("bulk" + index + "@example.com")
                    .name("Bulk " + index).role(Role.MEMBER).balance(BigDecimal.ONE).active(true)
                    .createdAt(CREATED).build());
        }
        assertEquals(1_200, db.customer().createMany(inputs));
        STATEMENTS.set(0);
        List<Customer> loaded = db.customer().findMany(query -> query.include(CustomerInclude.notes()));
        assertEquals(1_200, loaded.size());
        assertEquals(4, STATEMENTS.get(), "1 root query and 3 key-batched relation queries, never one per row");
        assertTrue(loaded.stream().allMatch(customer -> customer.notes().get().isEmpty()));
    }

    @Test
    void transactionsCommitRollBackAndRollBackNestedBlocksToTheirSavepoint() {
        Customer committed = db.transaction(tx -> {
            Customer created = tx.customer().create(input("tx@example.com", "Tx"));
            tx.note().create(NoteCreate.builder().id(10L).customerId(created.id()).body("inside").build());
            return created;
        });
        assertTrue(db.customer().findById(committed.id()).isPresent());
        assertEquals(1, db.note().count());

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> db.transaction(tx -> {
            tx.customer().create(input("rolled@example.com", "Rolled"));
            throw new IllegalStateException("abort");
        }));
        assertEquals("abort", failure.getMessage());
        assertFalse(db.customer().exists(query -> query.where(CustomerWhere.email().eq("rolled@example.com"))));

        db.transaction(tx -> {
            tx.customer().create(input("outer@example.com", "Outer"));
            assertThrows(TuprelDatabaseException.class, () -> tx.transaction(inner -> {
                inner.customer().create(input("inner@example.com", "Inner"));
                return inner.customer().create(input("outer@example.com", "Duplicate"));
            }));
            tx.customer().create(input("after@example.com", "After"));
            return null;
        });
        assertEquals(List.of("After", "Outer", "Tx"), db.customer().findMany(query -> query
                .orderBy(CustomerOrder.name().asc())).stream()
                .map(Customer::name).toList(), "The failed savepoint block left no rows");

        TuprelDatabaseException constraint = assertThrows(TuprelDatabaseException.class, () -> db.transaction(tx -> {
            tx.customer().create(input("partial@example.com", "Partial"));
            return tx.note().create(NoteCreate.builder().id(11L).customerId(UUID.randomUUID()).body("x").build());
        }));
        assertEquals("23503", constraint.sqlState());
        assertFalse(db.customer().exists(query -> query.where(CustomerWhere.email().eq("partial@example.com"))));
    }

    @Test
    void transactionOptionsAreAppliedAndConnectionsAreRestored() {
        List<Long> repeatable = db.transaction(TransactionOptions.defaults()
                .withIsolation(IsolationLevel.REPEATABLE_READ), tx -> {
            long before = tx.customer().count();
            db.customer().create(input("concurrent@example.com", "Concurrent"));
            return List.of(before, tx.customer().count());
        });
        assertEquals(List.of(0L, 0L), repeatable, "A repeatable-read snapshot ignores a concurrent commit");
        List<Long> readCommitted = db.transaction(TransactionOptions.defaults()
                .withIsolation(IsolationLevel.READ_COMMITTED), tx -> {
            long before = tx.customer().count();
            db.customer().create(input("visible@example.com", "Visible"));
            return List.of(before, tx.customer().count());
        });
        assertEquals(List.of(1L, 2L), readCommitted);
        TuprelDatabaseException readOnly = assertThrows(TuprelDatabaseException.class, () -> db.transaction(
                TransactionOptions.defaults().withReadOnly(true), tx -> tx.customer().create(input("ro@example.com", "Ro"))));
        assertEquals("25006", readOnly.sqlState());
        assertEquals(2, db.customer().count(), "The read-only attempt wrote nothing and autocommit works again");
    }

    @Test
    void rowLocksBlockNoWaitFailsSkipLockedSkipsAndTimeoutsCancel() throws Exception {
        Product product = db.product().create(ProductCreate.builder().id(1L).name("Lamp").stock(5).build());
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> holder = executor.submit(() -> db.transaction(tx -> {
                Product row = tx.product().findById(product.id(), query -> query.lock(RowLock.FOR_UPDATE)).orElseThrow();
                locked.countDown();
                await(release);
                return tx.product().updateById(row.id(), row.version(), ProductUpdate.builder()
                        .stock(row.stock() - 1).build()).orElseThrow().stock();
            }));
            assertTrue(locked.await(10, TimeUnit.SECONDS));

            TuprelDatabaseException noWait = assertThrows(TuprelDatabaseException.class, () -> db.transaction(tx ->
                    tx.product().findMany(query -> query.lock(RowLock.FOR_UPDATE_NOWAIT))));
            assertEquals("55P03", noWait.sqlState());
            assertEquals(List.of(), db.transaction(tx -> tx.product().findMany(query ->
                    query.lock(RowLock.FOR_UPDATE_SKIP_LOCKED))));
            long started = System.nanoTime();
            TuprelDatabaseException timeout = assertThrows(TuprelDatabaseException.class, () -> db.transaction(tx ->
                    tx.product().findMany(query -> query.lock(RowLock.FOR_UPDATE).timeout(Duration.ofSeconds(1)))));
            assertEquals("57014", timeout.sqlState());
            assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(10)) < 0);
            TuprelDatabaseException deadline = assertThrows(TuprelDatabaseException.class, () -> db.transaction(
                    TransactionOptions.defaults().withTimeout(Duration.ofSeconds(1)),
                    tx -> tx.product().findMany(query -> query.lock(RowLock.FOR_UPDATE))));
            assertEquals("57014", deadline.sqlState());

            release.countDown();
            assertEquals(4, holder.get(10, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
        assertThrows(IllegalStateException.class, () -> db.product().findMany(query -> query.lock(RowLock.FOR_SHARE)));
    }

    @Test
    void optimisticLockingDetectsConcurrentUpdatesWithoutWriting() {
        Product created = db.product().create(ProductCreate.builder().id(7L).name("Desk").stock(10).build());
        assertEquals(0, created.version(), "The database default provides the first version");
        Product first = db.product().findById(7L).orElseThrow();
        Product second = db.product().findById(7L).orElseThrow();

        Product updated = db.product().updateById(7L, first.version(), ProductUpdate.builder().stock(9).build())
                .orElseThrow();
        assertEquals(1, updated.version());
        OptimisticLockException conflict = assertThrows(OptimisticLockException.class, () -> db.product()
                .updateById(7L, second.version(), ProductUpdate.builder().stock(1).build()));
        assertEquals(0, conflict.expectedVersion());
        assertEquals(9, db.product().findById(7L).orElseThrow().stock(), "The stale update wrote nothing");
        assertTrue(db.product().updateById(99L, 0, ProductUpdate.builder().stock(1).build()).isEmpty());

        assertEquals(1, db.product().updateMany(ProductWhere.stock().gt(0), ProductUpdate.builder().name("Desk v2")
                .build()));
        assertEquals(2, db.product().findById(7L).orElseThrow().version(), "Bulk updates bump the version");
    }

    @Test
    void concurrentOptimisticUpdatesLetExactlyOneWriterWin() throws Exception {
        db.product().create(ProductCreate.builder().id(8L).name("Chair").stock(100).build());
        int writers = 8;
        ExecutorService executor = Executors.newFixedThreadPool(writers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        try {
            for (int index = 0; index < writers; index++) {
                results.add(executor.submit(() -> {
                    await(start);
                    try {
                        db.product().updateById(8L, 0, ProductUpdate.builder().stock(1).build());
                        return true;
                    } catch (OptimisticLockException conflict) {
                        return false;
                    }
                }));
            }
            start.countDown();
            int winners = 0;
            for (Future<Boolean> result : results) {
                winners += result.get(30, TimeUnit.SECONDS) ? 1 : 0;
            }
            assertEquals(1, winners);
        } finally {
            executor.shutdownNow();
        }
        assertEquals(1, db.product().findById(8L).orElseThrow().version());
    }

    @Test
    void streamsReadInBatchesInsideTransactionsOnly() {
        List<ProductCreate> inputs = new ArrayList<>();
        for (long id = 1; id <= 250; id++) {
            inputs.add(ProductCreate.builder().id(id).name("P" + id).stock((int) id).build());
        }
        assertEquals(250, db.product().createMany(inputs));
        assertThrows(IllegalStateException.class, () -> db.product().stream(query -> query));
        long total = db.transaction(tx -> {
            long sum = 0;
            try (TuprelStream<Product> products = tx.product().stream(query -> query
                    .where(ProductWhere.stock().gt(0)).fetchSize(40))) {
                for (Product product : products) {
                    sum += product.stock();
                }
            }
            return sum;
        });
        assertEquals(250L * 251 / 2, total);
    }

    @Test
    void batchesAreAtomicAndBulkWritesRequireConditions() {
        List<ProductCreate> duplicate = List.of(
                ProductCreate.builder().id(1L).name("A").stock(1).build(),
                ProductCreate.builder().id(1L).name("B").stock(2).version(5).build());
        TuprelDatabaseException failure = assertThrows(TuprelDatabaseException.class,
                () -> db.product().createMany(duplicate));
        assertEquals("23505", failure.sqlState());
        assertEquals(0, db.product().count(), "A single-statement batch stores no row when one fails");

        db.product().createMany(List.of(
                ProductCreate.builder().id(1L).name("A").stock(0).build(),
                ProductCreate.builder().id(2L).name("B").stock(3).version(5).build(),
                ProductCreate.builder().id(3L).name("C").stock(0).build()));
        assertEquals(5, db.product().findById(2L).orElseThrow().version(), "Absent fields take the default");
        assertEquals(0, db.product().findById(1L).orElseThrow().version());
        assertEquals(2, db.product().deleteMany(ProductWhere.stock().eq(0)));
        assertEquals(List.of(2L), db.product().findMany().stream().map(Product::id).toList());
        assertEquals(1, db.customer().createMany(List.of(input("one@example.com", "One"))));
        assertEquals(1, db.customer().updateMany(CustomerWhere.email().eq("one@example.com"),
                CustomerUpdate.builder().age(30).build()));
    }

    /** Keeps the README relations and transactions example compiling and behaving as described. */
    @Test
    void readmeRelationsAndTransactionsExampleRuns() {
        Customer owner = customer("readme@example.com", "Readme");
        db.note().create(NoteCreate.builder().id(1L).customerId(owner.id()).body("first").build());
        db.note().create(NoteCreate.builder().id(2L).customerId(owner.id()).body("second").build());
        db.product().create(ProductCreate.builder().id(1L).name("Lamp").stock(3).build());

        List<Customer> customers = db.customer().findMany(query -> query
                .include(CustomerInclude.notes(notes -> notes.orderBy(NoteOrder.id().desc()))));
        List<Note> notes = customers.getFirst().notes().get();

        Product updated = db.transaction(tx -> {
            Product product = tx.product().findById(1L, query -> query.lock(RowLock.FOR_UPDATE)).orElseThrow();
            return tx.product().updateById(product.id(), product.version(),
                    ProductUpdate.builder().stock(product.stock() - 1).build()).orElseThrow();
        });

        assertEquals(List.of(2L, 1L), notes.stream().map(Note::id).toList());
        assertEquals(2, updated.stock());
        assertEquals(1, updated.version());
    }

    private static CustomerCreate input(String email, String name) {
        return CustomerCreate.builder().id(UUID.randomUUID()).email(email).name(name).role(Role.MEMBER)
                .balance(BigDecimal.TEN).active(true).createdAt(CREATED).build();
    }

    private static Customer customer(String email, String name) {
        return db.customer().create(input(email, name));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for the other transaction");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private static DataSource counting(DataSource delegate) {
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class},
                (proxy, method, arguments) -> {
                    Object result = invoke(method, delegate, arguments);
                    if (result instanceof Connection connection) {
                        return Proxy.newProxyInstance(Connection.class.getClassLoader(),
                                new Class<?>[] {Connection.class}, (connectionProxy, connectionMethod, connectionArguments) -> {
                                    if (connectionMethod.getName().equals("prepareStatement")) {
                                        STATEMENTS.incrementAndGet();
                                    }
                                    return invoke(connectionMethod, connection, connectionArguments);
                                });
                    }
                    return result;
                });
    }

    private static Object invoke(Method method, Object target, Object[] arguments) throws Throwable {
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException exception) {
            throw exception.getCause();
        }
    }

    private static void execute(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
