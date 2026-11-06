package dev.tuprel.migrate.postgresql;

import static dev.tuprel.migrate.postgresql.PhysicalDriftReport.Diagnostic;
import static dev.tuprel.migrate.postgresql.PhysicalDriftReport.State;
import static dev.tuprel.migrate.postgresql.PostgresqlDriftInspector.ReferenceControl;
import static org.junit.jupiter.api.Assertions.*;

import dev.tuprel.migrate.MigrationDirectory;
import dev.tuprel.migrate.MigrationFile;
import dev.tuprel.migrate.postgresql.diff.SchemaDiff;
import dev.tuprel.sql.SqlIdentifier;
import dev.tuprel.testsupport.TestPostgresql;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;

class PhysicalDriftIntegrationTest {
    private static final AtomicInteger sequence = new AtomicInteger();
    private static TestPostgresql database;
    private static PGSimpleDataSource source;
    private static MigrationDatabaseSettings settings;
    private static PostgresqlDriftInspector inspector;
    @TempDir Path temporary;
    private SqlIdentifier schema;
    private Path directory;
    private boolean created;

    @BeforeAll static void open() throws Exception {
        database = TestPostgresql.open();
        source = database.dataSource();
        settings = MigrationDatabaseSettings.fromEnvironment(Map.of(
                "TUPREL_DATABASE_URL", "jdbc:postgresql://" + source.getServerNames()[0] + ":"
                        + source.getPortNumbers()[0] + "/" + source.getDatabaseName(),
                "TUPREL_DATABASE_USER", source.getUser(), "TUPREL_DATABASE_PASSWORD", source.getPassword()));
        inspector = new PostgresqlDriftInspector(settings);
    }

    @AfterAll static void close() throws SQLException { if (database != null) { database.close(); } }

    @BeforeEach void fixture() throws Exception {
        directory = Files.createTempDirectory(temporary.toRealPath(), "catalog-");
        Files.writeString(directory.resolve(MigrationDirectory.FORMAT_FILE), MigrationDirectory.FORMAT_HEADER);
        schema = new SqlIdentifier(source.getCurrentSchema() + "_d" + sequence.incrementAndGet());
        execute("CREATE SCHEMA " + quotedSchema());
        created = true;
    }

    @AfterEach void cleanup() throws SQLException {
        if (created) { execute("DROP SCHEMA " + quotedSchema() + " CASCADE"); }
    }

    @Test void verifiesAppliedPrefixAndExcludesPendingEvenWhenItsSqlIsUnsupported() throws Exception {
        createHistory();
        String ddl = "CREATE TABLE " + table("Items") + " (\"id\" INTEGER PRIMARY KEY, \"note\" TEXT);";
        execute(ddl);
        execute("INSERT INTO " + table("Items") + " VALUES (1, 'fixture')");
        MigrationFile first = migration(1, "initial", ddl);
        applied(first, 1);
        MigrationFile pending = migration(2, "pending", "SELECT 'private_source_sql';");
        Path pendingSql = directory.resolve(pending.directoryName()).resolve("migration.sql");
        byte[] before = Files.readAllBytes(pendingSql);

        PhysicalDriftReport result = managed();
        assertEquals(State.NO_DRIFT, result.state(), result::toString);
        assertTrue(result.differences().isEmpty());
        assertEquals(CatalogObservation.PROFILE, result.profile());
        assertEquals(1, count("SELECT count(*) FROM " + table("Items") + " WHERE note = 'fixture'"));
        assertEquals(1, count("SELECT count(*) FROM " + table(MigrationHistoryTable.NAME)));
        assertArrayEquals(before, Files.readAllBytes(pendingSql));
    }

    @Test void reportsConfirmedDifferencesWithoutSqlOrRenameInferenceAndRefusesPartialEvidence() throws Exception {
        createHistory();
        String ddl = createTable("before_name");
        execute(ddl);
        applied(migration(1, "initial", ddl), 1);
        execute("ALTER TABLE " + table("before_name") + " RENAME TO after_name");
        PhysicalDriftReport result = managed();
        assertEquals(State.DRIFT_DETECTED, result.state(), result::toString);
        assertEquals(List.of(SchemaDiff.Kind.TABLE_ADDED, SchemaDiff.Kind.TABLE_REMOVED),
                result.differences().stream().map(SchemaDiff.Change::kind).toList());
        assertTrue(result.differences().stream().allMatch(change -> change.risks().isEmpty()));
        assertFalse(result.toString().contains("after_name"));

        execute("ALTER TABLE " + table("after_name") + " ALTER COLUMN id SET DEFAULT 42");
        PhysicalDriftReport partial = managed();
        assertUnknown(partial, Diagnostic.CATALOG);
        assertEquals(List.of(CatalogObservation.Diagnostic.COLUMN), partial.catalogDiagnostics());
    }

