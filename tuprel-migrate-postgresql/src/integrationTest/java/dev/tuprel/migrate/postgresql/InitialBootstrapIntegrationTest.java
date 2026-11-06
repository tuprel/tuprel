package dev.tuprel.migrate.postgresql;

import static dev.tuprel.migrate.postgresql.PostgresqlInitialBootstrap.Outcome;
import static org.junit.jupiter.api.Assertions.*;

import dev.tuprel.migrate.MigrationHistory;
import dev.tuprel.sql.SqlIdentifier;
import dev.tuprel.testsupport.TestPostgresql;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

class InitialBootstrapIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final AtomicInteger sequence = new AtomicInteger();
    private static TestPostgresql database;
    private static MigrationDatabaseSettings settings;
    private InitialBootstrapLedger ledger;
    private SqlIdentifier target;
    private SqlIdentifier admin;
    private boolean adminCreated;
    private boolean targetCreated;
    private KeyPair key;
    private InitialBootstrapAuthorization.Claims claims;

    @BeforeAll static void open() throws Exception {
        database = TestPostgresql.open();
        PGSimpleDataSource source = database.dataSource();
        settings = MigrationDatabaseSettings.fromEnvironment(Map.of(
                "TUPREL_DATABASE_URL", "jdbc:postgresql://" + source.getServerNames()[0] + ":"
                        + source.getPortNumbers()[0] + "/" + source.getDatabaseName(),
                "TUPREL_DATABASE_USER", source.getUser(), "TUPREL_DATABASE_PASSWORD", source.getPassword()));
    }

    @AfterAll static void closeDatabase() throws Exception {
        if (database != null) { database.close(); }
    }

    @BeforeEach void prepare() throws Exception {
        target = new SqlIdentifier(database.dataSource().getCurrentSchema() + "_c" + sequence.incrementAndGet());
        admin = new SqlIdentifier(target.name() + "_admin");
        execute("CREATE SCHEMA " + quote(target));
        targetCreated = true;
        execute("CREATE SCHEMA " + quote(admin));
        adminCreated = true;
        execute(InitialBootstrapLedger.createSql(admin));
        try (Connection connection = settings.dataSource().getConnection();
                PreparedStatement query = connection.prepareStatement("""
                        SELECT system_identifier,
                          (SELECT oid FROM pg_catalog.pg_database WHERE datname = pg_catalog.current_database()),
                          pg_catalog.to_regnamespace(?)::pg_catalog.oid, pg_catalog.to_regclass(?)::pg_catalog.oid
                        FROM pg_catalog.pg_control_system()
                        """)) {
            query.setString(1, quote(target));
            query.setString(2, InitialBootstrapLedger.table(admin));
            try (ResultSet rows = query.executeQuery()) {
                assertTrue(rows.next());
                ledger = new InitialBootstrapLedger(admin, rows.getLong(4));
                claims = new InitialBootstrapAuthorization.Claims(UUID.randomUUID(),
                        settings.planTarget(target.name()).fingerprint(), rows.getLong(1), rows.getLong(2),
                        rows.getLong(3), ledger.oid(), UUID.randomUUID(), UUID.randomUUID(), NOW.plusSeconds(60));
            }
        }
        key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        try (Connection connection = settings.dataSource().getConnection();
                PreparedStatement insert = connection.prepareStatement("INSERT INTO " + InitialBootstrapLedger.table(admin)
                        + " (namespace, audience, fingerprint, generation) VALUES (?, ?, ?, ?)")) {
            insert.setLong(1, claims.namespace());
            insert.setObject(2, claims.audience());
            insert.setString(3, claims.fingerprint().value());
            insert.setObject(4, claims.generation());
            assertEquals(1, insert.executeUpdate());
        }
    }

    @AfterEach void close() throws Exception {
        try {
            if (adminCreated) { execute("DROP SCHEMA " + quote(admin) + " CASCADE"); }
        } finally {
            if (targetCreated) { execute("DROP SCHEMA " + quote(target) + " CASCADE"); }
        }
    }

    @Test void consumesOnceWithCanonicalEmptyHistoryAndReceipt() throws Exception {
        InitialBootstrapAuthorization authorization = sign(claims);
        PostgresqlInitialBootstrap bootstrap = bootstrap(settings.dataSource());
        assertEquals(Outcome.NOT_COMMITTED, bootstrap.reconcile(target, authorization));
        assertFalse(historyInitialised());
        assertEquals(Outcome.BOOTSTRAPPED, bootstrap.bootstrap(target, authorization));
        assertTrue(historyInitialised());
        assertEquals(Outcome.BOOTSTRAPPED, bootstrap.reconcile(target, authorization));
        assertEquals(Outcome.REFUSED, bootstrap.bootstrap(target, authorization));
    }

    @Test void rejectsUnknownGenerationTamperingWrongClusterAndExpiredAuthorization() throws Exception {
        PostgresqlInitialBootstrap bootstrap = bootstrap(settings.dataSource());
        assertEquals(Outcome.REFUSED, bootstrap.bootstrap(target, new InitialBootstrapAuthorization(claims, new byte[64])));
        assertEquals(Outcome.REFUSED, bootstrap.bootstrap(target, sign(new InitialBootstrapAuthorization.Claims(
                claims.audience(), claims.fingerprint(), claims.cluster(), claims.database(), claims.namespace(),
                claims.ledger(), UUID.randomUUID(), claims.nonce(), claims.expiresAt()))));
        assertEquals(Outcome.REFUSED, bootstrap.bootstrap(target, sign(new InitialBootstrapAuthorization.Claims(
                claims.audience(), claims.fingerprint(), claims.cluster() ^ 1, claims.database(), claims.namespace(),
                claims.ledger(), claims.generation(), claims.nonce(), claims.expiresAt()))));
        assertEquals(Outcome.REFUSED, bootstrap.bootstrap(target, sign(new InitialBootstrapAuthorization.Claims(
                claims.audience(), claims.fingerprint(), claims.cluster(), claims.database(), claims.namespace() ^ 1,
                claims.ledger(), claims.generation(), claims.nonce(), claims.expiresAt()))));
        assertEquals(Outcome.REFUSED, bootstrap.bootstrap(target, sign(new InitialBootstrapAuthorization.Claims(
                claims.audience(), claims.fingerprint(), claims.cluster(), claims.database(), claims.namespace(),
                claims.ledger(), claims.generation(), claims.nonce(), NOW))));
        try (Connection connection = settings.dataSource().getConnection(); PreparedStatement update = connection.prepareStatement(
                "UPDATE " + InitialBootstrapLedger.table(admin) + " SET generation = ? WHERE namespace = ?")) {
            update.setObject(1, UUID.randomUUID());
            update.setLong(2, claims.namespace());
            assertEquals(1, update.executeUpdate());
        }
        assertEquals(Outcome.REFUSED, bootstrap.bootstrap(target, sign(claims)));
        assertFalse(historyInitialised());
    }

    @Test void refusesAdoptionEvenForObjectsOutsideTheDriftProfileAndForPublicCreate() throws Exception {
        PostgresqlInitialBootstrap bootstrap = bootstrap(settings.dataSource());
        InitialBootstrapAuthorization authorization = sign(claims);
        String function = quote(target) + ".fixture_function()";
        execute("CREATE FUNCTION " + function + " RETURNS integer LANGUAGE sql AS 'SELECT 1'");
        assertEquals(Outcome.REFUSED, bootstrap.bootstrap(target, authorization));
        execute("DROP FUNCTION " + function);
        execute("GRANT CREATE ON SCHEMA " + quote(target) + " TO PUBLIC");
        assertEquals(Outcome.REFUSED, bootstrap.bootstrap(target, authorization));
        execute("REVOKE CREATE ON SCHEMA " + quote(target) + " FROM PUBLIC");
        execute(MigrationHistoryTable.createSql(target));
        assertEquals(Outcome.REFUSED, bootstrap.bootstrap(target, authorization));
        assertEquals(Outcome.REFUSED, bootstrap.reconcile(target, authorization));
    }

    @Test void rollsBackHistoryIfTheReceiptWriteFails() throws Exception {
        InitialBootstrapAuthorization authorization = sign(claims);
        PostgresqlInitialBootstrap refused = bootstrap(fault("prepareStatement", false));
        assertEquals(Outcome.REFUSED, refused.bootstrap(target, authorization));
        assertFalse(historyInitialised());
        assertEquals(Outcome.NOT_COMMITTED, bootstrap(settings.dataSource()).reconcile(target, authorization));
    }

    @Test void refusesUntrustedColumnGrantsOnAdministrativeReceipt() throws Exception {
        execute("GRANT UPDATE (nonce) ON " + InitialBootstrapLedger.table(admin) + " TO PUBLIC");
        assertEquals(Outcome.REFUSED, bootstrap(settings.dataSource()).bootstrap(target, sign(claims)));
        assertFalse(historyInitialised());
    }

    @Test void lostCommitAcknowledgementRequiresReconciliation() throws Exception {
        InitialBootstrapAuthorization authorization = sign(claims);
        PostgresqlInitialBootstrap uncertain = bootstrap(fault("commit", true));
        assertEquals(Outcome.OUTCOME_UNKNOWN, uncertain.bootstrap(target, authorization));
        assertEquals(Outcome.BOOTSTRAPPED, bootstrap(settings.dataSource()).reconcile(target, authorization));
        assertEquals(Outcome.REFUSED, bootstrap(settings.dataSource()).bootstrap(target, authorization));
        execute("DROP TABLE " + quote(target) + ".\"_tuprel_migrations\"");
        execute(MigrationHistoryTable.createSql(target));
        assertEquals(Outcome.REFUSED, bootstrap(settings.dataSource()).reconcile(target, authorization));
    }

    @Test void concurrentExecutorsConfirmExactlyOneBootstrap() throws Exception {
        InitialBootstrapAuthorization authorization = sign(claims);
        PostgresqlInitialBootstrap bootstrap = bootstrap(settings.dataSource());
        CompletableFuture<Outcome> first = CompletableFuture.supplyAsync(() -> bootstrap.bootstrap(target, authorization));
        CompletableFuture<Outcome> second = CompletableFuture.supplyAsync(() -> bootstrap.bootstrap(target, authorization));
        Outcome one = first.get(20, TimeUnit.SECONDS);
        Outcome two = second.get(20, TimeUnit.SECONDS);
        assertTrue((one == Outcome.BOOTSTRAPPED && two == Outcome.REFUSED)
                || (two == Outcome.BOOTSTRAPPED && one == Outcome.REFUSED), () -> one + " / " + two);
    }

    private boolean historyInitialised() throws Exception {
        MigrationHistory history = new PostgresqlMigrationHistory(settings.dataSource()).read(target.name());
        assertTrue(history.applied().isEmpty());
        return history.initialised();
    }

    private PostgresqlInitialBootstrap bootstrap(DataSource source) {
        return new PostgresqlInitialBootstrap(settings, source, key.getPublic(), claims.audience(), claims.generation(), ledger,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private InitialBootstrapAuthorization sign(InitialBootstrapAuthorization.Claims data) throws Exception {
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(key.getPrivate());
        signature.update(data.signingBytes());
        return new InitialBootstrapAuthorization(data, signature.sign());
    }

    private DataSource fault(String operation, boolean after) {
        return new PGSimpleDataSource() {
            @Override public Connection getConnection() throws SQLException {
                Connection connection = settings.dataSource().getConnection();
                return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                        (proxy, method, args) -> {
                            boolean fail = method.getName().equals(operation)
                                    && (!operation.equals("prepareStatement") || args[0].toString().startsWith("UPDATE "));
                            if (fail && !after) { throw new SQLException("fixture failure", "XX000"); }
                            try {
                                Object value = method.invoke(connection, args);
                                if (fail) { throw new SQLException("fixture failure", "08006"); }
                                return value;
                            } catch (InvocationTargetException failure) { throw failure.getCause(); }
                        });
            }
        };
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = settings.dataSource().getConnection()) {
            MigrationCatalog.execute(connection, sql);
        }
    }

    private static String quote(SqlIdentifier schema) { return "\"" + schema.name() + "\""; }
}
