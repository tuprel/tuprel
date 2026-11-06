package dev.tuprel.migrate.postgresql;

import static dev.tuprel.migrate.postgresql.CatalogObservation.Diagnostic;
import static dev.tuprel.migrate.postgresql.CatalogObservation.State;
import static org.junit.jupiter.api.Assertions.*;

import dev.tuprel.migrate.postgresql.ddl.DdlStatement;
import dev.tuprel.migrate.postgresql.diff.SchemaSnapshot;
import dev.tuprel.sql.SqlIdentifier;
import dev.tuprel.testsupport.TestPostgresql;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

class CatalogObservationIntegrationTest {
    private static TestPostgresql database;
    private static PGSimpleDataSource source;
    private static SqlIdentifier schema;
    private static MigrationDatabaseSettings settings;
    private static PostgresqlCatalogObserver observer;

    @BeforeAll static void open() throws Exception {
        database = TestPostgresql.open();
        source = database.dataSource();
        schema = new SqlIdentifier(source.getCurrentSchema());
        settings = settings(source.getUser(), source.getPassword());
        observer = new PostgresqlCatalogObserver(settings);
    }

    @AfterAll static void close() throws SQLException { if (database != null) { database.close(); } }

    @Test void observesEightTypesWithExactNamesKeyOrderAndCanonicalHistory() throws Exception {
        execute("""
                CREATE TABLE "ExactTable" (
                    id uuid PRIMARY KEY, flag bool NOT NULL, small_value int2, number int4,
                    big int8, "Text" text, day date, time_value timestamptz
                );
                CREATE INDEX "TextDay" ON "ExactTable" ("Text", day);
                CREATE INDEX "OtherTypes" ON "ExactTable" (flag, small_value, number, big, time_value);
                INSERT INTO "ExactTable" VALUES ('00000000-0000-0000-0000-000000000001', true, 1, 2, 3, 'fixture', null, null)
                """);
        execute(MigrationHistoryTable.createSql(schema));
        try {
            CatalogObservation observation = observer.observe(schema);
            assertEquals(State.COMPLETE, observation.state(), observation::toString);
            assertEquals(CatalogObservation.PROFILE, observation.profile());
            assertEquals(List.of("ExactTable"), List.copyOf(observation.snapshot().orElseThrow().tables().keySet()));
            SchemaSnapshot.Table table = observation.snapshot().orElseThrow().tables().get("ExactTable");
            assertEquals(Map.of("id", DdlStatement.Type.UUID, "flag", DdlStatement.Type.BOOLEAN, "small_value", DdlStatement.Type.SMALLINT,
                    "number", DdlStatement.Type.INTEGER, "big", DdlStatement.Type.BIGINT, "Text", DdlStatement.Type.TEXT, "day", DdlStatement.Type.DATE,
                    "time_value", DdlStatement.Type.TIMESTAMPTZ),
                    table.columns().entrySet().stream().collect(java.util.stream.Collectors.toMap(
                            Map.Entry::getKey, entry -> entry.getValue().type())));
            assertTrue(table.columns().get("id").primaryKey());
            assertFalse(table.columns().get("flag").nullable());
            assertTrue(table.columns().get("Text").nullable());
            assertEquals(List.of("Text", "day"), table.indexes().get("TextDay").columns().stream().map(SqlIdentifier::name).toList());
            assertFalse(observation.toString().contains("ExactTable"));
            assertEquals(1, scalar("SELECT count(*) FROM \"ExactTable\" WHERE \"Text\" = 'fixture'"));
            assertEquals(0, scalar("SELECT count(*) FROM _tuprel_migrations"));
            execute("CREATE INDEX unexpected_history_index ON _tuprel_migrations (engine_version)");
            assertIncomplete(Diagnostic.HISTORY);
        } finally { execute("DROP TABLE \"ExactTable\", _tuprel_migrations"); }
    }

