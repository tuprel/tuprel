package dev.tuprel.migrate.postgresql;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuprel.migrate.MigrationDirectory;
import dev.tuprel.migrate.MigrationFile;
import dev.tuprel.migrate.MigrationHistory;
import dev.tuprel.migrate.postgresql.ddl.PostgresqlDdlParser;
import dev.tuprel.testsupport.TestPostgresql;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;

class MigrationRunnerIntegrationTest {
    private static TestPostgresql database;
    private static PGSimpleDataSource source;
    private static MigrationDatabaseSettings settings;
    private static final AtomicInteger sequence = new AtomicInteger();
    private String schema;
    @TempDir Path directory;

    @BeforeAll static void open() throws Exception {
        database = TestPostgresql.open(); source = database.dataSource();
        settings = MigrationDatabaseSettings.fromEnvironment(Map.of("TUPREL_DATABASE_URL",
                "jdbc:postgresql://" + source.getServerNames()[0] + ":" + source.getPortNumbers()[0] + "/" + source.getDatabaseName(),
                "TUPREL_DATABASE_USER", source.getUser(), "TUPREL_DATABASE_PASSWORD", source.getPassword()));
    }
    @AfterAll static void close() throws Exception { if (database != null) { database.close(); } }
    @BeforeEach void fixture() throws Exception {
        schema = database.dataSource().getCurrentSchema() + "_" + sequence.incrementAndGet();
        execute("CREATE SCHEMA \"" + schema + "\"");
        directory = directory.toRealPath();
        Files.writeString(directory.resolve(MigrationDirectory.FORMAT_FILE), MigrationDirectory.FORMAT_HEADER);
    }
    @AfterEach void cleanup() throws Exception { if (schema != null) { execute("DROP SCHEMA \"" + schema + "\" CASCADE"); } }

    @Test void canonicalDdlAndAppliedHistoryCommitTogetherOnOneConnection() throws Exception {
        Path file = migration("-- private_marker is a comment, not executable SQL\n"
                + "CREATE TABLE " + table("items") + " (\"id\" INTEGER PRIMARY KEY);\n"
                + "ALTER TABLE " + table("items") + " ADD COLUMN \"label\" TEXT;\n"
                + "CREATE INDEX \"items_label\" ON " + table("items") + " (\"label\");\n");
        MigrationPlan plan = plan();
        AtomicInteger connections = new AtomicInteger(); List<String> statements = new ArrayList<>();
        DataSource observed = wrapped(connection -> connectionProxy((method, arguments) -> {
            if (method.getName().equals("createStatement")) {
                Statement statement = connection.createStatement();
                return Proxy.newProxyInstance(Statement.class.getClassLoader(), new Class<?>[]{Statement.class}, (object, operation, parameters) -> {
                    if (operation.getName().equals("execute")) { statements.add(parameters[0].toString()); }
                    return invoke(statement, operation, parameters);
                });
            }
            return invoke(connection, method, arguments);
        }), connections);
        MigrationRunResult result = run(observed, plan.digest());
        assertEquals(MigrationRunResult.Outcome.APPLIED, result.outcome());
        assertEquals(MigrationRunResult.Failure.NONE, result.failure()); assertEquals(1, connections.get());
        assertEquals(1, result.confirmed().size());
        assertEquals(history().applied(), result.confirmed());
        assertEquals(MigrationDirectory.discover(directory).getFirst().checksum(), result.confirmed().getFirst().checksum());
        assertTrue(exists("items")); assertTrue(exists("items_label"));
        assertFalse(statements.stream().anyMatch(sql -> sql.contains("private_marker")));
        String canonical = new PostgresqlDdlParser().parse(MigrationFile.fromDirectory(file.getParent().getFileName().toString(), Files.readAllBytes(file))).preview();
        assertEquals(canonical, statements.stream().filter(sql -> sql.startsWith("CREATE TABLE " + table("items"))
                || sql.startsWith("ALTER TABLE ") || sql.startsWith("CREATE INDEX ")).reduce("", String::concat));
    }

