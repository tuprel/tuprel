package dev.tuprel.migrate.postgresql;

import static dev.tuprel.migrate.postgresql.PostgresqlInitialBootstrap.Outcome;
import static org.junit.jupiter.api.Assertions.*;

import dev.tuprel.migrate.MigrationHistory;
import dev.tuprel.migrate.MigrationChecksum;
import dev.tuprel.migrate.MigrationDirectory;
import dev.tuprel.migrate.MigrationFile;
import dev.tuprel.migrate.NativeMigrationPublication;
import dev.tuprel.sql.SqlIdentifier;
import dev.tuprel.testsupport.TestPostgresql;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;
import org.postgresql.PGConnection;

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

    @Test void verifiedSupervisorIssuesOneSealedSessionAndRejectsReplay(@TempDir Path directory) throws Exception {
        Path root = physicalFiles(Files.createDirectory(directory.resolve("migrations")));
        InitialBootstrapAuthorization proof = sign(claims);
        PostgresqlInitialBootstrap provisioner = bootstrap(settings.dataSource());
        assertEquals(Outcome.BOOTSTRAPPED, provisioner.bootstrap(target, proof));
        Path jar = Path.of(MigrationRunner.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
        MigrationChecksum digest = MigrationChecksum.of(Files.readAllBytes(jar));
        boolean unix = java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("unix");
        long uid = unix ? new com.sun.security.auth.module.UnixSystem().getUid() : 0;
        ManagedExecutorPin pin = new ManagedExecutorPin(jar, digest, "development",
                PhysicalDeployDigest.DOMAIN, PhysicalDeployDigest.VERSION, uid);
        assertTrue(pin.verifiesRunningExecutor());

        Path substitute = Files.copy(jar, directory.resolve("changed-executor.jar"));
        Files.write(substitute, new byte[]{1}, StandardOpenOption.APPEND);
        MigrationChecksum changed = MigrationChecksum.of(Files.readAllBytes(substitute));
        assertEquals(0, runPinProbe(jar, digest));
        assertEquals(2, runPinProbe(substitute, digest));
        AtomicInteger issued = new AtomicInteger();
        SqlIdentifier ledgerOwner = new SqlIdentifier(database.dataSource().getUser());
        ManagedMigrationRole placeholder = new ManagedMigrationRole(new SqlIdentifier("untrusted_owner"), ledgerOwner);
        ManagedCredentialIssuer.CredentialSource forbidden = connection -> {
            issued.incrementAndGet();
            throw new SQLException("fixture source must not be reached");
        };
        for (ManagedExecutorPin refused : java.util.List.of(
                new ManagedExecutorPin(jar, changed, pin.artifactVersion(), pin.domain(), pin.version(), uid),
                new ManagedExecutorPin(substitute, changed, pin.artifactVersion(), pin.domain(), pin.version(), uid),
                new ManagedExecutorPin(jar, digest, "unapproved-version", pin.domain(), pin.version(), uid),
                new ManagedExecutorPin(jar, digest, pin.artifactVersion(), pin.domain(), pin.version() + 1, uid),
                new ManagedExecutorPin(jar, digest, pin.artifactVersion(), "TUPREL-MIGRATION-PLAN", 1, uid),
                new ManagedExecutorPin(jar, digest, pin.artifactVersion(), pin.domain(), pin.version(), uid ^ 1L))) {
            assertEquals(MigrationExecutionResult.Failure.PERMISSION, assertThrows(MigrationInspectionException.class,
                    () -> issuer(refused, forbidden, provisioner, new SqlIdentifier("untrusted_login"), placeholder)
                            .inspect(root, proof)).reason());
        }
        assertEquals(0, issued.get());

        if (!System.getProperty("tuprel.test.database").equals("container")) {
            assertEquals(MigrationExecutionResult.Failure.PERMISSION, assertThrows(MigrationInspectionException.class,
                    () -> issuer(pin, forbidden, provisioner, new SqlIdentifier("tuprel_status_reader"), placeholder)
                            .inspect(root, proof)).reason());
            assertEquals(0, issued.get());
            assertTrue(historyInitialised());
            return;
        }

        assertTrue(pin.protectedInstallation(), "CI must provide a protected POSIX installation path");
        Files.setPosixFilePermissions(directory, java.nio.file.attribute.PosixFilePermissions.fromString("rwxrwx---"));
        assertFalse(new ManagedExecutorPin(substitute, changed, pin.artifactVersion(), pin.domain(), pin.version(), uid)
                .protectedInstallation(), "Group-writable ancestors cannot host a supervisor installation");
        ManagedMigrationRole role = provisionSealedFixture(ledgerOwner);
        AtomicReference<MigrationDatabaseSettings> captured = new AtomicReference<>();
        ManagedCredentialIssuer.CredentialSource approvedSource = connection -> {
            issued.incrementAndGet();
            MigrationDatabaseSettings temporary = issueFixtureCredential(connection);
            captured.set(temporary);
            return temporary;
        };
        ManagedCredentialIssuer invalid = issuer(pin, approvedSource, provisioner, managedLogin, role);
        assertThrows(MigrationInspectionException.class,
                () -> invalid.inspect(root, new InitialBootstrapAuthorization(claims, new byte[64])));
        assertEquals(0, issued.get());

        ManagedCredentialIssuer preview = issuer(pin, connection -> {
            ManagedCredentialIssuer competing = issuer(pin, forbidden, provisioner, managedLogin, role);
            assertEquals(MigrationExecutionResult.Failure.PERMISSION, assertThrows(MigrationInspectionException.class,
                    () -> competing.inspect(root, proof)).reason());
            return approvedSource.issue(connection);
        }, provisioner, managedLogin, role);
        PostgresqlPhysicalApproval.Approval approval = preview.inspect(root, proof).approval();
        assertThrows(MigrationInspectionException.class, () -> preview.inspect(root, proof));
        assertEquals(1, issued.get());
        assertCredentialRejected(captured.get());

        ManagedCredentialIssuer failing = issuer(pin, connection -> {
            approvedSource.issue(connection);
            throw new SQLException("private fixture issuer failure", "08006");
        }, provisioner, managedLogin, role);
        MigrationExecutionResult refused = failing.deploy(root, approval, proof);
        assertEquals(MigrationExecutionResult.Outcome.REFUSED, refused.outcome());
        assertTrue(refused.confirmed().isEmpty());
        assertFalse(refused.toString().contains("private fixture issuer failure"));
        assertCredentialRejected(captured.get());
        assertTrue(historyInitialised());

        ManagedCredentialIssuer wrongPassword = issuer(pin, connection -> {
            approvedSource.issue(connection);
            return fixtureSettings("incorrect-fixture-password");
        }, provisioner, managedLogin, role);
        assertEquals(MigrationExecutionResult.Outcome.REFUSED, wrongPassword.deploy(root, approval, proof).outcome());
        assertCredentialRejected(captured.get());
        assertTrue(historyInitialised());

        ManagedCredentialIssuer expired = issuer(pin, connection -> {
            MigrationDatabaseSettings temporary = approvedSource.issue(connection);
            MigrationCatalog.execute(connection, "ALTER ROLE " + quote(managedLogin) + " VALID UNTIL '-infinity'");
            return temporary;
        }, provisioner, managedLogin, role);
        assertEquals(MigrationExecutionResult.Outcome.REFUSED, expired.deploy(root, approval, proof).outcome());
        assertCredentialRejected(captured.get());
        assertTrue(historyInitialised());

        ManagedCredentialIssuer deployment = issuer(pin, approvedSource, provisioner, managedLogin, role);
        MigrationExecutionResult result = deployment.deploy(root, approval, proof);
        assertEquals(MigrationExecutionResult.Outcome.APPLIED, result.outcome());
        assertEquals(2, result.confirmed().size());
        assertEquals(MigrationExecutionResult.Failure.PERMISSION, deployment.deploy(root, approval, proof).failure());
        assertEquals(5, issued.get());
        assertCredentialRejected(captured.get());
        assertEquals(result.confirmed(), new PostgresqlMigrationHistory(settings.dataSource()).read(target.name()).applied());
    }

    @Test void managedPublicationWritesExactSqlWithoutApplyingIt(@TempDir Path directory) throws Exception {
        InitialBootstrapAuthorization proof = sign(claims);
        PostgresqlInitialBootstrap provisioner = bootstrap(settings.dataSource());
        assertEquals(Outcome.BOOTSTRAPPED, provisioner.bootstrap(target, proof));
        Path jar = Path.of(MigrationRunner.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
        boolean unix = java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("unix");
        long uid = unix ? new com.sun.security.auth.module.UnixSystem().getUid() : 0;
        ManagedExecutorPin pin = new ManagedExecutorPin(jar, MigrationChecksum.of(Files.readAllBytes(jar)), "development",
                PhysicalDeployDigest.DOMAIN, PhysicalDeployDigest.VERSION, uid);
        SqlIdentifier ledgerOwner = new SqlIdentifier(database.dataSource().getUser());
        String fixtureBinary = System.getProperty("tuprel.test.publisher");
        String fixtureVolume = System.getProperty("tuprel.test.publicationVolume");
        if (fixtureBinary.isEmpty() && fixtureVolume.isEmpty()) {
            ManagedMigrationRole role = new ManagedMigrationRole(new SqlIdentifier("untrusted_owner"), ledgerOwner);
            ManagedCredentialIssuer refused = issuer(pin, ignored -> { throw new AssertionError("Credentials were requested"); },
                    provisioner, new SqlIdentifier("untrusted_login"), role);
            assertEquals(ManagedDevPublication.State.REFUSED, refused.publish(directory.resolve("migrations"), Path.of("schema.tuprel"),
                    "initial", directory, key.getPublic(), proof, Clock.fixed(NOW, ZoneOffset.UTC)).state());
            assertTrue(historyInitialised());
            return;
        }
        assertEquals("container", System.getProperty("tuprel.test.database"));
        Path binary = Path.of(fixtureBinary).toRealPath();
        Path project = Files.createTempDirectory(Path.of(fixtureVolume), "managed-dev-").toRealPath();
        Path catalog = Files.createDirectories(project.resolve("tuprel/migrations"));
        Files.writeString(catalog.resolve(MigrationDirectory.FORMAT_FILE), MigrationDirectory.FORMAT_HEADER);
        Path schema = Files.writeString(project.resolve("tuprel/schema.tuprel"), """
                model Product {
                  id Int @id
                  label String
                }
                """);
        Path installation = Files.createTempDirectory(binary.getParent(), "signed-publisher-");
        Path installed = Files.copy(binary, installation.resolve("publisher"), StandardCopyOption.COPY_ATTRIBUTES);
        byte[] manifest = ("TUPREL-NATIVE-PUBLISHER 2\nos linux\narch x86_64\nsha256 "
                + MigrationChecksum.of(Files.readAllBytes(installed)).value() + "\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        Files.write(installation.resolve("manifest.txt"), manifest);
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(key.getPrivate());
        signer.update(manifest);
        Files.write(installation.resolve("manifest.ed25519"), signer.sign());
        Files.setPosixFilePermissions(installed, PosixFilePermissions.fromString("rwxr-xr-x"));
        for (String metadata : java.util.List.of("manifest.txt", "manifest.ed25519")) {
            Path path = installation.resolve(metadata);
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-r--r--"));
            assertTrue(pin.protectedFile(path), "Signed fixture metadata must be operator-controlled");
        }
        assertTrue(pin.protectedFile(installed), "The native fixture must be operator-controlled");
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        ManagedMigrationRole role = provisionSealedFixture(ledgerOwner);
        ManagedCredentialIssuer.CredentialSource credentials = this::issueFixtureCredential;
        Files.write(installed, new byte[]{0}, StandardOpenOption.APPEND);
        assertEquals(ManagedDevPublication.State.REFUSED,
                issuer(pin, ignored -> { throw new AssertionError("Credentials were requested"); }, provisioner, managedLogin, role)
                        .publish(catalog, schema, "initial", installation, key.getPublic(), proof, clock).state());
        Files.copy(binary, installed, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        Files.setPosixFilePermissions(installed, PosixFilePermissions.fromString("rwxr-xr-x"));
        assertTrue(pin.protectedFile(installed), "Restored native fixture must be operator-controlled");
        NativeMigrationPublication recovery = new NativeMigrationPublication(catalog, installation, key.getPublic(), claims.generation(), clock);
        assertFalse(recovery.hasUnresolvedAttempt());
        assertTrue(MigrationDirectory.discover(catalog).isEmpty());
        PostgresqlPhysicalApproval.Approval stale = issuer(pin, credentials, provisioner, managedLogin, role)
                .inspect(catalog, proof).approval();
        ManagedDevPublication.Result publication = issuer(pin, credentials, provisioner, managedLogin, role)
                .publish(catalog, schema, "initial", installation, key.getPublic(), proof, clock);
        assertEquals(ManagedDevPublication.State.PUBLISHED, publication.state(), publication::toString);
        MigrationFile artifact = publication.file().orElseThrow();
        assertEquals(publication.proposal().orElseThrow().sql().orElseThrow(), artifact.sql());
        assertEquals(artifact.checksum(), MigrationDirectory.discover(catalog).getFirst().checksum());
        assertTrue(historyInitialised());
        assertFalse(relationExists("Product"));
        assertEquals(MigrationExecutionResult.Failure.FILES, assertThrows(MigrationInspectionException.class,
                () -> issuer(pin, credentials, provisioner, managedLogin, role).review(catalog, proof)).reason());
        assertEquals(MigrationExecutionResult.Failure.FILES, issuer(pin, credentials, provisioner, managedLogin, role)
                .deployReviewed(catalog, ManagedDeployApproval.format(stale), proof).failure());
        assertEquals(ManagedDevPublication.State.PUBLICATION_UNKNOWN, issuer(pin, credentials, provisioner, managedLogin, role)
                .publish(catalog, schema, "second", installation, key.getPublic(), proof, clock).state());
        assertEquals(NativeMigrationPublication.State.PUBLISHED, recovery.reconcile());
        assertTrue(recovery.acknowledge(NativeMigrationPublication.State.PUBLISHED));
        assertEquals(ManagedDevPublication.State.REFUSED, issuer(pin, credentials, provisioner, managedLogin, role)
                .publish(catalog, schema, "second", installation, key.getPublic(), proof, clock).state());
        assertEquals(MigrationExecutionResult.Failure.APPROVAL, issuer(pin, credentials, provisioner, managedLogin, role)
                .deploy(catalog, stale, proof).failure());
        assertTrue(historyInitialised());
        ManagedCredentialIssuer.DeploymentReview approved = issuer(pin, credentials, provisioner, managedLogin, role).review(catalog, proof);
        assertEquals(MigrationExecutionResult.Failure.APPROVAL,
                issuer(pin, ignored -> { throw new AssertionError("Credentials were requested"); }, provisioner, managedLogin, role)
                        .deployReviewed(catalog, approved.plan().digest().value(), proof).failure());
        assertEquals(MigrationExecutionResult.Outcome.APPLIED, issuer(pin, credentials, provisioner, managedLogin, role)
                .deployReviewed(catalog, approved.token().orElseThrow(), proof).outcome());
        assertTrue(relationExists("Product"));
        assertEquals(artifact.checksum(), new PostgresqlMigrationHistory(settings.dataSource()).read(target.name())
                .applied().getFirst().checksum());
    }

    private boolean relationExists(String name) throws SQLException {
        try (Connection connection = settings.dataSource().getConnection(); PreparedStatement query = connection.prepareStatement(
                "SELECT pg_catalog.to_regclass(?) IS NOT NULL")) {
            query.setString(1, quote(target) + ".\"" + name + "\"");
            try (ResultSet row = query.executeQuery()) { return row.next() && row.getBoolean(1); }
        }
    }

    private static int runPinProbe(Path jar, MigrationChecksum expected) throws Exception {
        boolean windows = System.getProperty("os.name").startsWith("Windows");
        Path javaExecutable = Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java");
        Path tests = Path.of(ManagedExecutorPinProbe.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path migrate = Path.of(MigrationChecksum.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        String classpath = String.join(java.io.File.pathSeparator, tests.toString(), jar.toString(), migrate.toString());
        ProcessBuilder builder = new ProcessBuilder(javaExecutable.toString(), "-cp", classpath,
                ManagedExecutorPinProbe.class.getName(), jar.toString(), expected.value());
        builder.environment().clear();
        Process child = builder.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            assertTrue(child.waitFor(15, TimeUnit.SECONDS), "Artifact probe did not exit");
            return child.exitValue();
        } finally { if (child.isAlive()) { child.destroyForcibly(); } }
    }

    private ManagedCredentialIssuer issuer(ManagedExecutorPin pin, ManagedCredentialIssuer.CredentialSource source,
            PostgresqlInitialBootstrap provisioner, SqlIdentifier login, ManagedMigrationRole role) {
        return new ManagedCredentialIssuer(pin, settings.dataSource(), source, provisioner, target, login, role);
    }

    private ManagedMigrationRole provisionSealedFixture(SqlIdentifier ledgerOwner) throws SQLException {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        SqlIdentifier owner = new SqlIdentifier("sealed_owner_" + suffix);
        execute("CREATE ROLE " + quote(owner) + " NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS");
        managedOwner = owner;
        SqlIdentifier login = new SqlIdentifier("sealed_login_" + suffix);
        execute("CREATE ROLE " + quote(login)
                + " NOLOGIN CONNECTION LIMIT 1 NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS");
        managedLogin = login;
        execute("GRANT " + quote(owner) + " TO " + quote(login) + " WITH INHERIT FALSE, SET TRUE, ADMIN FALSE");
        execute("ALTER SCHEMA " + quote(target) + " OWNER TO " + quote(owner));
        execute("ALTER TABLE " + quote(target) + ".\"_tuprel_migrations\" OWNER TO " + quote(owner));
        execute("GRANT USAGE ON SCHEMA " + quote(target) + " TO " + quote(login));
        execute("GRANT USAGE ON SCHEMA " + quote(admin) + " TO " + quote(owner));
        execute("GRANT SELECT ON " + InitialBootstrapLedger.table(admin) + " TO " + quote(owner));
        execute("GRANT EXECUTE ON FUNCTION pg_catalog.pg_control_system() TO " + quote(owner));
        return new ManagedMigrationRole(owner, ledgerOwner);
    }

    private MigrationDatabaseSettings issueFixtureCredential(Connection connection)
            throws SQLException, dev.tuprel.migrate.MigrationHistoryException {
        byte[] entropy = new byte[32];
        new SecureRandom().nextBytes(entropy);
        char[] password = Base64.getUrlEncoder().withoutPadding().encodeToString(entropy).toCharArray();
        Arrays.fill(entropy, (byte) 0);
        try {
            MigrationDatabaseSettings temporary = fixtureSettings(new String(password));
            connection.unwrap(PGConnection.class).alterUserPassword(managedLogin.name(), password, "scram-sha-256");
            MigrationCatalog.execute(connection, "ALTER ROLE " + quote(managedLogin) + " LOGIN");
            return temporary;
        } finally { Arrays.fill(password, '\0'); }
    }

    private MigrationDatabaseSettings fixtureSettings(String password) throws dev.tuprel.migrate.MigrationHistoryException {
        PGSimpleDataSource source = database.dataSource();
        return MigrationDatabaseSettings.fromEnvironment(Map.of(
                "TUPREL_DATABASE_URL", "jdbc:postgresql://" + source.getServerNames()[0] + ":"
                        + source.getPortNumbers()[0] + "/" + source.getDatabaseName(),
                "TUPREL_DATABASE_USER", managedLogin.name(), "TUPREL_DATABASE_PASSWORD", password));
    }

    private static void assertCredentialRejected(MigrationDatabaseSettings temporary) {
        SQLException failure = assertThrows(SQLException.class, () -> {
            try (Connection connection = temporary.dataSource().getConnection()) {
                assertFalse(connection.isValid(5), "A sealed login must reject new connections");
            }
        });
        assertTrue(failure.getSQLState().startsWith("28"));
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
