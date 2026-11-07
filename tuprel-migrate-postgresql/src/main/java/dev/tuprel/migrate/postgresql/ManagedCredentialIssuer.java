package dev.tuprel.migrate.postgresql;

import static dev.tuprel.migrate.postgresql.MigrationExecutionResult.Failure;

import dev.tuprel.migrate.MigrationHistoryException;
import dev.tuprel.sql.SqlIdentifier;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;

/** One private supervisor invocation. The operator supplies all authority, including the fixed target and artifact pin. */
final class ManagedCredentialIssuer {
    private final ManagedExecutorPin pin;
    private final DataSource authority;
    private final CredentialSource credentials;
    private final PostgresqlInitialBootstrap provenance;
    private final SqlIdentifier schema;
    private final SqlIdentifier login;
    private final ManagedMigrationRole role;
    private final AtomicBoolean used = new AtomicBoolean();

    ManagedCredentialIssuer(ManagedExecutorPin pin, DataSource authority, CredentialSource credentials,
            PostgresqlInitialBootstrap provenance, SqlIdentifier schema, SqlIdentifier login, ManagedMigrationRole role) {
        this.pin = Objects.requireNonNull(pin, "pin");
        this.authority = Objects.requireNonNull(authority, "authority");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.provenance = Objects.requireNonNull(provenance, "provenance");
        this.schema = Objects.requireNonNull(schema, "schema");
        this.login = Objects.requireNonNull(login, "login");
        this.role = Objects.requireNonNull(role, "role");
        if (login.equals(role.owner()) || login.equals(role.ledgerOwner()) || role.sealedUntil().isPresent()) {
            throw new IllegalArgumentException("Invalid managed issuance identities");
        }
    }

    PostgresqlPhysicalApproval.Inspection inspect(Path directory, InitialBootstrapAuthorization proof)
            throws MigrationInspectionException {
        Lease lease = open(proof);
        try { return lease.runner().inspect(schema.name(), directory, proof); }
        finally { discard(lease.backend()); }
    }

    MigrationExecutionResult deploy(Path directory, PostgresqlPhysicalApproval.Approval approval,
            InitialBootstrapAuthorization proof) {
        Lease lease;
        try { lease = open(proof); }
        catch (MigrationInspectionException failure) {
            return new MigrationExecutionResult(MigrationExecutionResult.Outcome.REFUSED, failure.reason(), List.of());
        }
        try { return lease.runner().deploy(schema.name(), directory, approval, proof); }
        finally { discard(lease.backend()); }
    }

    private Lease open(InitialBootstrapAuthorization proof) throws MigrationInspectionException {
        if (!used.compareAndSet(false, true) || !pin.protectedInstallation() || !pin.verifiesRunningExecutor()) {
            throw new MigrationInspectionException(Failure.PERMISSION);
        }
        Connection backend = null;
        try (Connection admin = authority.getConnection()) {
            if (!admin.getAutoCommit()) { throw new MigrationInspectionException(Failure.PERMISSION); }
            MigrationCatalog.execute(admin, "SET search_path = pg_catalog");
            MigrationCatalog.execute(admin, "SET lock_timeout = '2s'");
            MigrationCatalog.execute(admin, "SET statement_timeout = '5s'");
            if (!lock(admin) || !provenance.verifiesIssuanceTarget(admin, schema, proof) || !idle(admin)) {
                throw new MigrationInspectionException(Failure.PERMISSION);
            }
            Instant deadline = Instant.now().plusSeconds(45).truncatedTo(ChronoUnit.SECONDS);
            MigrationDatabaseSettings settings;
            try {
                MigrationCatalog.execute(admin, "ALTER ROLE " + quotedLogin() + " VALID UNTIL '" + deadline + "'");
                settings = credentials.issue(admin);
                if (!settings.isLogin(login.name()) || !settings.matchesEndpoint(admin) || !eligible(admin, deadline)) {
                    throw new MigrationInspectionException(Failure.PERMISSION);
                }
                backend = settings.dataSource().getConnection();
                MigrationCatalog.execute(backend, "SET search_path = pg_catalog");
                if (!settings.matches(backend, schema)) { throw new MigrationInspectionException(Failure.PERMISSION); }
            } finally {
                // Never offer a runner a live login. Also revoke on issuance or authentication failure.
                MigrationCatalog.execute(admin, "ALTER ROLE " + quotedLogin() + " NOLOGIN PASSWORD NULL");
            }
            if (!sealed(admin, backendPid(backend))) { throw new MigrationInspectionException(Failure.PERMISSION); }
            ManagedMigrationRole sealedRole = new ManagedMigrationRole(role.owner(), role.ledgerOwner(), Optional.of(deadline));
            return new Lease(backend, new PostgresqlPhysicalApproval(settings, settings.singleSession(backend), provenance, sealedRole));
        } catch (MigrationInspectionException failure) {
            discard(backend);
            throw failure;
        } catch (SQLException | MigrationHistoryException | RuntimeException failure) {
            discard(backend);
            throw new MigrationInspectionException(Failure.PERMISSION);
        }
    }