    @Test void aStatementFailureRollsBackEarlierDdlAndDoesNotRecordSuccess() throws Exception {
        execute("CREATE TABLE " + table("existing") + " (\"id\" integer)");
        execute("INSERT INTO " + table("existing") + " VALUES (73)");
        migration("CREATE TABLE " + table("uncommitted") + " (\"id\" INTEGER);\n"
                + "ALTER TABLE " + table("existing") + " ADD COLUMN \"id\" INTEGER;\n");
        MigrationRunResult result = run(settings.dataSource(), plan().digest());
        assertEquals(MigrationRunResult.Outcome.FAILED, result.outcome());
        assertEquals(MigrationRunResult.Failure.STATEMENT, result.failure());
        assertTrue(result.confirmed().isEmpty()); assertFalse(exists("uncommitted"));
        assertTrue(history().initialised()); assertTrue(history().applied().isEmpty());
        try (Connection connection = source.getConnection(); Statement query = connection.createStatement();
                ResultSet rows = query.executeQuery("SELECT id FROM " + table("existing"))) {
            assertTrue(rows.next()); assertEquals(73, rows.getInt(1)); assertFalse(rows.next());
        }
    }

    @Test void failedHistoryInsertRollsBackTheDdlInTheSameTransaction() throws Exception {
        migration("CREATE TABLE " + table("items") + " (\"id\" INTEGER);\n");
        AtomicInteger inserts = new AtomicInteger();
        DataSource failing = wrapped(connection -> connectionProxy((method, arguments) -> {
            if (method.getName().equals("prepareStatement") && arguments[0].toString().startsWith("INSERT INTO")) {
                inserts.incrementAndGet(); throw new SQLException("private_marker", "42501");
            }
            return invoke(connection, method, arguments);
        }), new AtomicInteger());
        MigrationRunResult result = run(failing, plan().digest());
        assertEquals(MigrationRunResult.Outcome.FAILED, result.outcome());
        assertEquals(MigrationRunResult.Failure.PERMISSION, result.failure()); assertEquals(1, inserts.get());
        assertFalse(exists("items")); assertTrue(history().applied().isEmpty());
        assertFalse(result.toString().contains("private_marker"));
    }

    @Test void changedFileOrHistoryInvalidatesApprovalBeforeBootstrapOrDdl() throws Exception {
        Path file = migration("CREATE TABLE " + table("items") + " (\"id\" INTEGER);\n");
        MigrationPlanDigest approved = plan().digest();
        Files.writeString(file, Files.readString(file) + "-- reviewed bytes changed\n");
        assertEquals(MigrationRunResult.Failure.APPROVAL, run(settings.dataSource(), approved).failure());
        assertFalse(history().initialised()); assertFalse(exists("items"));
        approved = plan().digest();
        execute(MigrationHistoryTable.createSql(new dev.tuprel.sql.SqlIdentifier(schema)));
        assertEquals(MigrationRunResult.Failure.APPROVAL, run(settings.dataSource(), approved).failure());
        assertFalse(exists("items")); assertTrue(history().applied().isEmpty());
    }

    @Test void unknownSqlAndNoChangesNeverBootstrapHistory() throws Exception {
        MigrationRunResult empty = run(settings.dataSource(), plan().digest());
        assertEquals(MigrationRunResult.Outcome.NO_CHANGES, empty.outcome()); assertFalse(history().initialised());
        migration("DROP TABLE " + table("items") + ";\n");
        MigrationRunResult blocked = run(settings.dataSource(), plan().digest());
        assertEquals(MigrationRunResult.Failure.BLOCKED, blocked.failure()); assertFalse(history().initialised());
    }

    @Test void aNoncanonicalHistoryIsNeverAdoptedOrRepaired() throws Exception {
        migration("CREATE TABLE " + table("items") + " (\"id\" INTEGER);\n");
        MigrationPlanDigest approval = plan().digest();
        execute("CREATE TABLE " + table(MigrationHistoryTable.NAME) + " (sentinel integer)");
        execute("INSERT INTO " + table(MigrationHistoryTable.NAME) + " VALUES (73)");
        assertEquals(MigrationRunResult.Failure.HISTORY, run(settings.dataSource(), approval).failure());
        assertFalse(exists("items"));
    }