    @Test void reportsColumnAndIndexPropertiesInDeterministicOrder() throws Exception {
        createHistory();
        String name = table("items");
        String ddl = "CREATE TABLE " + name + " (\"id\" INTEGER PRIMARY KEY, \"key\" INTEGER NOT NULL,"
                + " \"count\" INTEGER, \"note\" TEXT); CREATE INDEX \"listing\" ON " + name + " (\"count\", \"note\");";
        execute(ddl);
        applied(migration(1, "initial", ddl), 1);
        execute("ALTER TABLE " + name + " DROP CONSTRAINT items_pkey, ADD PRIMARY KEY (key), "
                + "ALTER COLUMN count TYPE bigint, ALTER COLUMN note SET NOT NULL");
        execute("DROP INDEX " + table("listing") + "; CREATE INDEX listing ON " + name + " (note, count)");
        PhysicalDriftReport report = managed();
        assertEquals(State.DRIFT_DETECTED, report.state(), report::toString);
        assertEquals(List.of("COLUMN_TYPE_CHANGED:count", "COLUMN_PRIMARY_KEY_CHANGED:id",
                "COLUMN_PRIMARY_KEY_CHANGED:key", "COLUMN_NULLABILITY_CHANGED:note", "INDEX_CHANGED:listing"),
                report.differences().stream().map(change -> change.kind() + ":" + change.member().orElseThrow().name()).toList());
    }

    @Test void anEmptyCatalogWithoutHistoryDoesNotCertifyEquivalence() throws Exception {
        migration(1, "pending", createTable("items"));
        assertUnknown(inspector.inspect(schema, directory), Diagnostic.REFERENCE_CONTROL);
        assertUnknown(managed(), Diagnostic.REFERENCE_MISSING);
        assertEquals(0, count("SELECT count(*) FROM pg_catalog.pg_class WHERE relnamespace = '" + schema.name() + "'::regnamespace"));
        createHistory();
        assertEquals(State.NO_DRIFT, managed().state());
        execute("DROP TABLE " + table(MigrationHistoryTable.NAME));
        execute("CREATE TABLE " + table("unexpected") + " (id int4)");
        assertUnknown(managed(), Diagnostic.REFERENCE_MISSING);
    }

    @Test void rejectsChecksumRenameRemovalAndOutOfOrderHistory() throws Exception {
        createHistory();
        String ddl = createTable("items");
        MigrationFile first = migration(1, "initial", ddl);
        MigrationFile second = migration(2, "later", createTable("later"));
        applied(second, 2);
        assertUnknown(managed(), Diagnostic.APPLIED_PREFIX);
        applied(first, 1);
        Path original = directory.resolve(first.directoryName());
        Path sql = original.resolve("migration.sql");
        Files.writeString(sql, ddl + "\n-- edited\n");
        assertUnknown(managed(), Diagnostic.APPLIED_PREFIX);
        Files.writeString(sql, ddl + "\n");
        Path renamed = directory.resolve("20261106_150001_renamed");
        Files.move(original, renamed);
        assertUnknown(managed(), Diagnostic.APPLIED_PREFIX);
        Files.delete(renamed.resolve("migration.sql"));
        Files.delete(renamed);
        assertUnknown(managed(), Diagnostic.APPLIED_PREFIX);
    }

    @Test void verifiesHistoryChecksAndRefusesInvalidReplayOrLocalCatalog() throws Exception {
        createHistory();
        applied(migration(1, "unknown", "SELECT 'private_source_sql';"), 1);
        assertUnknown(managed(), Diagnostic.REPLAY);
        execute("DELETE FROM " + table(MigrationHistoryTable.NAME));
        execute("ALTER TABLE " + table(MigrationHistoryTable.NAME)
                + " DROP CONSTRAINT _tuprel_migrations_format_version_check, ADD CHECK (format_version > 0)");
        assertUnknown(managed(), Diagnostic.HISTORY_INCOMPATIBLE);
        Files.delete(directory.resolve(MigrationDirectory.FORMAT_FILE));
        assertUnknown(managed(), Diagnostic.LOCAL_CATALOG);
    }

    @Test void rejectsHistoryReplacementBetweenSnapshotAndNameResolution() throws Exception {
        createHistory();
        AtomicBoolean replaced = new AtomicBoolean();
        AtomicBoolean readerInvoked = new AtomicBoolean();
        PostgresqlDriftInspector racing = inspectWithHook(sql -> {
            if (sql.contains("pg_catalog.to_regnamespace") && replaced.compareAndSet(false, true)) {
                execute("ALTER TABLE " + table(MigrationHistoryTable.NAME) + " RENAME TO former_history");
                execute("CREATE VIEW " + table(MigrationHistoryTable.NAME) + " AS SELECT 1 AS spoof");
            }
            if (sql.contains("pg_get_constraintdef") || sql.startsWith("SELECT format_version,")) {
                readerInvoked.set(true);
            }
        });
        assertUnknown(racing.inspect(schema, directory, ReferenceControl.MANAGED_HISTORY), Diagnostic.HISTORY_IDENTITY);
        assertTrue(replaced.get());
        assertFalse(readerInvoked.get());
    }