    @Test void inventoriesUnsupportedObjectsAndPropertiesInsteadOfFilteringThemAway() throws Exception {
        execute("CREATE TABLE fixture (id int4 PRIMARY KEY, other int4, note text)");
        try {
            refused("CREATE VIEW outside_profile AS SELECT id FROM fixture", "DROP VIEW outside_profile", Diagnostic.RELATION);
            refused("CREATE SEQUENCE outside_profile", "DROP SEQUENCE outside_profile", Diagnostic.RELATION);
            refused("CREATE UNLOGGED TABLE outside_profile (id int4)", "DROP TABLE outside_profile", Diagnostic.RELATION);
            refused("CREATE TABLE outside_profile () INHERITS (fixture)", "DROP TABLE outside_profile", Diagnostic.RELATION);
            refused("CREATE TABLE outside_profile (id int4) PARTITION BY RANGE(id)", "DROP TABLE outside_profile", Diagnostic.RELATION);
            refused("CREATE TABLE _tuprel_secret (id int4)", "DROP TABLE _tuprel_secret", Diagnostic.RELATION);
            refused("CREATE TABLE \"private_name;--\" (id int4)", "DROP TABLE \"private_name;--\"", Diagnostic.RELATION);
            refused("CREATE TYPE outside_profile AS ENUM ('private_value')", "DROP TYPE outside_profile", Diagnostic.TYPE);
            refused("ALTER TABLE fixture ALTER COLUMN note SET DEFAULT 'private_value'",
                    "ALTER TABLE fixture ALTER COLUMN note DROP DEFAULT", Diagnostic.COLUMN);
            refused("ALTER TABLE fixture ALTER COLUMN note TYPE text COLLATE \"C\"",
                    "ALTER TABLE fixture ALTER COLUMN note TYPE text COLLATE \"default\"", Diagnostic.COLUMN);
            refused("ALTER TABLE fixture ALTER COLUMN note SET STORAGE PLAIN",
                    "ALTER TABLE fixture ALTER COLUMN note SET STORAGE EXTENDED", Diagnostic.COLUMN);
            refused("ALTER TABLE fixture ADD CONSTRAINT outside_profile CHECK (id > 0)",
                    "ALTER TABLE fixture DROP CONSTRAINT outside_profile", Diagnostic.CONSTRAINT);
            refused("CREATE INDEX outside_profile ON fixture (note text_pattern_ops)", "DROP INDEX outside_profile", Diagnostic.INDEX);
            refused("CREATE INDEX outside_profile ON fixture (other DESC)", "DROP INDEX outside_profile", Diagnostic.INDEX);
            refused("CREATE INDEX outside_profile ON fixture (other) INCLUDE (note)", "DROP INDEX outside_profile", Diagnostic.INDEX);
            refused("CREATE INDEX outside_profile ON fixture (other) WHERE id > 0", "DROP INDEX outside_profile", Diagnostic.INDEX);
            refused("CREATE INDEX outside_profile ON fixture (lower(note))", "DROP INDEX outside_profile", Diagnostic.INDEX);
            refused("CREATE UNIQUE INDEX outside_profile ON fixture (other)", "DROP INDEX outside_profile", Diagnostic.INDEX);
            refused("CREATE POLICY outside_profile ON fixture USING (true)", "DROP POLICY outside_profile ON fixture", Diagnostic.RELATION);
            refused("ALTER TABLE fixture REPLICA IDENTITY FULL", "ALTER TABLE fixture REPLICA IDENTITY DEFAULT", Diagnostic.RELATION);
            execute("ALTER TABLE fixture ADD COLUMN removed text; ALTER TABLE fixture DROP COLUMN removed");
            assertIncomplete(Diagnostic.COLUMN);
        } finally { execute("DROP TABLE fixture"); }
    }

    @Test void observesOnlyOneSnapshotWhileExternalDdlCommits() throws Exception {
        execute("CREATE TABLE temporal (id int4)");
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        PostgresqlCatalogObserver coordinated = new PostgresqlCatalogObserver(settings, intercept(
                (connection, method, args) -> {
                    if (method.getName().equals("prepareStatement") && CatalogQueries.COLUMNS.equals(args[0])) {
                        reading.countDown();
                        if (!resume.await(10, TimeUnit.SECONDS)) { throw new SQLException("Fixture synchronization timed out"); }
                    }
                    return invoke(connection, method, args);
                }));
        CompletableFuture<CatalogObservation> before = CompletableFuture.supplyAsync(() -> coordinated.observe(schema));
        try {
            try {
                assertTrue(reading.await(10, TimeUnit.SECONDS));
                execute("ALTER TABLE temporal ADD COLUMN after_snapshot text");
            } finally { resume.countDown(); }
            CatalogObservation first = before.get(15, TimeUnit.SECONDS);
            assertEquals(State.COMPLETE, first.state(), first::toString);
            assertEquals(List.of("id"), List.copyOf(first.snapshot().orElseThrow().tables().get("temporal").columns().keySet()));
            CatalogObservation later = observer.observe(schema);
            assertEquals(List.of("after_snapshot", "id"),
                    List.copyOf(later.snapshot().orElseThrow().tables().get("temporal").columns().keySet()));
        } finally { execute("DROP TABLE temporal"); }
    }

