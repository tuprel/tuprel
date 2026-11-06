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
        Objects.requireNonNull(schema, "schema");
        String name = schema.name();
        if (name.startsWith("pg_") || name.equals("information_schema")
                || name.toLowerCase(Locale.ROOT).startsWith("_tuprel_")) {
            return CatalogObservation.unknown(Diagnostic.TARGET);
        }
        CatalogObservation observation = null;
        try (Connection connection = source.getConnection()) {
            if (!connection.getAutoCommit()) {
                discard(connection);
                return CatalogObservation.unknown(Diagnostic.SESSION);
            }
            connection.setAutoCommit(false);
            try {
                configureTransaction(connection);
                Target target = verifyTarget(connection, schema);
                observation = new CatalogInventory(connection, schema, target.namespace()).read(target.extensionNamespace());
            } catch (ObservationFailure failure) {
                observation = CatalogObservation.unknown(failure.diagnostic);
            } catch (SQLException failure) {
                observation = CatalogObservation.unknown(classify(failure));
            } catch (RuntimeException failure) {
                observation = CatalogObservation.unknown(Diagnostic.METADATA);
            }
            try {
                connection.rollback();
            } catch (SQLException | RuntimeException failure) {
                discard(connection);
                observation = CatalogObservation.unknown(Diagnostic.CLEANUP);
            }
        } catch (SQLException failure) {
            return CatalogObservation.unknown(observation == null ? classify(failure) : Diagnostic.CLEANUP);
        } catch (RuntimeException failure) {
            return CatalogObservation.unknown(observation == null ? Diagnostic.READ : Diagnostic.CLEANUP);
        }
        return observation;
    }

    static void configureTransaction(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(5);
            statement.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY");
            statement.execute("SET LOCAL search_path = pg_catalog");
            statement.execute("SET LOCAL lock_timeout = '2s'");
            statement.execute("SET LOCAL statement_timeout = '5s'");
            statement.execute("SET LOCAL transaction_timeout = '20s'");
        }
    }

    private Target verifyTarget(Connection connection, SqlIdentifier schema) throws SQLException, ObservationFailure {
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
                if (database <= 0 || namespace <= 0 || !schema.name().equals(row.getString(4))
                        || !settings.matchesIdentity(row.getString(3), row.getString(5), row.getString(6),
                                row.getObject(7, Boolean.class))) {
                    throw new ObservationFailure(Diagnostic.TARGET);
                }
                if (!row.getBoolean(8)) { throw new ObservationFailure(Diagnostic.PERMISSION); }
                if (row.getInt(9) != 17 || row.getBoolean(10) || !"repeatable read".equals(row.getString(11))
                        || !"on".equals(row.getString(12))) {
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

    private record Target(long database, long namespace, boolean extensionNamespace) { }

    static final class ObservationFailure extends Exception {
        private static final long serialVersionUID = 1L;
        private final Diagnostic diagnostic;

        ObservationFailure(Diagnostic diagnostic) { this.diagnostic = diagnostic; }
    }
}