    @Test void confirmsTheLockOidEvenWhenTheHistoryNameChangesAfterPreflight() throws Exception {
        createHistory();
        AtomicBoolean replaced = new AtomicBoolean();
        AtomicBoolean readerInvoked = new AtomicBoolean();
        PostgresqlDriftInspector racing = inspectWithHook(sql -> {
            if (sql.startsWith("LOCK TABLE ONLY ") && replaced.compareAndSet(false, true)) {
                execute("ALTER TABLE " + table(MigrationHistoryTable.NAME) + " RENAME TO former_history");
                execute(createTable(MigrationHistoryTable.NAME));
            }
            if (sql.contains("pg_get_constraintdef") || sql.startsWith("SELECT format_version,")) {
                readerInvoked.set(true);
            }
        });
        assertUnknown(racing.inspect(schema, directory, ReferenceControl.MANAGED_HISTORY), Diagnostic.HISTORY_IDENTITY);
        assertTrue(replaced.get());
        assertFalse(readerInvoked.get());
    }

    @Test void observesCatalogAndHistoryFromTheSameSnapshot() throws Exception {
        createHistory();
        String ddl = createTable("items");
        execute(ddl);
        applied(migration(1, "initial", ddl), 1);
        MigrationFile second = migration(2, "later", createTable("later"));
        AtomicBoolean inserted = new AtomicBoolean();
        PostgresqlDriftInspector coordinated = inspectWithHook(sql -> {
            if (sql.contains("pg_catalog.to_regnamespace") && inserted.compareAndSet(false, true)) { applied(second, 2); }
        });
        assertEquals(State.NO_DRIFT, coordinated.inspect(schema, directory, ReferenceControl.MANAGED_HISTORY).state());
        assertTrue(inserted.get());
        assertEquals(State.DRIFT_DETECTED, managed().state());
    }

    @Test void requiresHistorySelectButCanCompareWithoutApplicationSelect() throws Exception {
        boolean container = "container".equals(System.getProperty("tuprel.test.database", "local"));
        String principal = new SqlIdentifier(container ? "tuprel_drift_reader" : source.getUser()).name();
        String password = container ? UUID.randomUUID().toString() : source.getPassword();
        String owner = container ? "" : "SET ROLE tuprel_status_reader; ";
        if (container) {
            execute("CREATE ROLE tuprel_drift_reader LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS PASSWORD '"
                    + password + "'");
        } else {
            execute("ALTER SCHEMA " + quotedSchema() + " OWNER TO tuprel_status_reader");
        }
        try {
            execute(owner + MigrationHistoryTable.createSql(schema));
            execute(owner + createTable("application_table"));
            execute(owner + "GRANT USAGE ON SCHEMA " + quotedSchema() + " TO \"" + principal + "\"");
            MigrationDatabaseSettings restricted = MigrationDatabaseSettings.fromEnvironment(Map.of(
                    "TUPREL_DATABASE_URL", "jdbc:postgresql://" + source.getServerNames()[0] + ":"
                            + source.getPortNumbers()[0] + "/" + source.getDatabaseName(),
                    "TUPREL_DATABASE_USER", principal, "TUPREL_DATABASE_PASSWORD", password));
            PostgresqlDriftInspector limited = new PostgresqlDriftInspector(restricted);
            PhysicalDriftReport refused = limited.inspect(schema, directory, ReferenceControl.MANAGED_HISTORY);
            assertUnknown(refused, Diagnostic.CATALOG);
            assertEquals(List.of(CatalogObservation.Diagnostic.PERMISSION), refused.catalogDiagnostics());
            execute(owner + "GRANT SELECT ON " + table(MigrationHistoryTable.NAME) + " TO \"" + principal + "\"");
            try (Connection connection = restricted.dataSource().getConnection();
                    PreparedStatement statement = connection.prepareStatement("SELECT pg_catalog.has_table_privilege(?, 'SELECT')")) {
                statement.setString(1, table("application_table"));
                try (ResultSet rows = statement.executeQuery()) {
                    assertTrue(rows.next()); assertFalse(rows.getBoolean(1));
                }
            }
            PhysicalDriftReport confirmed = limited.inspect(schema, directory, ReferenceControl.MANAGED_HISTORY);
            assertEquals(State.DRIFT_DETECTED, confirmed.state(), confirmed::toString);
            assertEquals("application_table", confirmed.differences().getFirst().table().name());
        } finally {
            execute(owner + "DROP SCHEMA " + quotedSchema() + " CASCADE");
            created = false;
            if (container) { execute("DROP ROLE tuprel_drift_reader"); }
        }
    }

