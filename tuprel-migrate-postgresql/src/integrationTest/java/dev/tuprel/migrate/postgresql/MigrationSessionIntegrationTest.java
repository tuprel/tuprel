package dev.tuprel.migrate.postgresql;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuprel.sql.SqlIdentifier;
import dev.tuprel.testsupport.TestPostgresql;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

class MigrationSessionIntegrationTest {
    private static TestPostgresql database;
    private static PGSimpleDataSource source;
    private static MigrationDatabaseSettings settings;
    private static String schema;
    @BeforeAll static void open() throws Exception {
        database = TestPostgresql.open(); source = database.dataSource(); schema = source.getCurrentSchema();
        settings = configuration(source.getUser());
    }
    @AfterAll static void close() throws Exception { if (database != null) { database.close(); } }

    private static MigrationDatabaseSettings configuration(String user) throws Exception {
        return MigrationDatabaseSettings.fromEnvironment(Map.of(
                "TUPREL_DATABASE_URL", "jdbc:postgresql://" + source.getServerNames()[0] + ":"
                        + source.getPortNumbers()[0] + "/" + source.getDatabaseName(),
                "TUPREL_DATABASE_USER", user, "TUPREL_DATABASE_PASSWORD", source.getPassword()));
    }

    @Test void lockSurvivesCommitAndRollbackUntilSessionCloses() throws Exception {
        long key = MigrationSession.key(new SqlIdentifier(schema));
        try (Connection peer = source.getConnection()) {
            try (MigrationSession session = MigrationSession.open(settings.dataSource(), settings, schema)) {
                assertFalse(tryLock(peer, key));
                session.connection().setAutoCommit(false);
                session.connection().commit(); assertFalse(tryLock(peer, key));
                session.connection().rollback(); assertFalse(tryLock(peer, key));
            }
            assertTrue(tryLock(peer, key));
        }
        assertFalse(new PostgresqlMigrationHistory(source).read(schema).initialised());
    }

    @Test void contentionHasAFiniteDeadlineWithoutReentrantAcquisition() throws Exception {
        try (MigrationSession held = MigrationSession.open(settings.dataSource(), settings, schema)) {
            assertNotNull(held.connection());
            MigrationSessionException failure = assertThrows(MigrationSessionException.class,
                    () -> MigrationSession.open(settings.dataSource(), settings, schema, Duration.ofMillis(150)));
            assertEquals(MigrationSessionException.Reason.LOCK_BUSY, failure.reason());
        }
        try (MigrationSession available = MigrationSession.open(settings.dataSource(), settings, schema)) {
            assertNotNull(available.connection());
        }
    }

    @Test void preheldLocksAndActiveTransactionsAreRefusedAndDiscarded() throws Exception {
        Connection preheld = source.getConnection();
        assertTrue(tryLock(preheld, 77981L));
        assertEquals(MigrationSessionException.Reason.PREHELD_LOCK,
                assertThrows(MigrationSessionException.class,
                        () -> MigrationSession.open(single(preheld), settings, schema)).reason());
        assertTrue(preheld.isClosed());
        Connection active = source.getConnection(); active.setAutoCommit(false);
        assertEquals(MigrationSessionException.Reason.ACTIVE_TRANSACTION,
                assertThrows(MigrationSessionException.class,
                        () -> MigrationSession.open(single(active), settings, schema)).reason());
        assertTrue(active.isClosed());
    }

    @Test void configuredPrincipalAndSystemSchemaCannotBeAssumed() throws Exception {
        Connection actual = source.getConnection();
        assertEquals(MigrationSessionException.Reason.TARGET,
                assertThrows(MigrationSessionException.class,
                        () -> MigrationSession.open(single(actual), configuration("different_principal"), schema)).reason());
        assertTrue(actual.isClosed());
        assertEquals(MigrationSessionException.Reason.TARGET,
                assertThrows(MigrationSessionException.class,
                        () -> MigrationSession.open(settings.dataSource(), settings, "pg_catalog")).reason());
    }

    @Test void callerSearchPathCannotShadowCatalogChecksOrLockResolution() throws Exception {
        Connection actual = source.getConnection();
        try (Statement statement = actual.createStatement()) {
            statement.setQueryTimeout(10);
            statement.execute("SET search_path = \"" + schema + "\", pg_catalog");
        }
        try (MigrationSession session = MigrationSession.open(single(actual), settings, schema);
                Statement statement = session.connection().createStatement()) {
            statement.setQueryTimeout(10);
            try (java.sql.ResultSet result = statement.executeQuery("SHOW search_path")) {
                assertTrue(result.next()); assertEquals("pg_catalog", result.getString(1));
            }
        }
        assertTrue(actual.isClosed());
    }

    @Test void interruptionIsPreservedAndDoesNotLeakTheLock() throws Exception {
        CompletableFuture<Boolean> result = CompletableFuture.supplyAsync(() -> {
            Thread.currentThread().interrupt();
            try {
                MigrationSessionException exception = assertThrows(MigrationSessionException.class,
                        () -> MigrationSession.open(settings.dataSource(), settings, schema));
                return exception.reason() == MigrationSessionException.Reason.INTERRUPTED
                        && Thread.currentThread().isInterrupted();
            } finally { Thread.interrupted(); }
        });
        assertTrue(result.get(15, TimeUnit.SECONDS));
        try (MigrationSession available = MigrationSession.open(settings.dataSource(), settings, schema)) {
            assertNotNull(available.connection());
        }
    }