    @Test void acceptsExactMinimalLoginAndRefusesMissingUsage() throws Exception {
        boolean container = "container".equals(System.getProperty("tuprel.test.database", "local"));
        SqlIdentifier target = new SqlIdentifier(schema.name() + "_ro");
        String principal = new SqlIdentifier(container ? "tuprel_catalog_reader" : source.getUser()).name();
        String password = container ? UUID.randomUUID().toString() : source.getPassword();
        if (container) {
            execute("CREATE ROLE tuprel_catalog_reader LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS PASSWORD '"
                    + password + "'");
        }
        try {
            execute("CREATE SCHEMA \"" + target.name() + "\"" + (container ? "" : " AUTHORIZATION tuprel_status_reader"));
            String owner = container ? "" : "SET ROLE tuprel_status_reader; ";
            try {
                String qualifiedTable = "\"" + target.name() + "\".minimal";
                execute(owner + "CREATE TABLE " + qualifiedTable + " (id int4 PRIMARY KEY); RESET ROLE");
                execute(owner + MigrationHistoryTable.createSql(target) + "; RESET ROLE");
                MigrationDatabaseSettings restricted = settings(principal, password);
                PostgresqlCatalogObserver limited = new PostgresqlCatalogObserver(restricted);
                assertUnknown(limited.observe(target), Diagnostic.PERMISSION);
                execute(owner + "GRANT USAGE ON SCHEMA \"" + target.name() + "\" TO \"" + principal + "\"; RESET ROLE");
                try (Connection connection = restricted.dataSource().getConnection();
                        PreparedStatement statement = connection.prepareStatement("""
                                SELECT pg_catalog.has_schema_privilege(?, 'USAGE'),
                                       pg_catalog.has_schema_privilege(?, 'CREATE'),
                                       pg_catalog.has_table_privilege(?, 'SELECT')
                                """)) {
                    statement.setString(1, target.name()); statement.setString(2, target.name());
                    statement.setString(3, qualifiedTable);
                    try (ResultSet result = statement.executeQuery()) {
                        assertTrue(result.next());
                        assertTrue(result.getBoolean(1)); assertFalse(result.getBoolean(2)); assertFalse(result.getBoolean(3));
                    }
                }
                CatalogObservation result = limited.observe(target);
                assertEquals(State.COMPLETE, result.state(), result::toString);
                assertEquals(1, result.snapshot().orElseThrow().tables().size());
                String switchedRole = container ? principal : "tuprel_status_reader";
                PostgresqlCatalogObserver switched = new PostgresqlCatalogObserver(settings, intercept((connection, method, args) -> {
                    if (method.getName().equals("getAutoCommit")) {
                        try (Statement statement = connection.createStatement()) {
                            statement.execute("SET ROLE \"" + switchedRole + "\"");
                        }
                    }
                    return invoke(connection, method, args);
                }));
                assertUnknown(switched.observe(target), Diagnostic.TARGET);
            } finally { execute(owner + "DROP SCHEMA \"" + target.name() + "\" CASCADE; RESET ROLE"); }
        } finally {
            if (container) { execute("DROP ROLE tuprel_catalog_reader"); }
        }
    }

    @Test void refusesMissingOrReservedNamespaceWrongIdentityAndTlsMismatch() throws Exception {
        assertUnknown(observer.observe(new SqlIdentifier(schema.name() + "_missing")), Diagnostic.TARGET);
        assertUnknown(observer.observe(new SqlIdentifier("pg_catalog")), Diagnostic.TARGET);
        PostgresqlCatalogObserver wrongUser = new PostgresqlCatalogObserver(settings("different_user", "fixture"), settings.dataSource());
        assertUnknown(wrongUser.observe(schema), Diagnostic.TARGET);
        MigrationDatabaseSettings requireTls = MigrationDatabaseSettings.fromEnvironment(Map.of(
                "TUPREL_DATABASE_URL", jdbcUrl() + "?sslmode=verify-full",
                "TUPREL_DATABASE_USER", source.getUser(), "TUPREL_DATABASE_PASSWORD", source.getPassword()));
        assertUnknown(new PostgresqlCatalogObserver(requireTls, settings.dataSource()).observe(schema), Diagnostic.TARGET);
    }

