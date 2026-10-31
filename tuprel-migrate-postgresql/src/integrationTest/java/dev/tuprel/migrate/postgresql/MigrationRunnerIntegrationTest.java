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
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Nested;
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
        MigrationExecutionResult result = run(observed, plan.digest());
        assertEquals(MigrationExecutionResult.Outcome.APPLIED, result.outcome());
        assertEquals(MigrationExecutionResult.Failure.NONE, result.failure()); assertEquals(1, connections.get());
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
        MigrationExecutionResult result = run(settings.dataSource(), plan().digest());
        assertEquals(MigrationExecutionResult.Outcome.FAILED, result.outcome());
        assertEquals(MigrationExecutionResult.Failure.STATEMENT, result.failure());
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
        MigrationExecutionResult result = run(failing, plan().digest());
        assertEquals(MigrationExecutionResult.Outcome.FAILED, result.outcome());
        assertEquals(MigrationExecutionResult.Failure.PERMISSION, result.failure()); assertEquals(1, inserts.get());
        assertFalse(exists("items")); assertTrue(history().applied().isEmpty());
        assertFalse(result.toString().contains("private_marker"));
    }

    @Test void changedFileOrHistoryInvalidatesApprovalBeforeBootstrapOrDdl() throws Exception {
        Path file = migration("CREATE TABLE " + table("items") + " (\"id\" INTEGER);\n");
        MigrationPlanDigest approved = plan().digest();
        Files.writeString(file, Files.readString(file) + "-- reviewed bytes changed\n");
        assertEquals(MigrationExecutionResult.Failure.APPROVAL, run(settings.dataSource(), approved).failure());
        assertFalse(history().initialised()); assertFalse(exists("items"));
        approved = plan().digest();
        execute(MigrationHistoryTable.createSql(new dev.tuprel.sql.SqlIdentifier(schema)));
        assertEquals(MigrationExecutionResult.Failure.APPROVAL, run(settings.dataSource(), approved).failure());
        assertFalse(exists("items")); assertTrue(history().applied().isEmpty());
    }

    @Test void unknownSqlAndNoChangesNeverBootstrapHistory() throws Exception {
        MigrationExecutionResult empty = run(settings.dataSource(), plan().digest());
        assertEquals(MigrationExecutionResult.Outcome.NO_CHANGES, empty.outcome()); assertFalse(history().initialised());
        migration("DROP TABLE " + table("items") + ";\n");
        MigrationExecutionResult blocked = run(settings.dataSource(), plan().digest());
        assertEquals(MigrationExecutionResult.Failure.BLOCKED, blocked.failure()); assertFalse(history().initialised());
    }

    @Test void aNoncanonicalHistoryIsNeverAdoptedOrRepaired() throws Exception {
        migration("CREATE TABLE " + table("items") + " (\"id\" INTEGER);\n");
        MigrationPlanDigest approval = plan().digest();
        execute("CREATE TABLE " + table(MigrationHistoryTable.NAME) + " (sentinel integer)");
        execute("INSERT INTO " + table(MigrationHistoryTable.NAME) + " VALUES (73)");
        assertEquals(MigrationExecutionResult.Failure.HISTORY, run(settings.dataSource(), approval).failure());
        assertFalse(exists("items"));
    }

    @Test void extraHistoryIndexesAreNotTrustedForExecution() throws Exception {
        migration("CREATE TABLE " + table("items") + " (\"id\" INTEGER);\n");
        execute(MigrationHistoryTable.createSql(new dev.tuprel.sql.SqlIdentifier(schema)));
        execute("CREATE INDEX \"extra\" ON " + table(MigrationHistoryTable.NAME) + " (state)");
        MigrationExecutionResult result = run(settings.dataSource(), plan().digest());
        assertEquals(MigrationExecutionResult.Failure.CATALOG, result.failure());
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
            MigrationExecutionResult result = run(settings.dataSource(), plan().digest());
            assertEquals(MigrationExecutionResult.Failure.CATALOG, result.failure(), fixture);
            assertTrue(history().applied().isEmpty());
        }
    }

    @Test void indexesRequireKnownBuiltInTypesAndDefaultCollation() throws Exception {
        execute("CREATE DOMAIN " + table("custom") + " AS integer");
        execute("CREATE TABLE " + table("items") + " (custom " + table("custom") + ", label text COLLATE \"C\")");
        for (String column : List.of("custom", "label")) {
            migration("CREATE INDEX \"items_index\" ON " + table("items") + " (\"" + column + "\");\n");
            assertEquals(MigrationExecutionResult.Failure.CATALOG, run(settings.dataSource(), plan().digest()).failure());
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
        assertEquals(MigrationExecutionResult.Failure.CATALOG, run(blocked, plan().digest()).failure());
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
                MigrationExecutionResult result = new PostgresqlMigrationRunner(restrictedSource, restrictedSettings).deploy(schema, directory, plan.digest());
                assertEquals(MigrationExecutionResult.Outcome.FAILED, result.outcome(), result::toString);
                assertEquals(MigrationExecutionResult.Failure.PERMISSION, result.failure(), result::toString);
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
        MigrationPlanDigest approved = plan().digest();
        MigrationExecutionResult result = run(failing, approved);
        assertEquals(MigrationExecutionResult.Outcome.OUTCOME_UNKNOWN, result.outcome());
        assertEquals(MigrationExecutionResult.Failure.COMMIT, result.failure());
        assertTrue(result.confirmed().isEmpty()); assertEquals(2, commits.get());
        assertTrue(owned.getFirst().isClosed()); assertTrue(exists("items")); assertEquals(1, history().applied().size());
        assertFalse(result.toString().contains("private_marker"));
        MigrationInspection reconciled = runner().inspect(schema, directory);
        assertEquals(MigrationPlan.State.NO_CHANGES, reconciled.plan().state());
        assertEquals(1, reconciled.history().applied().size());
        assertEquals(MigrationExecutionResult.Failure.APPROVAL, runner().deploy(schema, directory, approved).failure());
        assertEquals(MigrationExecutionResult.Outcome.NO_CHANGES,
                runner().deploy(schema, directory, reconciled.plan().digest()).outcome());
        assertEquals(MigrationExecutionResult.Outcome.OUTCOME_UNKNOWN, result.outcome());
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
        MigrationExecutionResult result = run(failing, plan().digest());
        assertEquals(MigrationExecutionResult.Outcome.FAILED, result.outcome());
        assertEquals(MigrationExecutionResult.Failure.CLEANUP, result.failure());
        assertEquals(history().applied(), result.confirmed()); assertEquals(1, result.confirmed().size()); assertTrue(exists("items"));
    }

    @Nested class SequencesAndRecovery {
        @Test void appliesAnOrderedSequenceWithOneCommitPerMigrationAndNoGlobalRollback() throws Exception {
            threeMigrations();
            AtomicInteger commits = new AtomicInteger();
            DataSource observed = wrapped(connection -> connectionProxy((method, arguments) -> {
                if (method.getName().equals("commit")) { commits.incrementAndGet(); }
                return invoke(connection, method, arguments);
            }), new AtomicInteger());
            MigrationExecutionResult result = run(observed, plan().digest());
            assertEquals(MigrationExecutionResult.Outcome.APPLIED, result.outcome());
            assertEquals(3, result.confirmed().size()); assertEquals(4, commits.get());
            assertEquals(history().applied(), result.confirmed());
            assertTrue(exists("first")); assertTrue(exists("second")); assertTrue(exists("third"));
        }

        @Test void aLaterStatementFailurePreservesTheConfirmedPrefixAndStops() throws Exception {
            threeMigrations();
            execute("CREATE TABLE " + table("existing") + " (id integer)");
            migration("20261031_120001_second", "CREATE TABLE " + table("second") + " (\"id\" INTEGER);\n"
                    + "ALTER TABLE " + table("existing") + " ADD COLUMN \"id\" INTEGER;\n");
            MigrationExecutionResult result = run(settings.dataSource(), plan().digest());
            assertEquals(MigrationExecutionResult.Outcome.FAILED, result.outcome());
            assertEquals(MigrationExecutionResult.Failure.STATEMENT, result.failure());
            assertEquals(1, result.confirmed().size()); assertEquals(history().applied(), result.confirmed());
            assertTrue(exists("first")); assertFalse(exists("second")); assertFalse(exists("third"));
        }

        @Test void realBackendTerminationBeforeCommitIsUnknownAndStopsTheSequence() throws Exception {
            threeMigrations();
            AtomicInteger commits = new AtomicInteger(); AtomicBoolean terminated = new AtomicBoolean();
            List<Connection> owned = new ArrayList<>();
            DataSource failing = wrapped(connection -> {
                owned.add(connection);
                int pid = ownedBackendPid(connection);
                return connectionProxy((method, arguments) -> {
                    if (terminated.get() && List.of("prepareStatement", "createStatement", "rollback", "commit", "setAutoCommit").contains(method.getName())) {
                        fail("No SQL after losing the owned backend");
                    }
                    if (method.getName().equals("commit") && commits.incrementAndGet() == 3) {
                        terminateOwnedBackend(pid); terminated.set(true);
                    }
                    return invoke(connection, method, arguments);
                });
            }, new AtomicInteger());
            MigrationPlanDigest approved = plan().digest();
            MigrationExecutionResult result = run(failing, approved);
            assertEquals(MigrationExecutionResult.Outcome.OUTCOME_UNKNOWN, result.outcome());
            assertEquals(MigrationExecutionResult.Failure.COMMIT, result.failure());
            assertEquals(1, result.confirmed().size()); assertTrue(owned.getFirst().isClosed());
            assertTrue(exists("first")); assertFalse(exists("second")); assertFalse(exists("third"));
            MigrationInspection current = runner().inspect(schema, directory);
            assertEquals(result.confirmed(), current.history().applied()); assertEquals(2, current.plan().scripts().size());
            assertEquals(MigrationExecutionResult.Failure.APPROVAL, runner().deploy(schema, directory, approved).failure());
            assertFalse(exists("second")); assertFalse(exists("third"));
        }

        @Test void failedRollbackDiscardsWithoutAdvancingOrClaimingRollback() throws Exception {
            threeMigrations();
            execute("CREATE TABLE " + table("existing") + " (id integer)");
            migration("20261031_120001_second", "CREATE TABLE " + table("second") + " (\"id\" INTEGER);\n"
                    + "ALTER TABLE " + table("existing") + " ADD COLUMN \"id\" INTEGER;\n");
            AtomicInteger commits = new AtomicInteger(); List<Connection> owned = new ArrayList<>();
            DataSource failing = wrapped(connection -> {
                owned.add(connection);
                return connectionProxy((method, arguments) -> {
                    if (method.getName().equals("commit")) { commits.incrementAndGet(); }
                    if (method.getName().equals("rollback") && commits.get() == 2) { throw new SQLException("private_marker", "08006"); }
                    return invoke(connection, method, arguments);
                });
            }, new AtomicInteger());
            MigrationExecutionResult result = run(failing, plan().digest());
            assertEquals(MigrationExecutionResult.Outcome.OUTCOME_UNKNOWN, result.outcome());
            assertEquals(MigrationExecutionResult.Failure.ROLLBACK, result.failure());
            assertEquals(1, result.confirmed().size()); assertTrue(owned.getFirst().isClosed());
            MigrationInspection current = runner().inspect(schema, directory);
            assertEquals(result.confirmed(), current.history().applied()); assertEquals(2, current.plan().scripts().size());
            assertFalse(exists("second")); assertFalse(exists("third"));
        }

        @Test void bootstrapCommitUncertaintyRequiresFreshInspectionBeforeAnotherInvocation() throws Exception {
            for (boolean serverCommitted : List.of(false, true)) {
                migration("CREATE TABLE " + table("items") + " (\"id\" INTEGER);\n");
                MigrationPlanDigest approved = plan().digest(); AtomicInteger commits = new AtomicInteger();
                DataSource failing = wrapped(connection -> connectionProxy((method, arguments) -> {
                    if (method.getName().equals("commit") && commits.incrementAndGet() == 1) {
                        if (serverCommitted) { invoke(connection, method, arguments); }
                        throw new SQLException("private_marker", "08006");
                    }
                    return invoke(connection, method, arguments);
                }), new AtomicInteger());
                MigrationExecutionResult result = run(failing, approved);
                assertEquals(MigrationExecutionResult.Outcome.OUTCOME_UNKNOWN, result.outcome());
                assertTrue(result.confirmed().isEmpty()); assertFalse(exists("items")); assertEquals(1, commits.get());
                MigrationInspection current = runner().inspect(schema, directory);
                assertEquals(serverCommitted, current.history().initialised());
                assertTrue(current.history().applied().isEmpty()); assertEquals(1, current.plan().scripts().size());
                if (serverCommitted) { assertNotEquals(approved, current.plan().digest()); }
                else { assertEquals(approved, current.plan().digest()); }
            }
        }

        @Test void relationLockTimeoutRollsBackAndDoesNotAffectTheOtherSession() throws Exception {
            execute("CREATE TABLE " + table("items") + " (id integer)");
            migration("ALTER TABLE " + table("items") + " ADD COLUMN \"label\" TEXT;\n");
            MigrationPlanDigest approved = plan().digest();
            try (Connection peer = source.getConnection(); Statement lock = peer.createStatement()) {
                peer.setAutoCommit(false); lock.setQueryTimeout(20);
                lock.execute("LOCK TABLE ONLY " + table("items") + " IN ACCESS EXCLUSIVE MODE");
                MigrationExecutionResult result = CompletableFuture.supplyAsync(() -> run(settings.dataSource(), approved)).get(45, TimeUnit.SECONDS);
                assertEquals(MigrationExecutionResult.Outcome.FAILED, result.outcome());
                assertEquals(MigrationExecutionResult.Failure.TIMEOUT, result.failure()); assertTrue(result.confirmed().isEmpty());
                assertFalse(peer.isClosed()); peer.rollback();
            }
            assertTrue(history().applied().isEmpty());
            MigrationInspection current = runner().inspect(schema, directory);
            assertEquals(MigrationExecutionResult.Outcome.APPLIED, runner().deploy(schema, directory, current.plan().digest()).outcome());
        }

        @Test void twoExecutorsCannotReuseTheSameApprovedInitialState() throws Exception {
            migration("CREATE TABLE " + table("items") + " (\"id\" INTEGER);\n");
            MigrationPlanDigest approved = plan().digest();
            CountDownLatch atDdl = new CountDownLatch(1), release = new CountDownLatch(1), secondTried = new CountDownLatch(1);
            DataSource first = wrapped(connection -> connectionProxy((method, arguments) -> {
                if (method.getName().equals("createStatement")) {
                    Statement statement = connection.createStatement();
                    return Proxy.newProxyInstance(Statement.class.getClassLoader(), new Class<?>[]{Statement.class}, (object, operation, parameters) -> {
                        if (operation.getName().equals("execute") && parameters[0].toString().startsWith("CREATE TABLE " + table("items"))) {
                            atDdl.countDown(); if (!release.await(15, TimeUnit.SECONDS)) { fail("First executor barrier timed out"); }
                        }
                        return invoke(statement, operation, parameters);
                    });
                }
                return invoke(connection, method, arguments);
            }), new AtomicInteger());
            DataSource second = wrapped(connection -> connectionProxy((method, arguments) -> {
                Object value = invoke(connection, method, arguments);
                if (method.getName().equals("prepareStatement") && arguments[0].toString().contains("pg_try_advisory_lock")) {
                    return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[]{PreparedStatement.class}, (query, operation, parameters) -> {
                        Object result = invoke(value, operation, parameters);
                        if (operation.getName().equals("executeQuery")) { secondTried.countDown(); }
                        return result;
                    });
                }
                return value;
            }), new AtomicInteger());
            CompletableFuture<MigrationExecutionResult> one = CompletableFuture.supplyAsync(() -> run(first, approved));
            CompletableFuture<MigrationExecutionResult> two = null;
            try {
                assertTrue(atDdl.await(15, TimeUnit.SECONDS));
                two = CompletableFuture.supplyAsync(() -> run(second, approved));
                assertTrue(secondTried.await(15, TimeUnit.SECONDS)); release.countDown();
                assertEquals(MigrationExecutionResult.Outcome.APPLIED, one.get(45, TimeUnit.SECONDS).outcome());
                MigrationExecutionResult stale = two.get(45, TimeUnit.SECONDS);
                assertEquals(MigrationExecutionResult.Outcome.REFUSED, stale.outcome());
                assertEquals(MigrationExecutionResult.Failure.APPROVAL, stale.failure());
                assertEquals(1, history().applied().size());
            } finally {
                release.countDown(); one.get(45, TimeUnit.SECONDS);
                if (two != null) { two.get(45, TimeUnit.SECONDS); }
            }
        }

        @Test void inspectionNeverReturnsASnapshotAfterRollbackOrCleanupFailure() throws Exception {
            migration("CREATE TABLE " + table("items") + " (\"id\" INTEGER);\n");
            for (String failurePoint : List.of("rollback", "close")) {
                List<Connection> owned = new ArrayList<>();
                DataSource failing = wrapped(connection -> {
                    owned.add(connection);
                    return connectionProxy((method, arguments) -> {
                        if (method.getName().equals(failurePoint)) { throw new SQLException("private_marker", "08006"); }
                        return invoke(connection, method, arguments);
                    });
                }, new AtomicInteger());
                MigrationInspectionException failure = assertThrows(MigrationInspectionException.class,
                        () -> new PostgresqlMigrationRunner(failing, settings).inspect(schema, directory));
                assertEquals(failurePoint.equals("rollback") ? MigrationExecutionResult.Failure.ROLLBACK : MigrationExecutionResult.Failure.CLEANUP, failure.reason());
                assertNull(failure.getCause()); assertEquals(0, failure.getSuppressed().length); assertTrue(owned.getFirst().isClosed());
            }
            MigrationInspection current = runner().inspect(schema, directory);
            assertFalse(current.history().initialised()); assertEquals(1, current.plan().scripts().size()); assertFalse(exists("items"));
        }

        @Test void outOfBandHistoryChangesBetweenCommitsBlockTheNextMigration() throws Exception {
            threeMigrations(); AtomicInteger commits = new AtomicInteger();
            DataSource changed = wrapped(connection -> connectionProxy((method, arguments) -> {
                Object value = invoke(connection, method, arguments);
                if (method.getName().equals("commit") && commits.incrementAndGet() == 2) {
                    try (Connection peer = source.getConnection(); PreparedStatement update = peer.prepareStatement(
                            "UPDATE " + table(MigrationHistoryTable.NAME) + " SET migration_name = ? WHERE migration_id = ?")) {
                        update.setString(1, "tampered"); update.setString(2, "20261031_120000"); assertEquals(1, update.executeUpdate());
                    }
                }
                return value;
            }), new AtomicInteger());
            MigrationExecutionResult result = run(changed, plan().digest());
            assertEquals(MigrationExecutionResult.Outcome.REFUSED, result.outcome());
            assertEquals(MigrationExecutionResult.Failure.HISTORY, result.failure()); assertEquals(1, result.confirmed().size());
            assertTrue(exists("first")); assertFalse(exists("second")); assertFalse(exists("third"));
            MigrationInspection current = runner().inspect(schema, directory);
            assertEquals(MigrationPlan.State.BLOCKED, current.plan().state());
            assertEquals("tampered", current.history().applied().getFirst().name());
        }

        @Test void interruptionBetweenMigrationsPreservesThePrefixAndDoesNotContinue() throws Exception {
            threeMigrations(); MigrationPlanDigest approved = plan().digest(); AtomicInteger commits = new AtomicInteger();
            DataSource interrupted = wrapped(connection -> connectionProxy((method, arguments) -> {
                Object value = invoke(connection, method, arguments);
                if (method.getName().equals("commit") && commits.incrementAndGet() == 2) { Thread.currentThread().interrupt(); }
                return value;
            }), new AtomicInteger());
            Map.Entry<MigrationExecutionResult, Boolean> response = CompletableFuture.supplyAsync(() -> {
                try { return Map.entry(run(interrupted, approved), Thread.currentThread().isInterrupted()); }
                finally { Thread.interrupted(); }
            }).get(30, TimeUnit.SECONDS);
            MigrationExecutionResult result = response.getKey();
            assertTrue(response.getValue()); assertEquals(MigrationExecutionResult.Outcome.REFUSED, result.outcome());
            assertEquals(MigrationExecutionResult.Failure.INTERRUPTED, result.failure()); assertEquals(1, result.confirmed().size());
            assertEquals(result.confirmed(), runner().inspect(schema, directory).history().applied());
            assertFalse(exists("second")); assertFalse(exists("third"));
        }

        @Test void realCancellationConfirmsRollbackAndRetainsTheServerTimeouts() throws Exception {
            execute("CREATE TABLE " + table("items") + " (id integer)");
            migration("ALTER TABLE " + table("items") + " ADD COLUMN \"label\" TEXT;\n");
            MigrationPlanDigest approved = plan().digest(); AtomicInteger pid = new AtomicInteger();
            AtomicReference<String> sqlState = new AtomicReference<>(); CountDownLatch atLock = new CountDownLatch(1);
            DataSource cancellable = wrapped(connection -> {
                pid.set(ownedBackendPid(connection));
                return connectionProxy((method, arguments) -> {
                    if (method.getName().equals("createStatement")) {
                        Statement statement = connection.createStatement();
                        return Proxy.newProxyInstance(Statement.class.getClassLoader(), new Class<?>[]{Statement.class}, (object, operation, parameters) -> {
                            if (operation.getName().equals("execute") && parameters[0].toString().startsWith("LOCK TABLE ONLY " + table("items"))) {
                                try (PreparedStatement query = connection.prepareStatement(
                                        "SELECT pg_catalog.current_setting('lock_timeout'), pg_catalog.current_setting('statement_timeout')");
                                        ResultSet rows = query.executeQuery()) {
                                    assertTrue(rows.next()); assertEquals("10s", rows.getString(1)); assertEquals("20s", rows.getString(2));
                                }
                                atLock.countDown();
                                try { return invoke(statement, operation, parameters); }
                                catch (SQLException failure) { sqlState.set(failure.getSQLState()); throw failure; }
                            }
                            return invoke(statement, operation, parameters);
                        });
                    }
                    return invoke(connection, method, arguments);
                });
            }, new AtomicInteger());
            try (Connection peer = source.getConnection(); Statement held = peer.createStatement()) {
                peer.setAutoCommit(false); held.setQueryTimeout(20);
                held.execute("LOCK TABLE ONLY " + table("items") + " IN ACCESS EXCLUSIVE MODE");
                CompletableFuture<MigrationExecutionResult> pending = CompletableFuture.supplyAsync(() -> run(cancellable, approved));
                try {
                    assertTrue(atLock.await(15, TimeUnit.SECONDS));
                    awaitRelationWait(pid.get());
                    try (Connection control = source.getConnection(); PreparedStatement cancel = control.prepareStatement("SELECT pg_catalog.pg_cancel_backend(?)")) {
                        cancel.setInt(1, pid.get()); cancel.setQueryTimeout(10);
                        try (ResultSet rows = cancel.executeQuery()) { assertTrue(rows.next()); assertTrue(rows.getBoolean(1)); }
                    }
                    MigrationExecutionResult result = pending.get(30, TimeUnit.SECONDS);
                    assertEquals("57014", sqlState.get()); assertEquals(MigrationExecutionResult.Outcome.FAILED, result.outcome());
                    assertEquals(MigrationExecutionResult.Failure.TIMEOUT, result.failure()); assertTrue(result.confirmed().isEmpty());
                } finally { peer.rollback(); pending.get(45, TimeUnit.SECONDS); }
            }
            assertTrue(history().applied().isEmpty()); assertEquals(1, runner().inspect(schema, directory).plan().scripts().size());
        }

        private void threeMigrations() throws Exception {
            migration("20261031_120000_first", "CREATE TABLE " + table("first") + " (\"id\" INTEGER);\n");
            migration("20261031_120001_second", "CREATE TABLE " + table("second") + " (\"id\" INTEGER);\n");
            migration("20261031_120002_third", "CREATE TABLE " + table("third") + " (\"id\" INTEGER);\n");
        }
    }

    private Path migration(String sql) throws Exception {
        return migration("20261031_120000_initial", sql);
    }
    private Path migration(String name, String sql) throws Exception {
        Path child = directory.resolve(name); Files.createDirectories(child);
        return Files.writeString(child.resolve("migration.sql"), sql);
    }
    private String table(String name) { return "\"" + schema + "\".\"" + new dev.tuprel.sql.SqlIdentifier(name).name() + "\""; }
    private MigrationHistory history() throws Exception { return new PostgresqlMigrationHistory(source).read(schema); }
    private MigrationPlan plan() throws Exception {
        return new PostgresqlMigrationPlanCompiler().compile(settings.planTarget(schema), MigrationDirectory.discover(directory), history());
    }
    private MigrationExecutionResult run(DataSource dataSource, MigrationPlanDigest digest) {
        return new PostgresqlMigrationRunner(dataSource, settings).deploy(schema, directory, digest);
    }
    private PostgresqlMigrationRunner runner() { return new PostgresqlMigrationRunner(settings.dataSource(), settings); }
    private static void terminateOwnedBackend(int pid) throws Exception {
        try (Connection peer = source.getConnection(); PreparedStatement query = peer.prepareStatement("SELECT pg_catalog.pg_terminate_backend(?)")) {
            query.setQueryTimeout(10); query.setInt(1, pid);
            try (ResultSet rows = query.executeQuery()) { assertTrue(rows.next()); assertTrue(rows.getBoolean(1)); }
        }
    }
    private static int ownedBackendPid(Connection connection) throws Exception {
        try (PreparedStatement query = connection.prepareStatement("SELECT pg_catalog.pg_backend_pid()")) {
            query.setQueryTimeout(10);
            try (ResultSet rows = query.executeQuery()) { assertTrue(rows.next()); return rows.getInt(1); }
        }
    }
    private static void awaitRelationWait(int pid) throws Exception {
        long started = System.nanoTime();
        try (Connection peer = source.getConnection(); PreparedStatement query = peer.prepareStatement(
                "SELECT EXISTS (SELECT 1 FROM pg_catalog.pg_locks WHERE pid = ? AND locktype = 'relation' AND NOT granted)")) {
            query.setInt(1, pid); query.setQueryTimeout(5);
            while (System.nanoTime() - started < TimeUnit.SECONDS.toNanos(5)) {
                try (ResultSet rows = query.executeQuery()) { assertTrue(rows.next()); if (rows.getBoolean(1)) { return; } }
                TimeUnit.MILLISECONDS.sleep(20);
            }
        }
        fail("Owned backend did not enter a relation-lock wait");
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