    @Test void failedRollbackAbortsWithoutEnablingAutoCommitOrRetrying() throws Exception {
        Connection actual = source.getConnection();
        AtomicBoolean fail = new AtomicBoolean();
        Connection proxy = proxy((object, method, arguments) -> {
            if (fail.get() && method.getName().equals("rollback")) { throw new SQLException("private_marker"); }
            if (fail.get() && method.getName().equals("setAutoCommit")) { throw new AssertionError("No auto-commit restoration"); }
            return invoke(actual, method, arguments);
        });
        MigrationSession session = MigrationSession.open(single(proxy), settings, schema);
        session.connection().setAutoCommit(false); fail.set(true);
        MigrationSessionException failure = assertThrows(MigrationSessionException.class, session::close);
        assertEquals(MigrationSessionException.Reason.OUTCOME_UNKNOWN, failure.reason());
        assertTrue(actual.isClosed()); assertNull(failure.getCause()); assertEquals(0, failure.getSuppressed().length);
        assertFalse(failure.toString().contains("private_marker"));
        assertThrows(IllegalStateException.class, session::connection);
    }

    @Test void waitingSessionCanBeInterruptedAfterAnUnsuccessfulLockAttempt() throws Exception {
        try (MigrationSession held = MigrationSession.open(settings.dataSource(), settings, schema);
                Connection actual = source.getConnection()) {
            assertNotNull(held.connection());
            CountDownLatch attempted = new CountDownLatch(1);
            Connection waiting = proxy((object, method, arguments) -> {
                Object value = invoke(actual, method, arguments);
                if (method.getName().equals("prepareStatement") && arguments[0].toString().contains("pg_try_advisory_lock")) {
                    return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[]{PreparedStatement.class},
                            (query, operation, parameters) -> {
                                Object result = invoke(value, operation, parameters);
                                if (operation.getName().equals("executeQuery")) { attempted.countDown(); }
                                return result;
                            });
                }
                return value;
            });
            CompletableFuture<Boolean> result = new CompletableFuture<>();
            Thread worker = new Thread(() -> {
                try {
                    MigrationSessionException failure = assertThrows(MigrationSessionException.class,
                            () -> MigrationSession.open(single(waiting), settings, schema));
                    result.complete(failure.reason() == MigrationSessionException.Reason.INTERRUPTED
                            && Thread.currentThread().isInterrupted());
                } catch (Throwable failure) { result.completeExceptionally(failure); }
            });
            worker.start();
            try {
                assertTrue(attempted.await(10, TimeUnit.SECONDS)); worker.interrupt();
                assertTrue(result.get(15, TimeUnit.SECONDS));
                assertTrue(actual.isClosed());
            } finally { worker.interrupt(); worker.join(15000); }
        }
    }

    @Test void failedUnlockOrCloseDiscardsAndRedactsDriverFailures() throws Exception {
        for (String failurePoint : new String[]{"unlock", "close"}) {
            Connection actual = source.getConnection();
            Connection proxy = proxy((object, method, arguments) -> {
                if ((failurePoint.equals("close") && method.getName().equals("close"))
                        || (failurePoint.equals("unlock") && method.getName().equals("prepareStatement")
                            && arguments[0].toString().contains("pg_advisory_unlock"))) {
                    throw new SQLException("private_marker");
                }
                return invoke(actual, method, arguments);
            });
            MigrationSession session = MigrationSession.open(single(proxy), settings, schema);
            MigrationSessionException failure = assertThrows(MigrationSessionException.class, session::close);
            assertEquals(MigrationSessionException.Reason.CLEANUP, failure.reason());
            assertTrue(actual.isClosed()); assertNull(failure.getCause()); assertEquals(0, failure.getSuppressed().length);
            try (Connection peer = source.getConnection()) {
                assertTrue(tryLock(peer, MigrationSession.key(new SqlIdentifier(schema))));
            }
        }
    }

    @Test void distinctSchemasInOneDatabaseHaveIndependentLocks() throws Exception {
        String other = new SqlIdentifier(schema + "_other").name();
        try (Connection setup = source.getConnection(); Statement statement = setup.createStatement()) {
            statement.execute("CREATE SCHEMA \"" + other + "\"");
            try {
                try (MigrationSession first = MigrationSession.open(settings.dataSource(), settings, schema);
                        MigrationSession second = MigrationSession.open(settings.dataSource(), settings, other)) {
                    assertNotNull(first.connection()); assertNotNull(second.connection());
                }
            } finally { statement.execute("DROP SCHEMA \"" + other + "\" CASCADE"); }
        }
    }

    private static boolean tryLock(Connection connection, long key) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("SELECT pg_catalog.pg_try_advisory_lock(?)")) {
            query.setQueryTimeout(10); query.setLong(1, key);
            try (java.sql.ResultSet result = query.executeQuery()) { assertTrue(result.next()); return result.getBoolean(1); }
        }
    }
    private static DataSource single(Connection connection) {
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class},
                (object, method, arguments) -> {
                    if (method.getName().equals("getConnection")) { return connection; }
                    return invoke(source, method, arguments);
                });
    }
    private static Connection proxy(InvocationHandler handler) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, handler);
    }
    private static Object invoke(Object target, Method method, Object[] arguments) throws Throwable {
        try { return method.invoke(target, arguments); } catch (InvocationTargetException exception) { throw exception.getCause(); }
    }
}
