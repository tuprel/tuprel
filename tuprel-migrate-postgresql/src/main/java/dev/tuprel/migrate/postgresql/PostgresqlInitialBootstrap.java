package dev.tuprel.migrate.postgresql;

import dev.tuprel.migrate.MigrationHistory;
import dev.tuprel.migrate.MigrationHistoryException;
import dev.tuprel.migrate.postgresql.ddl.DdlStatement;
import dev.tuprel.sql.SqlIdentifier;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

/** Internal administrative bootstrap. The CLI and ordinary deploy path do not call it. */
final class PostgresqlInitialBootstrap {
    enum Outcome { BOOTSTRAPPED, NOT_COMMITTED, REFUSED, OUTCOME_UNKNOWN }

    private final MigrationDatabaseSettings settings;
    private final DataSource source;
    private final PublicKey issuer;
    private final UUID audience;
    private final UUID activeGeneration;
    private final InitialBootstrapLedger ledger;
    private final Clock clock;

    PostgresqlInitialBootstrap(MigrationDatabaseSettings settings, DataSource source, PublicKey issuer,
            UUID audience, UUID activeGeneration, InitialBootstrapLedger ledger, Clock clock) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.source = Objects.requireNonNull(source, "source");
        this.issuer = Objects.requireNonNull(issuer, "issuer");
        this.audience = Objects.requireNonNull(audience, "audience");
        this.activeGeneration = Objects.requireNonNull(activeGeneration, "activeGeneration");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    Outcome bootstrap(SqlIdentifier schema, InitialBootstrapAuthorization authorization) {
        return run(schema, authorization, false);
    }

    /** Fresh read-only reconciliation, permitted after the signed authorization expires. */
    Outcome reconcile(SqlIdentifier schema, InitialBootstrapAuthorization authorization) {
        return run(schema, authorization, true);
    }

    private Outcome run(SqlIdentifier schema, InitialBootstrapAuthorization authorization, boolean readOnly) {
        InitialBootstrapAuthorization.Claims claims = authorization.claims();
        if (!trusted(schema, authorization, !readOnly)) { return Outcome.REFUSED; }
        MigrationSession session;
        try { session = MigrationSession.open(source, settings, schema.name()); }
        catch (MigrationSessionException failure) { return readOnly ? Outcome.OUTCOME_UNKNOWN : Outcome.REFUSED; }
        try { return transaction(session, schema, claims, readOnly); }
        finally {
            try { session.closeSettled(); }
            catch (MigrationSessionException ignored) { /* Close already discarded; the transaction result stands. */ }
        }
    }

    private boolean trusted(SqlIdentifier schema, InitialBootstrapAuthorization authorization, boolean requireUnexpired) {
        InitialBootstrapAuthorization.Claims claims = authorization.claims();
        try {
            MigrationPlanTarget target = settings.planTarget(schema.name());
            return target.supportedSchema() && !schema.name().toLowerCase(Locale.ROOT).startsWith("_tuprel_")
                    && !schema.equals(ledger.schema()) && claims.ledger() == ledger.oid()
                    && audience.equals(claims.audience()) && activeGeneration.equals(claims.generation())
                    && target.fingerprint().equals(claims.fingerprint())
                    && (!requireUnexpired || !expired(claims)) && authorization.verifies(issuer);
        } catch (GeneralSecurityException | MigrationHistoryException failure) { return false; }
    }

    /** Verifies an already consumed provisioning authorization on the caller's live transaction. */
    boolean verifiesHistory(Connection connection, MigrationPlanTarget planTarget, PostgresqlCatalogObserver.Target target,
            long historyOid, InitialBootstrapAuthorization authorization) throws SQLException {
        InitialBootstrapAuthorization.Claims claims = authorization.claims();
        if (historyOid <= 0 || target.database() != claims.database() || target.namespace() != claims.namespace()
                || !planTarget.fingerprint().equals(claims.fingerprint())
                || !trusted(planTarget.schema(), authorization, false)
                || !targetPolicy(connection, claims) || !ledger.lock(connection, true)) { return false; }
        InitialBootstrapLedger.Receipt receipt = ledger.read(connection, claims);
        return receipt.registered() && claims.nonce().equals(receipt.nonce()) && receipt.historyOid() == historyOid;
    }

    private Outcome transaction(MigrationSession session, SqlIdentifier schema,
            InitialBootstrapAuthorization.Claims claims, boolean readOnly) {
        Connection connection = session.connection();
        Outcome decision;
        try {
            connection.setAutoCommit(false);
            PostgresqlCatalogObserver.configureTransaction(connection, readOnly);
            decision = inspect(connection, schema, claims, readOnly);
        } catch (SQLException | PostgresqlCatalogObserver.ObservationFailure | MigrationHistoryException | RuntimeException failure) {
            decision = readOnly ? Outcome.OUTCOME_UNKNOWN : Outcome.REFUSED;
        }
        if (!readOnly && decision == Outcome.BOOTSTRAPPED) {
            try { connection.commit(); return decision; }
            catch (SQLException | RuntimeException failure) {
                session.discard();
                return Outcome.OUTCOME_UNKNOWN;
            }
        }
        try { connection.rollback(); return decision; }
        catch (SQLException | RuntimeException failure) {
            session.discard();
            return Outcome.OUTCOME_UNKNOWN;
        }
    }