    @Test void ignoresTempCatalogLookalikesAndDetectsInboundForeignKeys() throws Exception {
        SqlIdentifier other = new SqlIdentifier(schema.name() + "_fk");
        execute("CREATE TABLE reference (id int4 PRIMARY KEY)");
        execute("CREATE SCHEMA \"" + other.name() + "\"");
        try {
            PostgresqlCatalogObserver shadowed = new PostgresqlCatalogObserver(settings, intercept((connection, method, args) -> {
                if (method.getName().equals("getAutoCommit")) {
                    try (Statement statement = connection.createStatement()) {
                        statement.execute("CREATE TEMP TABLE pg_class (fake text); SET search_path = pg_temp");
                    }
                }
                return invoke(connection, method, args);
            }));
            CatalogObservation valid = shadowed.observe(schema);
            assertEquals(State.COMPLETE, valid.state(), valid::toString);
            assertEquals(List.of("reference"), List.copyOf(valid.snapshot().orElseThrow().tables().keySet()));
            execute("CREATE TABLE \"" + other.name() + "\".child (parent int4 REFERENCES reference(id))");
            assertIncomplete(Diagnostic.CONSTRAINT);
        } finally {
            execute("DROP SCHEMA \"" + other.name() + "\" CASCADE; DROP TABLE reference");
        }
    }

