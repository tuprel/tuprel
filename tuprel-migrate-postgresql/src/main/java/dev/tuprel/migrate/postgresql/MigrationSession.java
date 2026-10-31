package dev.tuprel.migrate.postgresql;

import static dev.tuprel.migrate.postgresql.MigrationSessionException.Reason;

import dev.tuprel.migrate.MigrationHistoryException;
import dev.tuprel.sql.SqlIdentifier;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;

/** Owned dedicated session. Caller must supply idle connections with physical abort/discard semantics. */
final class MigrationSession implements AutoCloseable {
    private static final Duration LOCK_TIMEOUT = Duration.ofSeconds(30);
    private final Connection connection;
    private final long key;
    private boolean closed;
    private boolean discarded;

    private MigrationSession(Connection connection, long key) { this.connection = connection; this.key = key; }

    static MigrationSession open(DataSource source, MigrationDatabaseSettings settings, String schema)
            throws MigrationSessionException {
        return open(source, settings, schema, LOCK_TIMEOUT);
    }

    /** A shorter deadline is available internally for deterministic contention tests, never a longer one. */
    static MigrationSession open(DataSource source, MigrationDatabaseSettings settings, String schema, Duration timeout)
            throws MigrationSessionException {
        Objects.requireNonNull(source, "source"); Objects.requireNonNull(settings, "settings");
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(LOCK_TIMEOUT) > 0) {
            throw new MigrationSessionException(Reason.TARGET);
        }
        MigrationPlanTarget target;
        try {
            target = settings.planTarget(schema);
            if (!target.supportedSchema()) { throw new MigrationSessionException(Reason.TARGET); }
        } catch (MigrationHistoryException exception) { throw new MigrationSessionException(Reason.TARGET); }
        if (Thread.currentThread().isInterrupted()) { throw new MigrationSessionException(Reason.INTERRUPTED); }
        Connection connection = null;
        try {
            connection = source.getConnection();
            if (!connection.getAutoCommit()) { throw new MigrationSessionException(Reason.ACTIVE_TRANSACTION); }
            // Own the resolution context before any catalog predicate or lock query.
            try (Statement statement = connection.createStatement()) {
                statement.setQueryTimeout(10);
                statement.execute("SET search_path = pg_catalog");
            }
            if (!settings.matches(connection, target.schema())) { throw new MigrationSessionException(Reason.TARGET); }
            try (PreparedStatement query = query(connection, """
                    SELECT EXISTS (SELECT 1 FROM pg_catalog.pg_locks
                      WHERE pid = pg_catalog.pg_backend_pid() AND locktype = 'advisory')
                    """); ResultSet result = query.executeQuery()) {
                if (!result.next() || result.getBoolean(1)) { throw new MigrationSessionException(Reason.PREHELD_LOCK); }
            }
            long key = key(target.schema());
            long started = System.nanoTime();
            while (true) {
                if (Thread.currentThread().isInterrupted()) { throw new MigrationSessionException(Reason.INTERRUPTED); }
                if (System.nanoTime() - started >= timeout.toNanos()) { throw new MigrationSessionException(Reason.LOCK_BUSY); }
                if (lock(connection, true, key)) { return new MigrationSession(connection, key); }
                long remaining = timeout.toNanos() - (System.nanoTime() - started);
                if (remaining <= 0) { throw new MigrationSessionException(Reason.LOCK_BUSY); }
                try { TimeUnit.NANOSECONDS.sleep(Math.min(TimeUnit.MILLISECONDS.toNanos(100), remaining)); }
                catch (InterruptedException exception) {
                    Thread.currentThread().interrupt(); throw new MigrationSessionException(Reason.INTERRUPTED);
                }
            }
        } catch (SQLException | RuntimeException exception) {
            if (!discardPhysical(connection)) { throw new MigrationSessionException(Reason.CLEANUP); }
            throw new MigrationSessionException(Reason.CONNECTION);
        } catch (MigrationSessionException exception) {
            if (!discardPhysical(connection)) { throw new MigrationSessionException(Reason.CLEANUP); }
            throw new MigrationSessionException(exception.reason());
        }
    }

    static long key(SqlIdentifier schema) {
        String hash = PlanEncoding.digest(output -> {
            PlanEncoding.text(output, "TUPREL-PG-MIGRATION-LOCK"); output.writeInt(1);
            PlanEncoding.text(output, schema.name());
        }).value();
        return Long.parseUnsignedLong(hash.substring(0, 16), 16);
    }

    Connection connection() {
        if (closed) { throw new IllegalStateException("Migration session is closed"); }
        return connection;
    }

    /** Poisoned/uncertain sessions must never execute more SQL or return to a pool. */
    boolean discard() {
        if (closed) { return discarded; }
        closed = true;
        discarded = discardPhysical(connection);
        return discarded;
    }

    @Override public void close() throws MigrationSessionException {
        close(false);
    }

    /** The internal runner has confirmed commit/rollback and will issue no further transactional SQL. */
    void closeSettled() throws MigrationSessionException { close(true); }

    private void close(boolean settled) throws MigrationSessionException {
        if (closed) { return; }
        closed = true;
        Reason failure = null;
        try {
            if (!settled && !connection.getAutoCommit()) {
                try { connection.rollback(); }
                catch (SQLException | RuntimeException exception) { throw new MigrationSessionException(Reason.OUTCOME_UNKNOWN); }
            }
            if (!lock(connection, false, key)) { failure = Reason.CLEANUP; }
        } catch (MigrationSessionException exception) { failure = exception.reason(); }
        catch (SQLException | RuntimeException exception) { failure = Reason.CLEANUP; }
        finally {
            if (failure != null) { discarded = discardPhysical(connection); }
            else {
                try { connection.close(); discarded = true; }
                catch (SQLException | RuntimeException exception) {
                    discarded = discardPhysical(connection); failure = Reason.CLEANUP;
                }
            }
        }
        if (failure != null) { throw new MigrationSessionException(failure); }
    }

    private static PreparedStatement query(Connection connection, String sql) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        try { statement.setQueryTimeout(10); return statement; }
        catch (SQLException exception) { statement.close(); throw exception; }
    }

    private static boolean lock(Connection connection, boolean acquire, long key) throws SQLException {
        String sql = acquire ? "SELECT pg_catalog.pg_try_advisory_lock(?)" : "SELECT pg_catalog.pg_advisory_unlock(?)";
        try (PreparedStatement statement = query(connection, sql)) {
            statement.setLong(1, key);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) { throw new SQLException("No lock result"); }
                return result.getBoolean(1);
            }
        }
    }

    private static boolean discardPhysical(Connection connection) {
        if (connection == null) { return true; }
        boolean discarded = false;
        try { connection.abort(Runnable::run); discarded = true; } catch (SQLException | RuntimeException ignored) { /* Still close. */ }
        try { connection.close(); discarded = true; } catch (SQLException | RuntimeException ignored) { /* No raw cause escapes. */ }
        return discarded;
    }
}