    private Outcome inspect(Connection connection, SqlIdentifier schema, InitialBootstrapAuthorization.Claims claims,
            boolean readOnly) throws SQLException, PostgresqlCatalogObserver.ObservationFailure, MigrationHistoryException {
        PostgresqlCatalogObserver.Target target = new PostgresqlCatalogObserver(settings, source)
                .verifyTarget(connection, schema, readOnly);
        if (target.database() != claims.database() || target.namespace() != claims.namespace()
                || !targetPolicy(connection, claims) || !ledger.lock(connection, readOnly)) { return Outcome.REFUSED; }
        InitialBootstrapLedger.Receipt receipt = ledger.read(connection, claims);
        if (!receipt.registered()) { return Outcome.REFUSED; }

        CatalogInventory inventory = new CatalogInventory(connection, schema, target.namespace());
        CatalogObservation observation = inventory.read(target.extensionNamespace());
        if (observation.state() != CatalogObservation.State.COMPLETE
                || !observation.snapshot().orElseThrow().tables().isEmpty()) { return Outcome.REFUSED; }
        String historyName = MigrationCatalog.table(new DdlStatement.TableName(schema, new SqlIdentifier(MigrationHistoryTable.NAME)));
        if (readOnly && claims.nonce().equals(receipt.nonce()) && receipt.historyOid() > 0
                && inventory.historyOid() == receipt.historyOid()
                && InitialBootstrapLedger.relationOid(connection, historyName) == receipt.historyOid()) {
            MigrationCatalog.execute(connection, "LOCK TABLE ONLY " + historyName + " IN ACCESS SHARE MODE");
            if (InitialBootstrapLedger.relationOid(connection, historyName) != receipt.historyOid()) { return Outcome.REFUSED; }
            MigrationHistory history = PostgresqlMigrationHistory.snapshot(connection, schema);
            return history.initialised() && history.applied().isEmpty() ? Outcome.BOOTSTRAPPED : Outcome.REFUSED;
        }
        if (receipt.nonce() != null || receipt.historyOid() != 0 || inventory.historyOid() != 0
                || !emptyNamespace(connection, schema, target.namespace())) { return Outcome.REFUSED; }
        if (readOnly) { return Outcome.NOT_COMMITTED; }
        if (expired(claims)) { return Outcome.REFUSED; }
        MigrationCatalog.execute(connection, MigrationHistoryTable.createSql(schema));
        long historyOid = InitialBootstrapLedger.relationOid(connection, historyName);
        return historyOid > 0 && ledger.consume(connection, claims, historyOid) ? Outcome.BOOTSTRAPPED : Outcome.REFUSED;
    }

    private boolean expired(InitialBootstrapAuthorization.Claims claims) {
        return !clock.instant().isBefore(claims.expiresAt());
    }

    private static boolean targetPolicy(Connection connection, InitialBootstrapAuthorization.Claims claims) throws SQLException {
        try (PreparedStatement query = MigrationCatalog.query(connection, """
                SELECT (SELECT system_identifier FROM pg_catalog.pg_control_system()) = ?
                  AND pg_catalog.current_setting('session_replication_role') = 'origin'
                  AND pg_catalog.current_setting('default_table_access_method') = 'heap'
                  AND pg_catalog.current_setting('default_tablespace') = ''
                  AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_event_trigger WHERE evtenabled <> 'D')
                  AND n.nspowner = r.oid
                  AND NOT EXISTS (SELECT 1 FROM pg_catalog.aclexplode(
                    COALESCE(n.nspacl, pg_catalog.acldefault('n', n.nspowner))) AS acl
                    WHERE acl.grantee <> r.oid AND acl.privilege_type = 'CREATE')
                FROM pg_catalog.pg_namespace n JOIN pg_catalog.pg_roles r ON r.rolname = CURRENT_USER
                WHERE n.oid = ?::oid
                """)) {
            query.setLong(1, claims.cluster());
            query.setLong(2, claims.namespace());
            try (ResultSet rows = query.executeQuery()) { return rows.next() && rows.getBoolean(1); }
        }
    }

    private static boolean emptyNamespace(Connection connection, SqlIdentifier schema, long namespace) throws SQLException {
        // Check namespace dependencies too. The application's physical drift profile does not cover all object classes.
        try (PreparedStatement query = MigrationCatalog.query(connection, """
                SELECT pg_catalog.to_regnamespace(?)::pg_catalog.oid = ?::oid
                  AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_depend
                    WHERE refclassid = 'pg_catalog.pg_namespace'::pg_catalog.regclass AND refobjid = ?::oid)
                """)) {
            query.setString(1, "\"" + schema.name() + "\"");
            query.setLong(2, namespace);
            query.setLong(3, namespace);
            try (ResultSet rows = query.executeQuery()) { return rows.next() && rows.getBoolean(1); }
        }
    }
}