    private boolean lock(Connection admin) throws SQLException {
        String hash = PlanEncoding.digest(output -> {
            PlanEncoding.text(output, "TUPREL-PG-MANAGED-ISSUER");
            PlanEncoding.text(output, login.name());
        }).value();
        try (PreparedStatement query = MigrationCatalog.query(admin, "SELECT pg_catalog.pg_try_advisory_lock(?)")) {
            query.setLong(1, Long.parseUnsignedLong(hash.substring(0, 16), 16));
            try (ResultSet row = query.executeQuery()) { return row.next() && row.getBoolean(1); }
        }
    }

    private boolean idle(Connection admin) throws SQLException {
        try (PreparedStatement query = MigrationCatalog.query(admin, """
                SELECT NOT u.rolcanlogin AND u.rolpassword IS NULL AND u.rolconnlimit = 1
                  AND NOT u.rolsuper AND NOT u.rolcreaterole AND NOT u.rolcreatedb
                  AND NOT u.rolreplication AND NOT u.rolbypassrls
                  AND EXISTS (SELECT 1 FROM pg_catalog.pg_namespace n
                    JOIN pg_catalog.pg_roles o ON o.oid = n.nspowner
                    JOIN pg_catalog.pg_auth_members m ON m.roleid = o.oid
                    WHERE n.nspname = ? AND o.rolname = ? AND NOT o.rolcanlogin
                      AND m.member = u.oid AND m.set_option AND NOT m.inherit_option AND NOT m.admin_option)
                  AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_stat_activity s WHERE s.usesysid = u.oid)
                FROM pg_catalog.pg_authid u WHERE u.rolname = ?
                """)) {
            query.setString(1, schema.name());
            query.setString(2, role.owner().name());
            query.setString(3, login.name());
            try (ResultSet row = query.executeQuery()) { return row.next() && row.getBoolean(1); }
        }
    }

    private boolean eligible(Connection admin, Instant deadline) throws SQLException {
        try (PreparedStatement query = MigrationCatalog.query(admin, """
                SELECT rolcanlogin AND rolconnlimit = 1 AND rolpassword LIKE 'SCRAM-SHA-256$%'
                  AND rolvaliduntil <= ? AND rolvaliduntil > pg_catalog.clock_timestamp()
                FROM pg_catalog.pg_authid WHERE rolname = ?
                """)) {
            query.setObject(1, OffsetDateTime.ofInstant(deadline, ZoneOffset.UTC));
            query.setString(2, login.name());
            try (ResultSet row = query.executeQuery()) { return row.next() && row.getBoolean(1); }
        }
    }

    private boolean sealed(Connection admin, int pid) throws SQLException {
        try (PreparedStatement query = MigrationCatalog.query(admin, """
                SELECT NOT u.rolcanlogin AND u.rolpassword IS NULL AND u.rolconnlimit = 1
                  AND (SELECT pg_catalog.count(*) = 1 AND pg_catalog.bool_and(
                        s.pid = ? AND s.datname = pg_catalog.current_database())
                       FROM pg_catalog.pg_stat_activity s WHERE s.usesysid = u.oid)
                FROM pg_catalog.pg_authid u WHERE u.rolname = ?
                """)) {
            query.setInt(1, pid);
            query.setString(2, login.name());
            try (ResultSet row = query.executeQuery()) { return row.next() && row.getBoolean(1); }
        }
    }

    private static int backendPid(Connection connection) throws SQLException {
        try (PreparedStatement query = MigrationCatalog.query(connection, "SELECT pg_catalog.pg_backend_pid()");
                ResultSet row = query.executeQuery()) {
            if (!row.next()) { throw new SQLException("Missing managed backend"); }
            return row.getInt(1);
        }
    }

    private String quotedLogin() { return "\"" + login.name() + "\""; }

    private static void discard(Connection connection) {
        if (connection == null) { return; }
        try { if (!connection.isClosed()) { connection.abort(Runnable::run); } }
        catch (SQLException | RuntimeException ignored) { /* The settled migration result must be preserved. */ }
        finally {
            try { connection.close(); }
            catch (SQLException | RuntimeException ignored) { /* Never expose connection diagnostics. */ }
        }
    }

    /** Trusted provisioning hook. Only the supervisor sees the resulting short-lived settings. */
    @FunctionalInterface interface CredentialSource {
        MigrationDatabaseSettings issue(Connection administrativeSession) throws SQLException, MigrationHistoryException;
    }

    private record Lease(Connection backend, PostgresqlPhysicalApproval runner) { }
}
