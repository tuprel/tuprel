package dev.tuprel.migrate.postgresql;

import static dev.tuprel.migrate.MigrationHistoryException.Reason;

import dev.tuprel.migrate.AppliedMigration;
import dev.tuprel.migrate.MigrationChecksum;
import dev.tuprel.migrate.MigrationDirectory;
import dev.tuprel.migrate.MigrationHistory;
import dev.tuprel.migrate.MigrationHistoryException;
import dev.tuprel.migrate.MigrationHistoryReader;
import dev.tuprel.migrate.MigrationId;
import dev.tuprel.sql.SqlIdentifier;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.sql.DataSource;

/**
 * PostgreSQL history observer. Never creates objects, writes rows or applies
 * migrations; completes its read-only snapshot by rollback. The DataSource must supply an idle auto-commit connection;
 * it must not expose a caller's active transaction. Driver is supplied by caller.
 */
public final class PostgresqlMigrationHistory implements MigrationHistoryReader {
    private final DataSource dataSource;

    /**
     * Uses caller-owned configuration, owning and closing each borrowed connection.
     * @param dataSource idle connections supporting read-only repeatable-read snapshots
     */
    public PostgresqlMigrationHistory(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public MigrationHistory read(String targetSchema) throws MigrationHistoryException {
        SqlIdentifier schema;
        try { schema = new SqlIdentifier(targetSchema); }
        catch (IllegalArgumentException | NullPointerException exception) { throw failure(Reason.CONFIGURATION); }
        try (Connection connection = dataSource.getConnection()) {
            if (!connection.getAutoCommit()) { throw failure(Reason.TRANSACTION); }
            int isolation = connection.getTransactionIsolation();
            boolean readOnly = connection.isReadOnly();
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            MigrationHistory history;
            try {
                try (Statement statement = connection.createStatement()) {
                    statement.setQueryTimeout(10);
                    statement.execute("SET LOCAL search_path = pg_catalog");
                }
                verifyTransaction(connection);
                history = snapshot(connection, schema);
            } catch (SQLException | MigrationHistoryException | RuntimeException exception) {
                rollbackOrAbort(connection);
                if (exception instanceof MigrationHistoryException safe) { throw safe; }
                if (exception instanceof SQLException sql) { throw sqlFailure(sql); }
                throw failure(Reason.INCOMPATIBLE);
            }
            // A failed rollback/close cannot turn a tentative snapshot into success.
            rollbackOrAbort(connection);
            connection.setAutoCommit(true);
            connection.setTransactionIsolation(isolation);
            connection.setReadOnly(readOnly);
            return history;
        } catch (MigrationHistoryException exception) {
            // try-with-resources may have attached a raw driver close failure.
            throw failure(exception.reason());
        } catch (SQLException exception) {
            throw sqlFailure(exception);
        } catch (RuntimeException exception) {
            throw failure(Reason.JDBC);
        }
    }

    private static void verifyTransaction(Connection connection) throws SQLException, MigrationHistoryException {
        try (PreparedStatement statement = query(connection, """
                SELECT pg_catalog.current_setting('transaction_isolation'),
                       pg_catalog.current_setting('transaction_read_only')
                """); ResultSet result = statement.executeQuery()) {
            if (!result.next() || !result.getString(1).equals("repeatable read") || !result.getString(2).equals("on")) {
                throw failure(Reason.TRANSACTION);
            }
        }
    }

    private static MigrationHistory snapshot(Connection connection, SqlIdentifier schema)
            throws SQLException, MigrationHistoryException {
        long namespace;
        try (PreparedStatement statement = query(connection, """
                SELECT oid, pg_catalog.has_schema_privilege(oid, 'USAGE')
                FROM pg_catalog.pg_namespace WHERE nspname = ?
                """)) {
            statement.setString(1, schema.name());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) { throw failure(Reason.MISSING_SCHEMA); }
                namespace = result.getLong(1);
                if (!result.getBoolean(2)) { throw failure(Reason.PERMISSION); }
            }
        }
        long relation;
        try (PreparedStatement statement = query(connection, """
                SELECT c.oid, c.relkind = 'r' AND c.relpersistence = 'p'
                  AND NOT c.relrowsecurity AND NOT c.relforcerowsecurity AND NOT c.relispartition
                  AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_inherits WHERE inhrelid = c.oid OR inhparent = c.oid)
                  AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_rewrite WHERE ev_class = c.oid)
                  AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_trigger WHERE tgrelid = c.oid AND NOT tgisinternal),
                  pg_catalog.has_table_privilege(c.oid, 'SELECT')
                FROM pg_catalog.pg_class c WHERE c.relnamespace = ?::oid AND c.relname = ?
                """)) {
            statement.setLong(1, namespace);
            statement.setString(2, MigrationHistoryTable.NAME);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) { return new MigrationHistory(false, List.of()); }
                relation = result.getLong(1);
                if (!result.getBoolean(2)) { throw failure(Reason.INCOMPATIBLE); }
                if (!result.getBoolean(3)) { throw failure(Reason.PERMISSION); }
            }
        }
        verifyStructure(connection, relation);
        List<AppliedMigration> rows = new ArrayList<>();
        // This is the only dynamic SQL identifier: validated SqlIdentifier, quoted
        // as one component. All catalog lookup values above use binding.
        String sql = "SELECT format_version, migration_id, migration_name, checksum, state, started_at, finished_at, "
                + "execution_millis, engine_version FROM \"" + schema.name()
                + "\".\"_tuprel_migrations\" ORDER BY migration_id COLLATE \"C\"";
        try (PreparedStatement statement = query(connection, sql)) {
            statement.setMaxRows(MigrationDirectory.MAX_MIGRATIONS + 1);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    if (rows.size() >= MigrationDirectory.MAX_MIGRATIONS || result.getInt(1) != 1
                            || !"APPLIED".equals(result.getString(5))) { throw failure(Reason.INCOMPATIBLE); }
                    rows.add(new AppliedMigration(new MigrationId(result.getString(2)), result.getString(3),
                            new MigrationChecksum(result.getString(4)),
                            result.getObject(6, OffsetDateTime.class).toInstant(),
                            result.getObject(7, OffsetDateTime.class).toInstant(), result.getLong(8), result.getString(9)));
                }
            }
        }
        return new MigrationHistory(true, rows);
    }

    private static void verifyStructure(Connection connection, long relation) throws SQLException, MigrationHistoryException {
        Map<String, String> actual = new HashMap<>();
        try (PreparedStatement statement = query(connection, """
                SELECT a.attname, pg_catalog.format_type(a.atttypid, a.atttypmod),
                       a.attnotnull AND NOT a.attisdropped AND NOT a.atthasdef
                       AND a.attidentity = '' AND a.attgenerated = '' AND a.attcollation = t.typcollation
                FROM pg_catalog.pg_attribute a LEFT JOIN pg_catalog.pg_type t ON t.oid = a.atttypid
                WHERE a.attrelid = ?::oid AND a.attnum > 0
                """)) {
            statement.setLong(1, relation);
            statement.setMaxRows(11);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    if (!result.getBoolean(3)) { throw failure(Reason.INCOMPATIBLE); }
                    actual.put(result.getString(1), result.getString(2));
                }
            }
        }
        if (!actual.equals(MigrationHistoryTable.columns())) { throw failure(Reason.INCOMPATIBLE); }
        Set<String> constraints = new HashSet<>();
        int count = 0;
        try (PreparedStatement statement = query(connection, """
                SELECT convalidated AND NOT condeferrable AND NOT condeferred,
                       pg_catalog.pg_get_constraintdef(oid, false)
                FROM pg_catalog.pg_constraint WHERE conrelid = ?::oid
                """)) {
            statement.setLong(1, relation);
            statement.setMaxRows(7);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    if (!result.getBoolean(1)) { throw failure(Reason.INCOMPATIBLE); }
                    constraints.add(result.getString(2));
                    count++;
                }
            }
        }
        if (count != MigrationHistoryTable.constraints().size() || !constraints.equals(MigrationHistoryTable.constraints())) { throw failure(Reason.INCOMPATIBLE); }
        try (PreparedStatement statement = query(connection, """
                SELECT 1 FROM pg_catalog.pg_index WHERE indrelid = ?::oid
                AND indisprimary AND indisvalid AND indisready AND indisunique AND indpred IS NULL
                """)) {
            statement.setLong(1, relation);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) { throw failure(Reason.INCOMPATIBLE); }
            }
        }
    }

    private static PreparedStatement query(Connection connection, String sql) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        try { statement.setQueryTimeout(10); return statement; }
        catch (SQLException exception) { statement.close(); throw exception; }
    }

    private static void rollbackOrAbort(Connection connection) throws MigrationHistoryException {
        try { connection.rollback(); }
        catch (SQLException exception) {
            try { connection.abort(Runnable::run); } catch (SQLException ignored) { /* Close still runs; never return a result. */ }
            throw failure(Reason.TRANSACTION);
        }
    }

    private static MigrationHistoryException sqlFailure(SQLException exception) {
        String state = exception.getSQLState();
        return failure("42501".equals(state) ? Reason.PERMISSION
                : exception instanceof java.sql.SQLTimeoutException || "57014".equals(state) || "HYT00".equals(state) ? Reason.TIMEOUT : Reason.JDBC);
    }

    private static MigrationHistoryException failure(Reason reason) { return new MigrationHistoryException(reason); }
}
