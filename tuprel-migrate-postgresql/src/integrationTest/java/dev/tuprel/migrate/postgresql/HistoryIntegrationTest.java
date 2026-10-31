package dev.tuprel.migrate.postgresql;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuprel.migrate.MigrationDirectory;
import dev.tuprel.migrate.MigrationFile;
import dev.tuprel.migrate.MigrationHistory;
import dev.tuprel.migrate.MigrationHistoryException;
import dev.tuprel.migrate.MigrationStatus;
import dev.tuprel.testsupport.TestPostgresql;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;

class HistoryIntegrationTest {
    private static TestPostgresql database;
    private static PGSimpleDataSource source;
    private static String schema;
    @TempDir Path temporary;
    Path catalog;

    @BeforeAll static void open() throws SQLException {
        database = TestPostgresql.open();
        source = database.dataSource();
        schema = source.getCurrentSchema();
        if (System.getProperty("tuprel.test.database", "local").equals("container")) {
            execute("CREATE ROLE tuprel_status_reader NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS");
        }
    }
    @AfterAll static void close() throws SQLException { if (database != null) { database.close(); } }
    @BeforeEach void fixture() throws Exception {
        catalog = Files.createTempDirectory(temporary.toRealPath(), "catalog-");
        Files.writeString(catalog.resolve(MigrationDirectory.FORMAT_FILE), MigrationDirectory.FORMAT_HEADER);
        execute("DROP TABLE IF EXISTS _tuprel_migrations CASCADE");
        execute("""
                CREATE TABLE _tuprel_migrations (
                    format_version smallint NOT NULL CHECK (format_version = 1),
                    migration_id varchar(15) PRIMARY KEY,
                    migration_name varchar(64) NOT NULL,
                    checksum char(64) NOT NULL,
                    state text NOT NULL CHECK (state = 'APPLIED'),
                    started_at timestamptz NOT NULL,
                    finished_at timestamptz NOT NULL CHECK (finished_at >= started_at),
                    execution_millis bigint NOT NULL CHECK (execution_millis >= 0),
                    engine_version varchar(64) NOT NULL
                )
                """);
    }

    @Test void missingHistoryIsUninitialisedAndDoesNotBootstrapAnything() throws Exception {
        execute("DROP TABLE _tuprel_migrations");
        local(1, "initial");
        String before = objects();
        MigrationStatus status = status();
        assertFalse(status.initialised());
        assertFalse(status.hasProblems());
        assertEquals(MigrationStatus.State.PENDING, status.entries().getFirst().states().iterator().next());
        assertEquals(before, objects());
        assertEquals(0, count("SELECT count(*) FROM pg_catalog.pg_class WHERE relnamespace = '" + schema + "'::regnamespace"));
    }

    @Test void validPrefixAndPendingMigrationsAreReportedWithoutChanges() throws Exception {
        MigrationFile first = local(1, "initial"); local(2, "next");
        applied(first, 1);
        String before = fingerprint();
        MigrationStatus status = status();
        assertTrue(status.initialised());
        assertFalse(status.hasProblems());
        assertTrue(status.entries().getFirst().states().contains(MigrationStatus.State.APPLIED));
        assertTrue(status.entries().getLast().states().contains(MigrationStatus.State.PENDING));
        assertEquals(before, fingerprint());
    }

    @Test void modifiedRemovedAndRenamedFilesRemainIndependentProblems() throws Exception {
        MigrationFile first = local(1, "initial");
        MigrationFile second = local(2, "removed");
        applied(first, 1); applied(second, 2);
        Path old = catalog.resolve(first.directoryName());
        Files.move(old, catalog.resolve("20261030_120001_renamed"));
        Files.writeString(catalog.resolve("20261030_120001_renamed/migration.sql"), "-- changed\n");
        Files.delete(catalog.resolve(second.directoryName()).resolve("migration.sql"));
        Files.delete(catalog.resolve(second.directoryName()));
        MigrationStatus status = status();
        assertTrue(status.entries().getFirst().states().containsAll(java.util.Set.of(MigrationStatus.State.MODIFIED, MigrationStatus.State.RENAMED)));
        assertTrue(status.entries().getLast().states().contains(MigrationStatus.State.REMOVED));
        assertTrue(status.hasProblems());
    }

