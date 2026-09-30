package dev.tuprel.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuprel.it.generated.TuprelBytes;
import dev.tuprel.it.generated.TuprelClient;
import dev.tuprel.it.generated.client.CustomerClient;
import dev.tuprel.it.generated.create.CustomerCreate;
import dev.tuprel.it.generated.create.NoteCreate;
import dev.tuprel.it.generated.model.Customer;
import dev.tuprel.it.generated.model.Note;
import dev.tuprel.it.generated.model.Role;
import dev.tuprel.it.generated.order.CustomerOrder;
import dev.tuprel.it.generated.update.CustomerUpdate;
import dev.tuprel.it.generated.where.CustomerWhere;
import dev.tuprel.runtime.TuprelDatabase;
import dev.tuprel.runtime.TuprelDatabaseException;
import dev.tuprel.runtime.query.Condition;
import dev.tuprel.runtime.query.Cursor;
import dev.tuprel.runtime.query.CursorPage;
import dev.tuprel.runtime.query.Projection;
import dev.tuprel.runtime.query.Query;
import dev.tuprel.sql.RenderedSql;
import dev.tuprel.sql.SqlValue;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Generated client from src/integrationTest/tuprel/schema.tuprel against real PostgreSQL. */
class GeneratedQueryIntegrationTest {
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-trixie");
    private static final Instant CREATED = Instant.parse("2026-09-29T10:15:30.123456Z");
    private static PGSimpleDataSource dataSource;
    private static TuprelClient db;

    @BeforeAll
    static void startDatabase() throws SQLException {
        POSTGRES.start();
        dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        db = new TuprelClient(PostgresqlDatabase.using(dataSource));
        // Migrations are a later phase: the table mirrors the schema by hand, named exactly
        // like the model and its fields.
        execute("""
                CREATE TABLE "Customer" (
                    "id" UUID PRIMARY KEY,
                    "email" TEXT NOT NULL UNIQUE,
                    "name" TEXT NOT NULL,
                    "age" INTEGER,
                    "role" TEXT NOT NULL,
                    "balance" NUMERIC NOT NULL,
                    "active" BOOLEAN NOT NULL,
                    "createdAt" TIMESTAMPTZ NOT NULL,
                    "avatar" BYTEA
                );
                CREATE TABLE "Note" (
                    "id" BIGINT PRIMARY KEY,
                    "customerId" UUID NOT NULL REFERENCES "Customer" ("id"),
                    "body" TEXT NOT NULL
                );
                """);
    }

    @AfterAll
    static void stopDatabase() {
        POSTGRES.stop();
    }

    @BeforeEach
    void clearTables() throws SQLException {
        execute("TRUNCATE \"Note\", \"Customer\"");
    }

    @Test
    void createReadUpdateAndDeleteThroughTheGeneratedClient() {
        CustomerClient customers = db.customer();
        UUID id = UUID.randomUUID();
        Customer created = customers.create(CustomerCreate.builder()
                .id(id)
                .email("ana@example.com")
                .name("Ana")
                .age(null)
                .role(Role.ADMIN)
                .balance(new BigDecimal("10.50"))
                .active(true)
                .createdAt(CREATED)
                .avatar(new TuprelBytes(new byte[] {1, 2, 3}))
                .build());
        assertEquals(new Customer(id, "ana@example.com", "Ana", null, Role.ADMIN, new BigDecimal("10.50"),
                true, CREATED, new TuprelBytes(new byte[] {1, 2, 3})), created);
        assertEquals(Optional.of(created), customers.findById(id));

        Customer updated = customers.updateById(id, CustomerUpdate.builder()
                .age(41)
                .avatar(null)
                .role(Role.MEMBER)
                .build()).orElseThrow();
        assertEquals(41, updated.age());
        assertNull(updated.avatar());
        assertEquals(Role.MEMBER, updated.role());
        assertEquals("Ana", updated.name(), "Absent update fields are not written");
        assertEquals(Optional.of(updated), customers.findById(id));

        assertTrue(customers.updateById(UUID.randomUUID(), CustomerUpdate.builder().name("x").build()).isEmpty());
        assertTrue(customers.deleteById(id));
        assertFalse(customers.deleteById(id));
        assertTrue(customers.findById(id).isEmpty());
    }