    @Test void historyLockTimesOutConservativelyAndIsReleasedAtTransactionEnd() throws Exception {
        createHistory();
        try (Connection blocker = source.getConnection(); Statement statement = blocker.createStatement()) {
            blocker.setAutoCommit(false);
            statement.setQueryTimeout(5);
            statement.execute("LOCK TABLE " + table(MigrationHistoryTable.NAME) + " IN ACCESS EXCLUSIVE MODE");
            PhysicalDriftReport result = managed();
            assertUnknown(result, Diagnostic.CATALOG);
            assertEquals(List.of(CatalogObservation.Diagnostic.TIMEOUT), result.catalogDiagnostics());
            blocker.rollback();
        }
        assertEquals(State.NO_DRIFT, managed().state());
        try (Connection peer = source.getConnection(); Statement statement = peer.createStatement()) {
            peer.setAutoCommit(false);
            statement.execute("LOCK TABLE " + table(MigrationHistoryTable.NAME) + " IN ACCESS EXCLUSIVE MODE NOWAIT");
            peer.rollback();
        }
    }

    private PhysicalDriftReport managed() { return inspector.inspect(schema, directory, ReferenceControl.MANAGED_HISTORY); }

    private static void assertUnknown(PhysicalDriftReport result, Diagnostic reason) {
        assertEquals(State.UNKNOWN, result.state(), result::toString);
        assertEquals(reason, result.diagnostic().orElseThrow(), result::toString);
        assertTrue(result.differences().isEmpty());
        assertFalse(result.toString().contains("private_source_sql"));
    }

    private MigrationFile migration(int order, String name, String sql) throws Exception {
        String id = "20261106_15000" + order;
        Path artifact = Files.createDirectory(directory.resolve(id + "_" + name));
        Files.writeString(artifact.resolve("migration.sql"), sql + "\n");
        return MigrationDirectory.discover(directory).stream().filter(file -> file.directoryName().endsWith("_" + name))
                .findFirst().orElseThrow();
    }

    private void createHistory() throws SQLException { execute(MigrationHistoryTable.createSql(schema)); }

    private void applied(MigrationFile file, int order) throws SQLException {
        String insert = "INSERT INTO " + table(MigrationHistoryTable.NAME)
                + " VALUES (1, ?, ?, ?, 'APPLIED', ?, ?, 0, 'fixture')";
        try (Connection connection = source.getConnection(); PreparedStatement statement = connection.prepareStatement(insert)) {
            statement.setString(1, file.id().value());
            statement.setString(2, file.name());
            statement.setString(3, file.checksum().value());
            OffsetDateTime time = OffsetDateTime.parse("2026-11-06T15:00:00Z").plusSeconds(order);
            statement.setObject(4, time);
            statement.setObject(5, time);
            statement.setQueryTimeout(5);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private String quotedSchema() { return "\"" + schema.name() + "\""; }

    private String table(String name) { return quotedSchema() + ".\"" + new SqlIdentifier(name).name() + "\""; }

    private String createTable(String name) { return "CREATE TABLE " + table(name) + " (\"id\" INTEGER);"; }

    private static void execute(String sql) throws SQLException {
        try (Connection connection = source.getConnection(); Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(5);
            statement.execute(sql);
        }
    }

    private static int count(String sql) throws SQLException {
        try (Connection connection = source.getConnection(); Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getInt(1);
        }
    }

    private static PostgresqlDriftInspector inspectWithHook(SqlHook hook) {
        DataSource hooked = new PGSimpleDataSource() {
            @Override public Connection getConnection() throws SQLException {
                Connection connection = settings.dataSource().getConnection();
                return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                        (proxy, method, args) -> {
                            if (method.getName().equals("prepareStatement") && args[0] instanceof String sql) { hook.read(sql); }
                            try {
                                Object value = method.invoke(connection, args);
                                if (method.getName().equals("createStatement") && value instanceof Statement statement) {
                                    return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Statement.class},
                                            (stmtProxy, call, arguments) -> {
                                                if (call.getName().equals("execute") && arguments != null
                                                        && arguments[0] instanceof String sql) { hook.read(sql); }
                                                try { return call.invoke(statement, arguments); }
                                                catch (InvocationTargetException failure) { throw failure.getCause(); }
                                            });
                                }
                                return value;
                            }
                            catch (InvocationTargetException failure) { throw failure.getCause(); }
                        });
            }
        };
        return new PostgresqlDriftInspector(new PostgresqlCatalogObserver(settings, hooked));
    }

    @FunctionalInterface
    private interface SqlHook {
        void read(String sql) throws SQLException;
    }
}