    @Test void gapsAndExecutionSequenceAreOutOfOrder() throws Exception {
        MigrationFile first = local(1, "first"); MigrationFile second = local(2, "second");
        applied(second, 1);
        assertTrue(status().entries().stream().allMatch(entry -> entry.states().contains(MigrationStatus.State.OUT_OF_ORDER)));
        applied(first, 2);
        assertTrue(status().hasProblems());
    }

    @Test void unknownColumnsConstraintsTypesAndPoliciesFailClosed() throws Exception {
        for (String change : List.of("ALTER TABLE _tuprel_migrations ADD COLUMN extra text",
                "ALTER TABLE _tuprel_migrations ALTER COLUMN migration_name TYPE varchar(63)",
                "ALTER TABLE _tuprel_migrations DROP CONSTRAINT _tuprel_migrations_format_version_check",
                "ALTER TABLE _tuprel_migrations ENABLE ROW LEVEL SECURITY",
                "ALTER TABLE _tuprel_migrations DROP CONSTRAINT _tuprel_migrations_format_version_check; ALTER TABLE _tuprel_migrations ADD CHECK (format_version = 2)",
                "ALTER TABLE _tuprel_migrations ALTER COLUMN state SET DEFAULT 'APPLIED'",
                "ALTER TABLE _tuprel_migrations ADD CONSTRAINT extra CHECK (length(engine_version) > 0)")) {
            try (Connection connection = source.getConnection(); Statement statement = connection.createStatement()) {
                connection.setAutoCommit(false); statement.execute(change);
                // Committed metadata is needed by the independent observer; undo explicitly in the fixture afterwards.
                connection.commit();
            }
            assertFailure(MigrationHistoryException.Reason.INCOMPATIBLE, reader(source));
            fixture();
        }
    }

    @Test void unknownRowValuesAndVersionsAreRejected() throws Exception {
        MigrationFile file = local(1, "initial"); applied(file, 1);
        for (String update : List.of("checksum = repeat('z',64)", "migration_name = '../private_value'", "engine_version = ''")) {
            execute("UPDATE _tuprel_migrations SET " + update);
            MigrationHistoryException exception = assertThrows(MigrationHistoryException.class, () -> reader(source).read(schema));
            assertEquals(MigrationHistoryException.Reason.INCOMPATIBLE, exception.reason());
            assertFalse(exception.toString().contains("private_value"));
            execute("DELETE FROM _tuprel_migrations"); applied(file, 1);
        }
    }

    @Test void explicitSchemaAndIdentifierValidationNeverCreateSchemas() throws Exception {
        String before = objects();
        MigrationHistoryException missing = assertThrows(MigrationHistoryException.class,
                () -> reader(source).read("tuprel_missing_" + java.util.UUID.randomUUID().toString().replace("-", "")));
        assertEquals(MigrationHistoryException.Reason.MISSING_SCHEMA, missing.reason());
        assertEquals(MigrationHistoryException.Reason.CONFIGURATION,
                assertThrows(MigrationHistoryException.class, () -> reader(source).read("x\"; private_value")).reason());
        assertEquals(before, objects());
    }

    @Test void selectOnlyRoleWorksAndCannotWrite() throws Exception {
        MigrationFile file = local(1, "initial"); applied(file, 1);
        execute("GRANT USAGE ON SCHEMA \"" + schema + "\" TO tuprel_status_reader");
        execute("GRANT SELECT ON _tuprel_migrations TO tuprel_status_reader");
        String before = fingerprint();
        DataSource role = roleSource();
        try (Connection connection = role.getConnection(); Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("""
                        SELECT current_user = 'tuprel_status_reader',
                               has_table_privilege('_tuprel_migrations', 'SELECT'),
                               has_table_privilege('_tuprel_migrations', 'INSERT'),
                               has_table_privilege('_tuprel_migrations', 'UPDATE'),
                               has_table_privilege('_tuprel_migrations', 'DELETE')
                        """)) {
            assertTrue(result.next()); assertTrue(result.getBoolean(1)); assertTrue(result.getBoolean(2));
            assertFalse(result.getBoolean(3)); assertFalse(result.getBoolean(4)); assertFalse(result.getBoolean(5));
        }
        assertEquals(1, reader(role).read(schema).applied().size());
        assertEquals(before, fingerprint());
    }