    @Test void aRealStatementTimeoutAndBackendLossCannotPublishEvidence() throws Exception {
        PostgresqlCatalogObserver timedOut = new PostgresqlCatalogObserver(settings, intercept((connection, method, args) -> {
            if (method.getName().equals("prepareStatement") && CatalogQueries.COLUMNS.equals(args[0])) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("SET LOCAL statement_timeout = '100ms'");
                    statement.execute("SELECT pg_catalog.pg_sleep(1)");
                }
            }
            return invoke(connection, method, args);
        }));
        assertUnknown(timedOut.observe(schema), Diagnostic.TIMEOUT);

        PostgresqlCatalogObserver disconnected = new PostgresqlCatalogObserver(settings, intercept((connection, method, args) -> {
            if (method.getName().equals("prepareStatement") && CatalogQueries.COLUMNS.equals(args[0])) {
                int pid;
                try (Statement statement = connection.createStatement();
                        ResultSet row = statement.executeQuery("SELECT pg_catalog.pg_backend_pid()")) {
                    assertTrue(row.next());
                    pid = row.getInt(1);
                }
                try (Connection killer = settings.dataSource().getConnection();
                        PreparedStatement statement = killer.prepareStatement("SELECT pg_catalog.pg_terminate_backend(?, 5000)")) {
                    statement.setInt(1, pid);
                    try (ResultSet row = statement.executeQuery()) {
                        assertTrue(row.next()); assertTrue(row.getBoolean(1));
                    }
                }
            }
            return invoke(connection, method, args);
        }));
        assertUnknown(disconnected.observe(schema), Diagnostic.CLEANUP);
        assertEquals(State.COMPLETE, observer.observe(schema).state());
    }

    @Test void blockedReadUsesTheTransactionLocalLockDeadline() throws Exception {
        long key = UUID.randomUUID().getMostSignificantBits();
        try (Connection blocker = settings.dataSource().getConnection()) {
            blocker.setAutoCommit(false);
            try (PreparedStatement lock = blocker.prepareStatement("SELECT pg_catalog.pg_advisory_xact_lock(?)")) {
                lock.setLong(1, key);
                lock.setQueryTimeout(5);
                lock.execute();
            }
            PostgresqlCatalogObserver blocked = new PostgresqlCatalogObserver(settings, intercept((connection, method, args) -> {
                if (method.getName().equals("prepareStatement") && CatalogQueries.COLUMNS.equals(args[0])) {
                    try (PreparedStatement read = connection.prepareStatement("SELECT pg_catalog.pg_advisory_xact_lock(?)")) {
                        read.setLong(1, key);
                        read.execute();
                    }
                }
                return invoke(connection, method, args);
            }));
            assertUnknown(blocked.observe(schema), Diagnostic.TIMEOUT);
            blocker.rollback();
        }
    }

    @Test void refusesBudgetsAndRedactsReadAndCleanupFailures() throws Exception {
        String columns = java.util.stream.IntStream.rangeClosed(1, 257).mapToObj(i -> "c" + i + " int4")
                .collect(java.util.stream.Collectors.joining(","));
        execute("CREATE TABLE oversized (" + columns + ")");
        try { assertUnknown(observer.observe(schema), Diagnostic.RESOURCE_LIMIT); }
        finally { execute("DROP TABLE oversized"); }

        for (Map.Entry<String, Diagnostic> example : Map.of("42501", Diagnostic.PERMISSION,
                "08006", Diagnostic.READ, "55P03", Diagnostic.TIMEOUT).entrySet()) {
            PostgresqlCatalogObserver fault = new PostgresqlCatalogObserver(settings, intercept((connection, method, args) -> {
                if (method.getName().equals("prepareStatement") && CatalogQueries.COLUMNS.equals(args[0])) {
                    throw new SQLException("private_value", example.getKey());
                }
                return invoke(connection, method, args);
            }));
            assertUnknown(fault.observe(schema), example.getValue());
        }
        for (String stage : List.of("rollback", "close")) {
            PostgresqlCatalogObserver fault = new PostgresqlCatalogObserver(settings, intercept((connection, method, args) -> {
                if (method.getName().equals(stage)) {
                    if (stage.equals("close")) { connection.close(); }
                    throw new SQLException("private_value", "08006");
                }
                return invoke(connection, method, args);
            }));
            assertUnknown(fault.observe(schema), Diagnostic.CLEANUP);
        }
    }

    private static void refused(String ddl, String cleanup, Diagnostic diagnostic) throws Exception {
        execute(ddl);
        try { assertIncomplete(diagnostic); }
        finally { execute(cleanup); }
    }

    private static void assertIncomplete(Diagnostic diagnostic) {
        CatalogObservation result = observer.observe(schema);
        assertEquals(State.INCOMPLETE, result.state(), result::toString);
        assertTrue(result.diagnostics().contains(diagnostic), result::toString);
        assertTrue(result.snapshot().isEmpty());
        assertFalse(result.toString().contains("private"));
    }

    private static void assertUnknown(CatalogObservation result, Diagnostic diagnostic) {
        assertEquals(State.UNKNOWN, result.state(), result::toString);
        assertEquals(List.of(diagnostic), result.diagnostics());
        assertTrue(result.snapshot().isEmpty());
        assertFalse(result.toString().contains("private_value"));
    }

    private static MigrationDatabaseSettings settings(String user, String password) throws Exception {
        return MigrationDatabaseSettings.fromEnvironment(Map.of(
                "TUPREL_DATABASE_URL", jdbcUrl(),
                "TUPREL_DATABASE_USER", user, "TUPREL_DATABASE_PASSWORD", password));
    }

    private static String jdbcUrl() {
        return "jdbc:postgresql://" + source.getServerNames()[0] + ":"
                + source.getPortNumbers()[0] + "/" + source.getDatabaseName();
    }

    private static DataSource intercept(Call call) {
        return new PGSimpleDataSource() {
            @Override public Connection getConnection() throws SQLException {
                Connection connection = settings.dataSource().getConnection();
                return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                        (proxy, method, args) -> call.invoke(connection, method, args));
            }
        };
    }

    private static Object invoke(Connection connection, Method method, Object[] args) throws Throwable {
        try { return method.invoke(connection, args); }
        catch (InvocationTargetException exception) { throw exception.getCause(); }
    }

    private static void execute(String sql) throws SQLException {
        try (Connection connection = source.getConnection(); Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(10);
            statement.execute(sql);
        }
    }

    private static int scalar(String sql) throws SQLException {
        try (Connection connection = source.getConnection(); Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getInt(1);
        }
    }

    @FunctionalInterface
    private interface Call {
        Object invoke(Connection connection, Method method, Object[] args) throws Throwable;
    }
}