    @Test void extraHistoryIndexesAreNotTrustedForExecution() throws Exception {
        migration("CREATE TABLE " + table("items") + " (\"id\" INTEGER);\n");
        execute(MigrationHistoryTable.createSql(new dev.tuprel.sql.SqlIdentifier(schema)));
        execute("CREATE INDEX \"extra\" ON " + table(MigrationHistoryTable.NAME) + " (state)");
        MigrationRunResult result = run(settings.dataSource(), plan().digest());
        assertEquals(MigrationRunResult.Failure.CATALOG, result.failure());
        assertFalse(exists("items")); assertTrue(history().applied().isEmpty()); assertTrue(exists("extra"));
    }

    @Test void inheritanceRlsRulesTriggersAndViewsAreOutsideTheExecutionSubset() throws Exception {
        for (String fixture : List.of("inheritance", "rls", "rules", "triggers", "view", "partition")) {
            String name = fixture + "_target";
            switch (fixture) {
                case "view" -> execute("CREATE VIEW " + table(name) + " AS SELECT 1 AS id");
                case "partition" -> execute("CREATE TABLE " + table(name) + " (id integer) PARTITION BY RANGE (id)");
                default -> {
                    execute("CREATE TABLE " + table(name) + " (id integer)");
                    switch (fixture) {
                        case "inheritance" -> execute("CREATE TABLE " + table("child") + " () INHERITS (" + table(name) + ")");
                        case "rls" -> execute("ALTER TABLE " + table(name) + " ENABLE ROW LEVEL SECURITY");
                        case "rules" -> execute("CREATE RULE ignore_insert AS ON INSERT TO " + table(name) + " DO INSTEAD NOTHING");
                        case "triggers" -> {
                            execute("CREATE FUNCTION " + table("hook") + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RETURN NEW; END $$");
                            execute("CREATE TRIGGER hook BEFORE INSERT ON " + table(name) + " FOR EACH ROW EXECUTE FUNCTION " + table("hook") + "()");
                        }
                        default -> fail("Unexpected fixture");
                    }
                }
            }
            migration("ALTER TABLE " + table(name) + " ADD COLUMN \"label\" TEXT;\n");
            MigrationRunResult result = run(settings.dataSource(), plan().digest());
            assertEquals(MigrationRunResult.Failure.CATALOG, result.failure(), fixture);
            assertTrue(history().applied().isEmpty());
        }
    }

    @Test void indexesRequireKnownBuiltInTypesAndDefaultCollation() throws Exception {
        execute("CREATE DOMAIN " + table("custom") + " AS integer");
        execute("CREATE TABLE " + table("items") + " (custom " + table("custom") + ", label text COLLATE \"C\")");
        for (String column : List.of("custom", "label")) {
            migration("CREATE INDEX \"items_index\" ON " + table("items") + " (\"" + column + "\");\n");
            assertEquals(MigrationRunResult.Failure.CATALOG, run(settings.dataSource(), plan().digest()).failure());
            assertFalse(exists("items_index")); assertTrue(history().applied().isEmpty());
        }
    }

    @Test void anObservedActiveEventTriggerBlocksBeforeBootstrapWithoutAdminOperations() throws Exception {
        migration("CREATE TABLE " + table("items") + " (\"id\" INTEGER);\n");
        DataSource blocked = wrapped(connection -> connectionProxy((method, arguments) -> {
            Object value = invoke(connection, method, arguments);
            if (method.getName().equals("prepareStatement") && arguments[0].toString().contains("pg_event_trigger")) {
                return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[]{PreparedStatement.class}, (query, operation, parameters) -> {
                    Object result = invoke(value, operation, parameters);
                    if (operation.getName().equals("executeQuery")) {
                        return Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class<?>[]{ResultSet.class}, (rows, read, fields) -> {
                            if (read.getName().equals("getBoolean") && fields[0].equals(1)) { return false; }
                            return invoke(result, read, fields);
                        });
                    }
                    return result;
                });
            }
            return value;
        }), new AtomicInteger());
        assertEquals(MigrationRunResult.Failure.CATALOG, run(blocked, plan().digest()).failure());
        assertFalse(history().initialised()); assertFalse(exists("items"));
    }

    @Test void usageWithoutCreatePrivilegeCannotBootstrapHistory() throws Exception {
        boolean container = System.getProperty("tuprel.test.database", "local").equals("container");
        String original = schema;
        String restricted = new dev.tuprel.sql.SqlIdentifier(schema + "_ro").name();
        String principal = new dev.tuprel.sql.SqlIdentifier(container ? "tuprel_runner_restricted" : source.getUser()).name();
        boolean created = false;
        boolean roleCreated = false;
        try (Connection setup = source.getConnection(); Statement statement = setup.createStatement()) {
            statement.setQueryTimeout(20);
            try {
                // Only the disposable CI database creates a login; local mode uses the existing user/NOLOGIN owner.
                String password = container ? java.util.UUID.randomUUID().toString() : source.getPassword();
                if (container) {
                    // Generated UUID is trusted fixture data, never user input, persisted or logged.
                    statement.execute("CREATE ROLE tuprel_runner_restricted LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS PASSWORD '"
                            + password + "'");
                    roleCreated = true;
                }
                statement.execute("CREATE SCHEMA \"" + restricted + "\"" + (container ? "" : " AUTHORIZATION tuprel_status_reader"));
                created = true;
                if (!container) { statement.execute("SET ROLE tuprel_status_reader"); }
                statement.execute("GRANT USAGE ON SCHEMA \"" + restricted + "\" TO \"" + principal + "\"");
                statement.execute("RESET ROLE");
                schema = restricted;
                MigrationDatabaseSettings restrictedSettings = MigrationDatabaseSettings.fromEnvironment(Map.of("TUPREL_DATABASE_URL",
                        "jdbc:postgresql://" + source.getServerNames()[0] + ":" + source.getPortNumbers()[0] + "/" + source.getDatabaseName(),
                        "TUPREL_DATABASE_USER", principal, "TUPREL_DATABASE_PASSWORD", password));
                DataSource restrictedSource = restrictedSettings.dataSource();
                try (Connection connection = restrictedSource.getConnection(); PreparedStatement query = connection.prepareStatement(
                        "SELECT pg_catalog.has_schema_privilege(?, 'CREATE')")) {
                    assertTrue(restrictedSettings.matches(connection, new dev.tuprel.sql.SqlIdentifier(restricted)));
                    query.setString(1, restricted);
                    try (ResultSet rows = query.executeQuery()) { assertTrue(rows.next()); assertFalse(rows.getBoolean(1)); }
                }
                migration("CREATE TABLE " + table("items") + " (\"id\" INTEGER);\n");
                MigrationPlan plan = new PostgresqlMigrationPlanCompiler().compile(restrictedSettings.planTarget(schema),
                        MigrationDirectory.discover(directory), history());
                MigrationRunResult result = MigrationRunner.applyOne(restrictedSource, restrictedSettings, schema, directory, plan.digest());
                assertEquals(MigrationRunResult.Outcome.FAILED, result.outcome(), result::toString);
                assertEquals(MigrationRunResult.Failure.PERMISSION, result.failure(), result::toString);
                assertTrue(result.confirmed().isEmpty()); assertFalse(exists("items")); assertFalse(history().initialised());
            } finally {
                schema = original;
                if (created) {
                    if (!container) { statement.execute("SET ROLE tuprel_status_reader"); }
                    statement.execute("DROP SCHEMA \"" + restricted + "\" CASCADE");
                    statement.execute("RESET ROLE");
                }
                if (roleCreated) { statement.execute("DROP ROLE tuprel_runner_restricted"); }
            }
        }
    }

    @Test void lostCommitAcknowledgementDiscardsAndNeverClaimsRollbackOrRetries() throws Exception {
        migration("CREATE TABLE " + table("items") + " (\"id\" INTEGER);\n");
        AtomicInteger commits = new AtomicInteger(); AtomicBoolean lost = new AtomicBoolean();
        List<Connection> owned = new ArrayList<>();
        DataSource failing = wrapped(connection -> {
            owned.add(connection);
            return connectionProxy((method, arguments) -> {
                if (lost.get() && List.of("prepareStatement", "createStatement", "rollback", "commit", "setAutoCommit").contains(method.getName())) {
                    throw new AssertionError("No transactional SQL or recovery after lost acknowledgement");
                }
                Object result = invoke(connection, method, arguments);
                if (method.getName().equals("commit") && commits.incrementAndGet() == 2) {
                    lost.set(true); throw new SQLException("private_marker", "08006");
                }
                return result;
            });
        }, new AtomicInteger());
        MigrationRunResult result = run(failing, plan().digest());
        assertEquals(MigrationRunResult.Outcome.OUTCOME_UNKNOWN, result.outcome());
        assertEquals(MigrationRunResult.Failure.COMMIT, result.failure());
        assertTrue(result.confirmed().isEmpty()); assertEquals(2, commits.get());
        assertTrue(owned.getFirst().isClosed()); assertTrue(exists("items")); assertEquals(1, history().applied().size());
        assertFalse(result.toString().contains("private_marker"));
    }

    @Test void cleanupFailureDoesNotEraseConfirmedCommitEvidence() throws Exception {
        migration("CREATE TABLE " + table("items") + " (\"id\" INTEGER);\n");
        AtomicInteger commits = new AtomicInteger();
        DataSource failing = wrapped(connection -> connectionProxy((method, arguments) -> {
            if (method.getName().equals("close")) { throw new SQLException("private_marker"); }
            if (method.getName().equals("commit")) { commits.incrementAndGet(); }
            if (method.getName().equals("rollback") && commits.get() == 2) { fail("No rollback after confirmed commit"); }
            return invoke(connection, method, arguments);
        }), new AtomicInteger());
        MigrationRunResult result = run(failing, plan().digest());
        assertEquals(MigrationRunResult.Outcome.FAILED, result.outcome());
        assertEquals(MigrationRunResult.Failure.CLEANUP, result.failure());
        assertEquals(history().applied(), result.confirmed()); assertEquals(1, result.confirmed().size()); assertTrue(exists("items"));
    }

    private Path migration(String sql) throws Exception {
        Path child = directory.resolve("20261031_120000_initial"); Files.createDirectories(child);
        return Files.writeString(child.resolve("migration.sql"), sql);
    }
    private String table(String name) { return "\"" + schema + "\".\"" + new dev.tuprel.sql.SqlIdentifier(name).name() + "\""; }
    private MigrationHistory history() throws Exception { return new PostgresqlMigrationHistory(source).read(schema); }
    private MigrationPlan plan() throws Exception {
        return new PostgresqlMigrationPlanCompiler().compile(settings.planTarget(schema), MigrationDirectory.discover(directory), history());
    }
    private MigrationRunResult run(DataSource dataSource, MigrationPlanDigest digest) {
        return MigrationRunner.applyOne(dataSource, settings, schema, directory, digest);
    }
    private boolean exists(String name) throws Exception {
        try (Connection connection = source.getConnection(); PreparedStatement query = connection.prepareStatement("""
                SELECT EXISTS (SELECT 1 FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = ? AND c.relname = ?)
                """)) {
            query.setString(1, schema); query.setString(2, name);
            try (ResultSet rows = query.executeQuery()) { assertTrue(rows.next()); return rows.getBoolean(1); }
        }
    }
    private static void execute(String sql) throws Exception {
        try (Connection connection = source.getConnection(); Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(20); statement.execute(sql);
        }
    }
    @FunctionalInterface private interface Wrapper { Connection wrap(Connection connection) throws Exception; }
    @FunctionalInterface private interface Call { Object invoke(Method method, Object[] arguments) throws Throwable; }
    private static DataSource wrapped(Wrapper wrapper, AtomicInteger attempts) {
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class}, (object, method, arguments) -> {
            if (method.getName().equals("getConnection")) { attempts.incrementAndGet(); return wrapper.wrap(settings.dataSource().getConnection()); }
            return invoke(source, method, arguments);
        });
    }
    private static Connection connectionProxy(Call call) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (object, method, arguments) -> call.invoke(method, arguments));
    }
    private static Object invoke(Object object, Method method, Object[] arguments) throws Throwable {
        try { return method.invoke(object, arguments); } catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
}