    @Test void missingUsageAndSelectPrivilegesAreExplicitErrors() throws Exception {
        execute("REVOKE ALL ON SCHEMA \"" + schema + "\" FROM tuprel_status_reader");
        assertFailure(MigrationHistoryException.Reason.PERMISSION, reader(roleSource()));
        execute("GRANT USAGE ON SCHEMA \"" + schema + "\" TO tuprel_status_reader");
        assertFailure(MigrationHistoryException.Reason.PERMISSION, reader(roleSource()));
    }

    @Test void concurrentInsertIsExcludedFromTheRepeatableReadSnapshot() throws Exception {
        MigrationFile first = local(1, "initial"); MigrationFile next = local(2, "next"); applied(first, 1);
        CountDownLatch snapshot = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean once = new AtomicBoolean();
        DataSource coordinated = wrap(connection -> (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, arguments) -> {
                    Object value = invoke(connection, method, arguments);
                    if (method.getName().equals("prepareStatement") && value instanceof PreparedStatement prepared) {
                        return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PreparedStatement.class},
                                (p, call, args) -> {
                                    Object answer = invoke(prepared, call, args);
                                    if (call.getName().equals("executeQuery") && once.compareAndSet(false, true)) {
                                        snapshot.countDown();
                                        if (!release.await(15, TimeUnit.SECONDS)) { throw new SQLException("Snapshot barrier timeout"); }
                                    }
                                    return answer;
                                });
                    }
                    return value;
                }));
        CompletableFuture<MigrationHistory> observed = CompletableFuture.supplyAsync(() -> {
            try { return reader(coordinated).read(schema); }
            catch (MigrationHistoryException exception) { throw new java.util.concurrent.CompletionException(exception); }
        });
        try {
            assertTrue(snapshot.await(15, TimeUnit.SECONDS));
            applied(next, 2);
        } finally { release.countDown(); }
        assertEquals(1, observed.get(20, TimeUnit.SECONDS).applied().size());
        assertEquals(2, reader(source).read(schema).applied().size());
    }

    @Test void rollbackFailureAbortsClosesAndNeverRetriesOrEnablesAutoCommit() throws Exception {
        AtomicInteger rollbacks = new AtomicInteger(); AtomicInteger aborts = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger(); AtomicInteger resets = new AtomicInteger();
        DataSource faulty = wrap(connection -> (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("rollback")) { rollbacks.incrementAndGet(); throw new SQLException("private_value", "08006"); }
                    if (method.getName().equals("abort")) { aborts.incrementAndGet(); }
                    if (method.getName().equals("close")) { closes.incrementAndGet(); }
                    if (method.getName().equals("setAutoCommit") && Boolean.TRUE.equals(arguments[0])) { resets.incrementAndGet(); }
                    return invoke(connection, method, arguments);
                }));
        MigrationHistoryException failure = assertThrows(MigrationHistoryException.class, () -> reader(faulty).read(schema));
        assertEquals(MigrationHistoryException.Reason.TRANSACTION, failure.reason());
        assertFalse(failure.toString().contains("private_value")); assertNull(failure.getCause());
        assertEquals(1, rollbacks.get()); assertEquals(1, aborts.get()); assertEquals(1, closes.get()); assertEquals(0, resets.get());
    }

    @Test void closeFailureDoesNotPublishAnOtherwiseValidSnapshot() throws Exception {
        AtomicInteger closes = new AtomicInteger();
        DataSource faulty = wrap(connection -> (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, arguments) -> {
                    Object result = invoke(connection, method, arguments);
                    if (method.getName().equals("close")) { closes.incrementAndGet(); throw new SQLException("private_value", "08006"); }
                    return result;
                }));
        assertFailure(MigrationHistoryException.Reason.JDBC, reader(faulty));
        assertEquals(1, closes.get());
    }

    @Test void ignoredReadOnlyModeAndActiveConnectionsAreRefused() throws Exception {
        DataSource ignoredMode = wrap(connection -> (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("setReadOnly")) { return null; }
                    return invoke(connection, method, arguments);
                }));
        assertFailure(MigrationHistoryException.Reason.TRANSACTION, reader(ignoredMode));
        DataSource active = wrap(connection -> { connection.setAutoCommit(false); return connection; });
        assertFailure(MigrationHistoryException.Reason.TRANSACTION, reader(active));
    }

    @Test void suppressedDriverCloseMessagesAreNotExposedByPublicExceptions() throws Exception {
        DataSource faulty = wrap(connection -> (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, arguments) -> {
                    Object result = invoke(connection, method, arguments);
                    if (method.getName().equals("close")) { throw new SQLException("private_value", "08006"); }
                    return result;
                }));
        MigrationHistoryException failure = assertThrows(MigrationHistoryException.class,
                () -> reader(faulty).read("tuprel_missing_" + java.util.UUID.randomUUID().toString().replace("-", "")));
        assertEquals(MigrationHistoryException.Reason.MISSING_SCHEMA, failure.reason());
        assertNull(failure.getCause()); assertEquals(0, failure.getSuppressed().length);
    }

    private static DataSource roleSource() {
        return wrap(connection -> {
            try (Statement statement = connection.createStatement()) { statement.execute("SET ROLE tuprel_status_reader"); }
            catch (SQLException failure) {
                connection.close();
                throw new SQLException("Provision the NOLOGIN role using docs/development/STATUS_TEST_ROLE.sql", "42501");
            }
            return connection;
        });
    }
    private static DataSource wrap(ConnectionWrapper wrapper) {
        return new PGSimpleDataSource() {
            private static final long serialVersionUID = 1L;
            @Override public Connection getConnection() throws SQLException { return wrapper.wrap(source.getConnection()); }
        };
    }
    private static Object invoke(Object target, java.lang.reflect.Method method, Object[] arguments) throws Throwable {
        try { return method.invoke(target, arguments); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
    @FunctionalInterface private interface ConnectionWrapper { Connection wrap(Connection connection) throws SQLException; }

    private MigrationStatus status() throws Exception { return MigrationStatus.compare(MigrationDirectory.discover(catalog), reader(source).read(schema)); }
    private static PostgresqlMigrationHistory reader(DataSource dataSource) { return new PostgresqlMigrationHistory(dataSource); }
    private static void assertFailure(MigrationHistoryException.Reason reason, PostgresqlMigrationHistory reader) {
        assertEquals(reason, assertThrows(MigrationHistoryException.class, () -> reader.read(schema)).reason());
    }
    private MigrationFile local(int second, String name) throws IOException {
        Path directory = Files.createDirectory(catalog.resolve("20261030_1200%02d_".formatted(second) + name));
        byte[] bytes = "-- reviewed fixture\n".getBytes(StandardCharsets.UTF_8);
        Files.write(directory.resolve("migration.sql"), bytes);
        return MigrationFile.fromDirectory(directory.getFileName().toString(), bytes);
    }
    private static void applied(MigrationFile file, int sequence) throws SQLException {
        try (Connection connection = source.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO _tuprel_migrations VALUES (1, ?, ?, ?, 'APPLIED', ?, ?, 1, 'development')")) {
            statement.setString(1, file.id().value()); statement.setString(2, file.name()); statement.setString(3, file.checksum().value());
            OffsetDateTime start = OffsetDateTime.parse("2026-10-30T12:00:00Z").plusSeconds(sequence * 2L);
            statement.setObject(4, start); statement.setObject(5, start.plusSeconds(1)); statement.executeUpdate();
        }
    }
    private static void execute(String sql) throws SQLException {
        try (Connection connection = source.getConnection(); Statement statement = connection.createStatement()) { statement.execute(sql); }
    }
    private static long count(String sql) throws SQLException {
        try (Connection connection = source.getConnection(); Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            assertTrue(rows.next()); return rows.getLong(1);
        }
    }
    private static String objects() throws SQLException {
        try (Connection connection = source.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT oid::text, relname, relkind FROM pg_catalog.pg_class WHERE relnamespace = ?::regnamespace ORDER BY oid")) {
            statement.setString(1, schema);
            StringBuilder result = new StringBuilder();
            try (ResultSet rows = statement.executeQuery()) { while (rows.next()) { result.append(rows.getString(1)).append(rows.getString(2)).append(rows.getString(3)); } }
            return result.toString();
        }
    }
    private static String fingerprint() throws SQLException {
        try (Connection connection = source.getConnection(); Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT row_to_json(h)::text FROM _tuprel_migrations h ORDER BY migration_id")) {
            StringBuilder result = new StringBuilder(objects());
            while (rows.next()) { result.append(rows.getString(1)); }
            return result.toString();
        }
    }
}