    @Test
    void relationsStayExplicitAndConstraintFailuresKeepSqlState() {
        Customer owner = seed("owner@example.com", "Owner", 30, Role.ADMIN, "1", true);
        Note note = db.note().create(NoteCreate.builder().id(1L).customerId(owner.id()).body("hello").build());
        assertEquals(new Note(1L, owner.id(), "hello"), note);

        TuprelDatabaseException foreignKey = assertThrows(TuprelDatabaseException.class,
                () -> db.note().create(NoteCreate.builder().id(2L).customerId(UUID.randomUUID())
                        .body("orphan").build()));
        assertEquals("23503", foreignKey.sqlState());

        TuprelDatabaseException unique = assertThrows(TuprelDatabaseException.class,
                () -> seed("owner@example.com", "Copy", null, Role.MEMBER, "0", true));
        assertEquals("23505", unique.sqlState());
        assertFalse(unique.getMessage().contains("owner@example.com"));
    }

    @Test
    void filtersFollowTypedOperatorsAndSqlNullSemantics() {
        seed("a@example.com", "Ana", 17, Role.MEMBER, "5.00", true);
        seed("b@example.com", "Bruno", 30, Role.ADMIN, "100.5", false);
        seed("c@example.com", "Carla", 45, Role.MEMBER, "0", true);
        seed("d@example.com", "Duarte", null, Role.MEMBER, "-3", true);
        CustomerClient customers = db.customer();

        assertEquals(List.of("Bruno"), names(customers.findMany(q -> q.where(CustomerWhere.age().eq(30)))));
        assertEquals(List.of("Bruno", "Carla"),
                names(customers.findMany(q -> q.where(CustomerWhere.age().gte(18)).orderBy(CustomerOrder.name().asc()))));
        assertEquals(List.of("Ana"), names(customers.findMany(q -> q.where(CustomerWhere.age().lt(18)))));
        assertEquals(List.of("Ana", "Bruno"),
                names(customers.findMany(q -> q.where(CustomerWhere.age().between(17, 30))
                        .orderBy(CustomerOrder.name().asc()))));
        assertEquals(List.of("Ana", "Carla"), names(customers.findMany(q -> q
                .where(CustomerWhere.age().notEq(30)).orderBy(CustomerOrder.name().asc()))),
                "A NULL column never matches a comparison");
        assertEquals(List.of("Duarte"), names(customers.findMany(q -> q.where(CustomerWhere.age().isNull()))));
        assertEquals(List.of("Ana", "Bruno", "Carla"),
                names(customers.findMany(q -> q.where(CustomerWhere.age().isNotNull())
                        .orderBy(CustomerOrder.name().asc()))));
        assertEquals(List.of("Bruno"), names(customers.findMany(q -> q.where(CustomerWhere.role().eq(Role.ADMIN)))));
        assertEquals(List.of("Ana", "Carla"), names(customers.findMany(q -> q
                .where(CustomerWhere.email().in(List.of("a@example.com", "c@example.com", "zz@example.com")))
                .orderBy(CustomerOrder.name().asc()))));
        assertEquals(List.of("Bruno", "Duarte"), names(customers.findMany(q -> q
                .where(CustomerWhere.email().notIn(List.of("a@example.com", "c@example.com")))
                .orderBy(CustomerOrder.name().asc()))));
        assertEquals(List.of("Ana"), names(customers.findMany(q -> q
                .where(CustomerWhere.balance().eq(new BigDecimal("5"))))), "Decimals compare numerically");
        assertEquals(List.of("Bruno"), names(customers.findMany(q -> q.where(CustomerWhere.active().eq(false)))));
        assertEquals(List.of("Ana", "Carla", "Duarte"), names(customers.findMany(q -> q
                .where(CustomerWhere.createdAt().lte(CREATED).and(CustomerWhere.active().eq(true)))
                .orderBy(CustomerOrder.name().asc()))));

        Condition<Customer> adultAdminOrNull = Condition.anyOf(
                CustomerWhere.age().gte(18).and(CustomerWhere.role().eq(Role.ADMIN)),
                CustomerWhere.age().isNull());
        assertEquals(List.of("Bruno", "Duarte"), names(customers.findMany(q -> q.where(adultAdminOrNull)
                .orderBy(CustomerOrder.name().asc()))));
        assertEquals(List.of("Ana", "Carla"), names(customers.findMany(q -> q.where(adultAdminOrNull.not())
                .orderBy(CustomerOrder.name().asc()))));
        assertEquals(List.of("Carla"), names(customers.findMany(q -> q
                .where(CustomerWhere.age().gt(18))
                .where(CustomerWhere.role().eq(Role.MEMBER)))), "Repeated where combines by AND");
    }

