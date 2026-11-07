package dev.tuprel.migrate.postgresql;

import static dev.tuprel.migrate.postgresql.PostgresqlInitialBootstrap.Outcome;
import static org.junit.jupiter.api.Assertions.*;

import dev.tuprel.migrate.MigrationHistory;
import dev.tuprel.migrate.MigrationDirectory;
import dev.tuprel.sql.SqlIdentifier;
import dev.tuprel.testsupport.TestPostgresql;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.junit.jupiter.api.io.TempDir;
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
    private SqlIdentifier managedOwner;
    private SqlIdentifier managedLogin;
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
            try {
                if (targetCreated) { execute("DROP SCHEMA " + quote(target) + " CASCADE"); }
            } finally {
                try {
                    if (managedLogin != null) { execute("DROP ROLE " + quote(managedLogin)); }
                } finally {
                    if (managedOwner != null) {
                        execute("REVOKE EXECUTE ON FUNCTION pg_catalog.pg_control_system() FROM " + quote(managedOwner));
                        execute("DROP ROLE " + quote(managedOwner));
                    }
                }
            }
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

    @Test void physicalPreviewRequiresConsumedProvenanceAndRefusesDrift(@TempDir Path directory) throws Exception {
        Path root = directory.toRealPath();
        Files.writeString(root.resolve(MigrationDirectory.FORMAT_FILE), MigrationDirectory.FORMAT_HEADER);
        Path migration = Files.createDirectory(root.resolve("20261031_120000_first")).resolve("migration.sql");
        String ddl = "CREATE TABLE " + quote(target) + ".\"example\" (\"id\" INTEGER);\n";
        Files.writeString(migration, ddl);
        InitialBootstrapAuthorization authorization = sign(claims);
        PostgresqlInitialBootstrap provisioner = bootstrap(settings.dataSource());
        PostgresqlPhysicalApproval preview = new PostgresqlPhysicalApproval(settings, settings.dataSource(), provisioner);
        assertCatalogRefused(preview, root, authorization);
        assertFalse(historyInitialised());
        assertEquals(Outcome.BOOTSTRAPPED, provisioner.bootstrap(target, authorization));

        PostgresqlPhysicalApproval.Inspection inspection = preview.inspect(target.name(), root, authorization);
        assertEquals(MigrationPlan.State.REVIEW_REQUIRED, inspection.plan().state());
        assertTrue(inspection.history().applied().isEmpty());
        assertEquals(claims.generation(), inspection.approval().generation());
        assertNotEquals(inspection.plan().digest().value(), inspection.approval().digest().value());
        assertEquals(inspection.approval(), preview.inspect(target.name(), root, authorization).approval());
        PostgresqlPhysicalApproval failedCleanup = new PostgresqlPhysicalApproval(settings, fault("rollback", true), provisioner);
        assertEquals(MigrationExecutionResult.Failure.ROLLBACK, assertThrows(MigrationInspectionException.class,
                () -> failedCleanup.inspect(target.name(), root, authorization)).reason());
        assertCatalogRefused(preview, root, new InitialBootstrapAuthorization(claims, new byte[64]));
        Files.writeString(migration, ddl + "-- change to reviewed bytes\n");
        assertNotEquals(inspection.approval(), preview.inspect(target.name(), root, authorization).approval());

        execute("CREATE TABLE " + quote(target) + ".external (id integer)");
        assertCatalogRefused(preview, root, authorization);
        execute("DROP TABLE " + quote(target) + ".external");
        assertTrue(historyInitialised());
        execute("DROP TABLE " + quote(target) + ".\"_tuprel_migrations\"");
        execute(MigrationHistoryTable.createSql(target));
        assertCatalogRefused(preview, root, authorization);
    }

    private void assertCatalogRefused(PostgresqlPhysicalApproval preview, Path directory,
            InitialBootstrapAuthorization authorization) {
        MigrationInspectionException failure = assertThrows(MigrationInspectionException.class,
                () -> preview.inspect(target.name(), directory, authorization));
        assertEquals(MigrationExecutionResult.Failure.CATALOG, failure.reason());
    }

    @Test void physicalRunnerRejectsLegacyAndSerializesTwoApprovedDeploys(@TempDir Path directory) throws Exception {
        Path root = physicalFiles(directory);
        InitialBootstrapAuthorization authorization = sign(claims);
        PostgresqlInitialBootstrap provisioner = bootstrap(settings.dataSource());
        assertEquals(Outcome.BOOTSTRAPPED, provisioner.bootstrap(target, authorization));
        PostgresqlPhysicalApproval runner = new PostgresqlPhysicalApproval(settings, settings.dataSource(), provisioner);
        PostgresqlPhysicalApproval.Inspection preview = runner.inspect(target.name(), root, authorization);
        PostgresqlPhysicalApproval.Approval legacy = new PostgresqlPhysicalApproval.Approval(claims.generation(),
                new PhysicalDeployDigest(preview.plan().digest().value()));
        assertEquals(MigrationExecutionResult.Failure.APPROVAL, runner.deploy(target.name(), root, legacy, authorization).failure());
        assertTrue(historyInitialised());

        execute("CREATE TABLE " + quote(target) + ".external (id integer)");
        assertEquals(MigrationExecutionResult.Failure.CATALOG,
                runner.deploy(target.name(), root, preview.approval(), authorization).failure());
        execute("DROP TABLE " + quote(target) + ".external");

        CompletableFuture<MigrationExecutionResult> first = CompletableFuture.supplyAsync(
                () -> runner.deploy(target.name(), root, preview.approval(), authorization));
        CompletableFuture<MigrationExecutionResult> second = CompletableFuture.supplyAsync(
                () -> runner.deploy(target.name(), root, preview.approval(), authorization));
        MigrationExecutionResult one = first.get(25, TimeUnit.SECONDS);
        MigrationExecutionResult two = second.get(25, TimeUnit.SECONDS);
        assertEquals(java.util.Set.of(MigrationExecutionResult.Outcome.APPLIED, MigrationExecutionResult.Outcome.REFUSED),
                java.util.Set.of(one.outcome(), two.outcome()));
        MigrationExecutionResult applied = one.outcome() == MigrationExecutionResult.Outcome.APPLIED ? one : two;
        assertEquals(2, applied.confirmed().size());
        PostgresqlPhysicalApproval.Inspection confirmed = runner.inspect(target.name(), root, authorization);
        assertEquals(MigrationPlan.State.NO_CHANGES, confirmed.plan().state());
        assertEquals(applied.confirmed(), confirmed.history().applied());
        assertEquals(MigrationExecutionResult.Failure.APPROVAL,
                runner.deploy(target.name(), root, preview.approval(), authorization).failure());
    }

    @Test void physicalRunnerPreservesOutcomeUnknownAndRequiresFreshApproval(@TempDir Path directory) throws Exception {
        Path root = physicalFiles(directory);
        InitialBootstrapAuthorization authorization = sign(claims);
        PostgresqlInitialBootstrap provisioner = bootstrap(settings.dataSource());
        assertEquals(Outcome.BOOTSTRAPPED, provisioner.bootstrap(target, authorization));
        PostgresqlPhysicalApproval runner = new PostgresqlPhysicalApproval(settings, settings.dataSource(), provisioner);
        PostgresqlPhysicalApproval.Approval approved = runner.inspect(target.name(), root, authorization).approval();
        PostgresqlPhysicalApproval faulty = new PostgresqlPhysicalApproval(settings, fault("commit", true), provisioner);
        MigrationExecutionResult uncertain = faulty.deploy(target.name(), root, approved, authorization);
        assertEquals(MigrationExecutionResult.Outcome.OUTCOME_UNKNOWN, uncertain.outcome());
        assertEquals(MigrationExecutionResult.Failure.COMMIT, uncertain.failure());
        assertTrue(uncertain.confirmed().isEmpty());
        PostgresqlPhysicalApproval.Inspection fresh = runner.inspect(target.name(), root, authorization);
        assertEquals(1, fresh.history().applied().size());
        assertEquals(1, fresh.plan().scripts().size());
        assertEquals(MigrationExecutionResult.Failure.APPROVAL, runner.deploy(target.name(), root, approved, authorization).failure());
        assertEquals(MigrationExecutionResult.Outcome.APPLIED, runner.deploy(target.name(), root, fresh.approval(), authorization).outcome());
    }

    @Test void driftBetweenCommitsStopsTheNextWriteAndPreservesTheConfirmedPrefix(@TempDir Path directory) throws Exception {
        Path root = physicalFiles(directory);
        InitialBootstrapAuthorization authorization = sign(claims);
        PostgresqlInitialBootstrap provisioner = bootstrap(settings.dataSource());
        assertEquals(Outcome.BOOTSTRAPPED, provisioner.bootstrap(target, authorization));
        PostgresqlPhysicalApproval reader = new PostgresqlPhysicalApproval(settings, settings.dataSource(), provisioner);
        PostgresqlPhysicalApproval.Approval approved = reader.inspect(target.name(), root, authorization).approval();
        String peer = quote(target) + ".\"peer\"";
        AtomicInteger commits = new AtomicInteger();
        DataSource changed = intercept((connection, method, arguments) -> {
            Object result = invoke(connection, method, arguments);
            if (method.getName().equals("commit") && commits.incrementAndGet() == 1) {
                execute("CREATE TABLE " + peer + " (id integer)");
            }
            return result;
        });
        PostgresqlPhysicalApproval runner = new PostgresqlPhysicalApproval(settings, changed, provisioner);
        MigrationExecutionResult result = runner.deploy(target.name(), root, approved, authorization);
        assertEquals(MigrationExecutionResult.Outcome.REFUSED, result.outcome());
        assertEquals(MigrationExecutionResult.Failure.CATALOG, result.failure());
        assertEquals(1, commits.get());
        assertEquals(1, result.confirmed().size());
        assertCatalogRefused(reader, root, authorization);
        execute("DROP TABLE " + peer);
        PostgresqlPhysicalApproval.Inspection after = reader.inspect(target.name(), root, authorization);
        assertEquals(result.confirmed(), after.history().applied());
        assertEquals(1, after.plan().scripts().size());
    }

    @Test void unexpectedPostDdlShapeRollsBackSqlAndHistoryTogether(@TempDir Path directory) throws Exception {
        Path root = physicalFiles(directory);
        InitialBootstrapAuthorization authorization = sign(claims);
        PostgresqlInitialBootstrap provisioner = bootstrap(settings.dataSource());
        assertEquals(Outcome.BOOTSTRAPPED, provisioner.bootstrap(target, authorization));
        PostgresqlPhysicalApproval reader = new PostgresqlPhysicalApproval(settings, settings.dataSource(), provisioner);
        PostgresqlPhysicalApproval.Approval approved = reader.inspect(target.name(), root, authorization).approval();
        AtomicInteger mutations = new AtomicInteger();
        DataSource changed = intercept((connection, method, arguments) -> {
            if (method.getName().equals("prepareStatement") && arguments[0].toString().startsWith("INSERT INTO")
                    && mutations.getAndIncrement() == 0) {
                MigrationCatalog.execute(connection, "CREATE TABLE " + quote(target) + ".\"unexpected\" (id integer)");
            }
            return invoke(connection, method, arguments);
        });
        PostgresqlPhysicalApproval runner = new PostgresqlPhysicalApproval(settings, changed, provisioner);
        MigrationExecutionResult result = runner.deploy(target.name(), root, approved, authorization);
        assertEquals(MigrationExecutionResult.Outcome.REFUSED, result.outcome());
        assertEquals(MigrationExecutionResult.Failure.CATALOG, result.failure());
        assertEquals(1, mutations.get());
        assertTrue(result.confirmed().isEmpty());
        assertEquals(approved, reader.inspect(target.name(), root, authorization).approval());
    }

    @Test void managedExecutorUsesNoLoginOwnerAndReadOnlyAdministrativeLedger(@TempDir Path directory) throws Exception {
        Path root = physicalFiles(directory);
        InitialBootstrapAuthorization authorization = sign(claims);
        PostgresqlInitialBootstrap provisioner = bootstrap(settings.dataSource());
        assertEquals(Outcome.BOOTSTRAPPED, provisioner.bootstrap(target, authorization));
        PGSimpleDataSource databaseSource = database.dataSource();
        SqlIdentifier ledgerOwner = new SqlIdentifier(databaseSource.getUser());
        if (!System.getProperty("tuprel.test.database").equals("container")) {
            PostgresqlPhysicalApproval refused = new PostgresqlPhysicalApproval(settings, settings.dataSource(), provisioner,
                    new ManagedMigrationRole(new SqlIdentifier("tuprel_status_reader"), ledgerOwner));
            assertEquals(MigrationExecutionResult.Failure.PERMISSION, assertThrows(MigrationInspectionException.class,
                    () -> refused.inspect(target.name(), root, authorization)).reason());
            return;
        }

        String suffix = UUID.randomUUID().toString().replace("-", "");
        SqlIdentifier owner = new SqlIdentifier("managed_owner_" + suffix);
        execute("CREATE ROLE " + quote(owner) + " NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS");
        managedOwner = owner;
        SqlIdentifier login = new SqlIdentifier("managed_login_" + suffix);
        String credential = UUID.randomUUID().toString();
        execute("CREATE ROLE " + quote(login) + " LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS PASSWORD '"
                + credential + "'");
        managedLogin = login;
        execute("GRANT " + quote(owner) + " TO " + quote(login) + " WITH INHERIT FALSE, SET TRUE, ADMIN FALSE");
        execute("ALTER SCHEMA " + quote(target) + " OWNER TO " + quote(owner));
        execute("ALTER TABLE " + quote(target) + ".\"_tuprel_migrations\" OWNER TO " + quote(owner));
        execute("GRANT USAGE ON SCHEMA " + quote(target) + " TO " + quote(login));
        execute("GRANT USAGE ON SCHEMA " + quote(admin) + " TO " + quote(owner));
        execute("GRANT SELECT ON " + InitialBootstrapLedger.table(admin) + " TO " + quote(owner));
        execute("GRANT EXECUTE ON FUNCTION pg_catalog.pg_control_system() TO " + quote(owner));

        MigrationDatabaseSettings managedSettings = MigrationDatabaseSettings.fromEnvironment(Map.of(
                "TUPREL_DATABASE_URL", "jdbc:postgresql://" + databaseSource.getServerNames()[0] + ":"
                        + databaseSource.getPortNumbers()[0] + "/" + databaseSource.getDatabaseName(),
                "TUPREL_DATABASE_USER", login.name(), "TUPREL_DATABASE_PASSWORD", credential));
        PostgresqlPhysicalApproval runner = new PostgresqlPhysicalApproval(managedSettings, managedSettings.dataSource(),
                provisioner, new ManagedMigrationRole(owner, ledgerOwner));
        PostgresqlPhysicalApproval.Approval approved = runner.inspect(target.name(), root, authorization).approval();
        execute("GRANT INSERT ON " + quote(target) + ".\"_tuprel_migrations\" TO " + quote(login));
        assertEquals(MigrationExecutionResult.Failure.PERMISSION,
                runner.deploy(target.name(), root, approved, authorization).failure());
        execute("REVOKE INSERT ON " + quote(target) + ".\"_tuprel_migrations\" FROM " + quote(login));
        execute("GRANT UPDATE ON " + InitialBootstrapLedger.table(admin) + " TO " + quote(owner));
        assertCatalogRefused(runner, root, authorization);
        execute("REVOKE UPDATE ON " + InitialBootstrapLedger.table(admin) + " FROM " + quote(owner));
        execute("GRANT " + quote(owner) + " TO " + quote(ledgerOwner));
        assertEquals(MigrationExecutionResult.Failure.PERMISSION,
                runner.deploy(target.name(), root, approved, authorization).failure());
        execute("REVOKE " + quote(owner) + " FROM " + quote(ledgerOwner));
        MigrationExecutionResult applied = runner.deploy(target.name(), root, approved, authorization);
        assertEquals(MigrationExecutionResult.Outcome.APPLIED, applied.outcome());
        assertEquals(2, applied.confirmed().size());
        assertEquals(MigrationPlan.State.NO_CHANGES, runner.inspect(target.name(), root, authorization).plan().state());
    }

    private Path physicalFiles(Path directory) throws Exception {
        Path root = directory.toRealPath();
        Files.writeString(root.resolve(MigrationDirectory.FORMAT_FILE), MigrationDirectory.FORMAT_HEADER);
        Files.writeString(Files.createDirectory(root.resolve("20261031_120000_first")).resolve("migration.sql"),
                "CREATE TABLE " + quote(target) + ".\"example\" (\"id\" INTEGER);\n");
        Files.writeString(Files.createDirectory(root.resolve("20261031_120001_second")).resolve("migration.sql"),
                "ALTER TABLE " + quote(target) + ".\"example\" ADD COLUMN \"note\" TEXT;\n");
        return root;
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
        return intercept((connection, method, arguments) -> {
            boolean fail = method.getName().equals(operation)
                    && (!operation.equals("prepareStatement") || arguments[0].toString().startsWith("UPDATE "));
            if (fail && !after) { throw new SQLException("fixture failure", "XX000"); }
            Object value = invoke(connection, method, arguments);
            if (fail) { throw new SQLException("fixture failure", "08006"); }
            return value;
        });
    }

    @FunctionalInterface private interface Intercept {
        Object call(Connection connection, java.lang.reflect.Method method, Object[] arguments) throws Throwable;
    }

    private DataSource intercept(Intercept intercept) {
        return new PGSimpleDataSource() {
            @Override public Connection getConnection() throws SQLException {
                Connection connection = settings.dataSource().getConnection();
                return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                        (proxy, method, arguments) -> intercept.call(connection, method, arguments));
            }
        };
    }

    private static Object invoke(Connection connection, java.lang.reflect.Method method, Object[] arguments) throws Throwable {
        try { return method.invoke(connection, arguments); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = settings.dataSource().getConnection()) {
            MigrationCatalog.execute(connection, sql);
        }
    }

    private static String quote(SqlIdentifier schema) { return "\"" + schema.name() + "\""; }
}
