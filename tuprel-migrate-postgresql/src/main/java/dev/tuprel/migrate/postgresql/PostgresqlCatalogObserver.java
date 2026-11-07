package dev.tuprel.migrate.postgresql;

import static dev.tuprel.migrate.postgresql.CatalogObservation.Diagnostic;

import dev.tuprel.sql.SqlIdentifier;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import javax.sql.DataSource;

/**
 * Observes the approved PostgreSQL 17 catalog subset in a dedicated read-only snapshot.
 * The result describes one instant, even if external DDL commits during observation.
 * This API reads no application rows or migration history contents.
 */
public final class PostgresqlCatalogObserver {
    private final MigrationDatabaseSettings settings;
    private final DataSource source;

    /**
     * Uses the explicit migration connection settings and owns a fresh session for each call.
     * @param settings validated target and TLS configuration
     */
    public PostgresqlCatalogObserver(MigrationDatabaseSettings settings) {
        this(settings, settings.dataSource());
    }

    PostgresqlCatalogObserver(MigrationDatabaseSettings settings, DataSource source) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.source = Objects.requireNonNull(source, "source");
    }

    /**
     * Observes the exact schema on the configured primary. No state changes persist.
     * @param schema explicit application schema
     * @return an observation with a comparable snapshot only when coverage is complete
     */
    public CatalogObservation observe(SqlIdentifier schema) {
        return observe(schema, (connection, target, historyOid, catalog) -> catalog, CatalogObservation::unknown);
    }

    <T> T observe(SqlIdentifier schema, SnapshotRead<T> read, Function<Diagnostic, T> unknown) {
        Objects.requireNonNull(schema, "schema");
        String name = schema.name();
        if (name.startsWith("pg_") || name.equals("information_schema")
                || name.toLowerCase(Locale.ROOT).startsWith("_tuprel_")) {
            return unknown.apply(Diagnostic.TARGET);
        }
        T observation = null;
        try (Connection connection = source.getConnection()) {
            if (!connection.getAutoCommit()) {
                discard(connection);
                return unknown.apply(Diagnostic.SESSION);
            }
            connection.setAutoCommit(false);
            try {
                configureTransaction(connection);
                Target target = verifyTarget(connection, schema);
                CatalogInventory inventory = new CatalogInventory(connection, schema, target.namespace());
                CatalogObservation catalog = inventory.read(target.extensionNamespace());
                observation = read.read(connection, target, inventory.historyOid(), catalog);
            } catch (ObservationFailure failure) {
                observation = unknown.apply(failure.diagnostic);
            } catch (SQLException failure) {
                observation = unknown.apply(classify(failure));
            } catch (RuntimeException failure) {
                observation = unknown.apply(Diagnostic.METADATA);
            }
            try {
                connection.rollback();
            } catch (SQLException | RuntimeException failure) {
                discard(connection);
                observation = unknown.apply(Diagnostic.CLEANUP);
            }
        } catch (SQLException failure) {
            return unknown.apply(observation == null ? classify(failure) : Diagnostic.CLEANUP);
        } catch (RuntimeException failure) {
            return unknown.apply(observation == null ? Diagnostic.READ : Diagnostic.CLEANUP);
        }
        return observation;
    }

    static void configureTransaction(Connection connection) throws SQLException {
        configureTransaction(connection, true);
    }

    static void configureTransaction(Connection connection, boolean readOnly) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(5);
            statement.execute(readOnly ? "SET TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY"
                    : "SET TRANSACTION ISOLATION LEVEL REPEATABLE READ READ WRITE");
            statement.execute("SET LOCAL search_path = pg_catalog");
            statement.execute("SET LOCAL lock_timeout = '2s'");
            statement.execute("SET LOCAL statement_timeout = '5s'");
            statement.execute("SET LOCAL transaction_timeout = '20s'");
        }
    }

    private Target verifyTarget(Connection connection, SqlIdentifier schema) throws SQLException, ObservationFailure {
        return verifyTarget(connection, schema, true);
    }

    Target verifyTarget(Connection connection, SqlIdentifier schema, boolean readOnly) throws SQLException, ObservationFailure {
        return verifyTarget(connection, schema, readOnly, "repeatable read", Optional.empty());
    }

    Target verifyWriteTarget(Connection connection, SqlIdentifier schema) throws SQLException, ObservationFailure {
        return verifyTarget(connection, schema, false, "read committed", Optional.empty());
    }

    /** Rechecks names and effective role after TLS was verified as the login in this transaction. */
    Target verifyManagedTarget(Connection connection, SqlIdentifier schema, boolean readOnly, SqlIdentifier owner)
            throws SQLException, ObservationFailure {
        return verifyTarget(connection, schema, readOnly, readOnly ? "repeatable read" : "read committed", Optional.of(owner));
    }

    private Target verifyTarget(Connection connection, SqlIdentifier schema, boolean readOnly, String isolation,
            Optional<SqlIdentifier> owner)
            throws SQLException, ObservationFailure {
        if (!settings.matchesEndpoint(connection)) { throw new ObservationFailure(Diagnostic.TARGET); }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT d.oid, n.oid, d.datname, n.nspname, CURRENT_USER, SESSION_USER, s.ssl,
                       pg_catalog.has_schema_privilege(n.oid, 'USAGE'),
                       pg_catalog.current_setting('server_version_num')::pg_catalog.int4 / 10000,
                       pg_catalog.pg_is_in_recovery(),
                       pg_catalog.current_setting('transaction_isolation'),
                       pg_catalog.current_setting('transaction_read_only'),
                       EXISTS (SELECT 1 FROM pg_catalog.pg_depend p
                               WHERE p.classid = 'pg_catalog.pg_namespace'::pg_catalog.regclass
                                 AND p.objid = n.oid AND p.deptype IN ('e', 'x'))
                FROM pg_catalog.pg_database d
                CROSS JOIN pg_catalog.pg_namespace n
                LEFT JOIN pg_catalog.pg_stat_ssl s ON s.pid = pg_catalog.pg_backend_pid()
                WHERE d.datname = pg_catalog.current_database()
                  AND n.nspname COLLATE pg_catalog."C" = ?
                """)) {
            statement.setQueryTimeout(5);
            statement.setMaxRows(2);
            statement.setString(1, schema.name());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) { throw new ObservationFailure(Diagnostic.TARGET); }
                long database = row.getLong(1);
                long namespace = row.getLong(2);
                String databaseName = row.getString(3);
                String currentUser = row.getString(5);
                String sessionUser = row.getString(6);
                Boolean tls = row.getObject(7, Boolean.class);
                boolean identity = owner.isPresent()
                        ? settings.matchesRole(databaseName, currentUser, sessionUser, owner.orElseThrow().name())
                        : settings.matchesIdentity(databaseName, currentUser, sessionUser, tls);
                if (database <= 0 || namespace <= 0 || !schema.name().equals(row.getString(4)) || !identity) {
                    throw new ObservationFailure(Diagnostic.TARGET);
                }
                if (!row.getBoolean(8)) { throw new ObservationFailure(Diagnostic.PERMISSION); }
                if (row.getInt(9) != 17 || row.getBoolean(10) || !isolation.equals(row.getString(11))
                        || !(readOnly ? "on" : "off").equals(row.getString(12))) {
                    throw new ObservationFailure(Diagnostic.SESSION);
                }
                boolean extensionNamespace = row.getBoolean(13);
                if (row.next()) { throw new ObservationFailure(Diagnostic.TARGET); }
                return new Target(database, namespace, extensionNamespace);
            }
        }
    }

    static Diagnostic classify(SQLException failure) {
        String state = failure.getSQLState();
        if ("42501".equals(state)) { return Diagnostic.PERMISSION; }
        if (failure instanceof SQLTimeoutException || "57014".equals(state)
                || "55P03".equals(state) || "25P04".equals(state) || "HYT00".equals(state)) {
            return Diagnostic.TIMEOUT;
        }
        return Diagnostic.READ;
    }

    private static void discard(Connection connection) {
        try { connection.abort(Runnable::run); }
        catch (SQLException | RuntimeException ignored) { /* Closing still runs. */ }
    }

    record Target(long database, long namespace, boolean extensionNamespace) { }

    @FunctionalInterface
    interface SnapshotRead<T> {
        T read(Connection connection, Target target, long historyOid, CatalogObservation catalog) throws SQLException;
    }

    static final class ObservationFailure extends Exception {
        private static final long serialVersionUID = 1L;
        private final Diagnostic diagnostic;

        ObservationFailure(Diagnostic diagnostic) { this.diagnostic = diagnostic; }
    }
}