    @Test
    void textPatternsAreLiteralAndCaseSensitive() {
        seed("pct@example.com", "50% off", 1, Role.MEMBER, "0", true);
        seed("five@example.com", "5000 off", 1, Role.MEMBER, "0", true);
        seed("under@example.com", "a_b", 1, Role.MEMBER, "0", true);
        seed("axb@example.com", "axb", 1, Role.MEMBER, "0", true);
        seed("bang@example.com", "wow!", 1, Role.MEMBER, "0", true);
        seed("slash@example.com", "back\\slash", 1, Role.MEMBER, "0", true);
        CustomerClient customers = db.customer();

        assertEquals(List.of("50% off"), names(customers.findMany(q -> q.where(CustomerWhere.name().contains("%")))));
        assertEquals(List.of("50% off"), names(customers.findMany(q -> q.where(CustomerWhere.name().startsWith("50%")))));
        assertEquals(List.of("a_b"), names(customers.findMany(q -> q.where(CustomerWhere.name().contains("_")))));
        assertEquals(List.of("wow!"), names(customers.findMany(q -> q.where(CustomerWhere.name().endsWith("!")))));
        assertEquals(List.of("back\\slash"),
                names(customers.findMany(q -> q.where(CustomerWhere.name().contains("\\")))));
        assertEquals(List.of("axb"), names(customers.findMany(q -> q.where(CustomerWhere.name().startsWith("ax")))));
        assertEquals(List.of(), names(customers.findMany(q -> q.where(CustomerWhere.name().startsWith("AX")))));
    }

    @Test
    void orderingPagingCountExistsAndProjectionRunAsSeparateExplicitQueries() {
        for (int index = 0; index < 5; index++) {
            seed("user" + index + "@example.com", "User " + index, 20 + index, Role.MEMBER, "0", true);
        }
        CustomerClient customers = db.customer();
        assertEquals(List.of("User 3", "User 2"), names(customers.findMany(q -> q
                .orderBy(CustomerOrder.age().desc()).skip(1).take(2))));
        assertEquals("User 0", customers.findFirst(q -> q.orderBy(CustomerOrder.age().asc())).orElseThrow().name());
        assertTrue(customers.findFirst(q -> q.where(CustomerWhere.age().gt(99))).isEmpty());
        assertEquals(5, customers.count());
        assertEquals(2, customers.count(q -> q.where(CustomerWhere.age().gte(23))));
        assertTrue(customers.exists(q -> q.where(CustomerWhere.name().eq("User 4"))));
        assertFalse(customers.exists(q -> q.where(CustomerWhere.name().eq("Nobody"))));
        assertThrows(IllegalArgumentException.class, () -> customers.count(q -> q.take(1)));

        List<String> summaries = customers.select(
                Projection.of(List.of(CustomerWhere.name(), CustomerWhere.age()),
                        row -> row.get(CustomerWhere.name()) + ":" + row.get(CustomerWhere.age())),
                q -> q.where(CustomerWhere.age().lt(22)).orderBy(CustomerOrder.age().asc()));
        assertEquals(List.of("User 0:20", "User 1:21"), summaries);
        assertThrows(TuprelDatabaseException.class, () -> customers.select(
                Projection.of(List.of(CustomerWhere.name()), row -> row.get(CustomerWhere.age()))));
    }

    @Test
    void cursorPaginationVisitsEveryRowOnceWithTiesAndConcurrentInserts() {
        List<String> expected = new ArrayList<>();
        for (int index = 0; index < 7; index++) {
            // Only two distinct names: the identifier tiebreaker must make the order total.
            String name = index % 2 == 0 ? "Even" : "Odd";
            seed("cursor" + index + "@example.com", name, index, Role.MEMBER, "0", true);
        }
        CustomerClient customers = db.customer();
        for (Customer customer : customers.findMany(q -> q.orderBy(CustomerOrder.name().desc(),
                CustomerOrder.id().asc()))) {
            expected.add(customer.email());
        }
        List<String> visited = new ArrayList<>();
        Optional<Cursor> cursor = Optional.empty();
        int pages = 0;
        do {
            Optional<Cursor> after = cursor.map(Cursor::encode).map(Cursor::decode);
            CursorPage<Customer> page = customers.findManyCursor(q -> {
                Query<Customer> ordered = q
                        .orderBy(CustomerOrder.name().desc(), CustomerOrder.id().asc()).take(3);
                return after.map(ordered::after).orElse(ordered);
            });
            page.items().forEach(customer -> visited.add(customer.email()));
            cursor = page.nextCursor();
            pages++;
            if (pages == 1) {
                // A row sorting before the current position does not shift later pages.
                seed("late@example.com", "Zed", 99, Role.MEMBER, "0", true);
            }
        } while (cursor.isPresent());
        assertEquals(expected, visited);
        assertEquals(3, pages);
        Set<String> unique = new HashSet<>(visited);
        assertEquals(visited.size(), unique.size());

        CursorPage<Customer> byId = customers.findManyCursor(q -> q.take(100));
        assertFalse(byId.hasNext());
        assertEquals(8, byId.items().size());
        assertThrows(IllegalArgumentException.class, () -> customers.findManyCursor(q -> q.take(2)
                .orderBy(CustomerOrder.name().asc())));
        assertThrows(IllegalArgumentException.class, () -> customers.findManyCursor(q -> q.take(2)
                .orderBy(CustomerOrder.age().asc(), CustomerOrder.id().asc())));
        assertThrows(IllegalArgumentException.class, () -> Cursor.decode("' OR 1=1 --"));
    }

    @Test
    void hostileInputThroughTheGeneratedApiIsOnlyEverBound() throws SQLException {
        String hostile = "Robert'); DROP TABLE \"Customer\";--";
        Customer created = seed("x'); DELETE FROM \"Customer\";--@example.com", hostile, 1, Role.MEMBER, "0", true);
        CustomerClient customers = db.customer();
        assertEquals(List.of(created), customers.findMany(q -> q.where(CustomerWhere.name().eq(hostile))));
        assertEquals(List.of(created), customers.findMany(q -> q.where(CustomerWhere.name().contains("'); DROP"))));
        assertEquals(List.of(created), customers.findMany(q -> q.where(CustomerWhere.name()
                .in(List.of(hostile, "' OR '1'='1")))));
        assertEquals(0, customers.count(q -> q.where(CustomerWhere.name().eq("' OR '1'='1"))));
        assertEquals(1, customers.count());
        assertEquals(1, countRows("Customer"), "The table survives every hostile value");

        RenderedSql preview = customers.preview(q -> q.where(CustomerWhere.name().eq(hostile))
                .orderBy(CustomerOrder.createdAt().desc()).take(10));
        assertEquals("SELECT \"id\", \"email\", \"name\", \"age\", \"role\", \"balance\", \"active\", "
                + "\"createdAt\", \"avatar\" FROM \"Customer\" WHERE \"name\" = ? "
                + "ORDER BY \"createdAt\" DESC LIMIT ?", preview.text());
        assertEquals(List.of(new SqlValue.Text(hostile), new SqlValue.Int32(10)), preview.binds());
        assertFalse(preview.toString().contains("DROP"));
    }

    /** Keeps the README "Generated client example" compiling and behaving as described. */
    @Test
    void readmeGeneratedClientExampleRuns() {
        seed("ana@example.com", "Ana", 30, Role.MEMBER, "0", true);
        seed("alex@example.com", "Alex", 12, Role.MEMBER, "0", true);
        seed("bea@example.com", "Bea", null, Role.MEMBER, "0", true);

        TuprelDatabase database = PostgresqlDatabase.using(dataSource);
        TuprelClient db = new TuprelClient(database);

        List<Customer> adults = db.customer().findMany(query -> query
                .where(CustomerWhere.age().gte(18).and(CustomerWhere.name().startsWith("A")))
                .orderBy(CustomerOrder.createdAt().desc())
                .take(20));

        long withoutAge = db.customer().count(query -> query.where(CustomerWhere.age().isNull()));

        RenderedSql preview = db.customer().preview(query -> query.where(CustomerWhere.name().eq("Ana")));

        assertEquals(List.of("Ana"), names(adults));
        assertEquals(1, withoutAge);
        assertTrue(preview.text().endsWith("FROM \"Customer\" WHERE \"name\" = ?"));
        assertEquals(List.of(new SqlValue.Text("Ana")), preview.binds());
    }

    private static Customer seed(String email, String name, Integer age, Role role, String balance, boolean active) {
        return db.customer().create(CustomerCreate.builder()
                .id(UUID.randomUUID())
                .email(email)
                .name(name)
                .age(age)
                .role(role)
                .balance(new BigDecimal(balance))
                .active(active)
                .createdAt(CREATED)
                .build());
    }

    private static List<String> names(List<Customer> customers) {
        return customers.stream().map(Customer::name).toList();
    }

    private static int countRows(String table) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM \"" + table + "\"")) {
            result.next();
            return result.getInt(1);
        }
    }

    private static void execute(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
