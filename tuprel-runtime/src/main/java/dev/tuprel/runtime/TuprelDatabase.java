package dev.tuprel.runtime;

import dev.tuprel.sql.RenderedSql;
import dev.tuprel.sql.SqlCommand;
import dev.tuprel.sql.SqlQuery;
import dev.tuprel.sql.SqlRenderer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Savepoint;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * Synchronous, stateless executor of structural commands and queries; the caller owns the
 * DataSource.
 *
 * <p>Outside a transaction, each call obtains its own connection, requires autocommit and
 * closes it before returning (ADR-0009). {@link #transaction(TransactionOptions, TransactionWork)}
 * runs work on one connection with explicit commit and rollback; the instance passed to the work
 * is bound to that connection. No call starts a transaction implicitly.
 */
public final class TuprelDatabase {
    private final DataSource dataSource;
    private final SqlRenderer renderer;
    private final JdbcValueBinder binder;
    private final Optional<TransactionScope> scope;

    public TuprelDatabase(DataSource dataSource, SqlRenderer renderer, JdbcValueBinder binder) {
        this(Objects.requireNonNull(dataSource, "dataSource"), Objects.requireNonNull(renderer, "renderer"),
                Objects.requireNonNull(binder, "binder"), Optional.empty());
    }

    private TuprelDatabase(DataSource dataSource, SqlRenderer renderer, JdbcValueBinder binder,
            Optional<TransactionScope> scope) {
        this.dataSource = dataSource;
        this.renderer = renderer;
        this.binder = binder;
        this.scope = scope;
    }

    /** Whether this instance is bound to an active transaction. */
    public boolean inTransaction() {
        return scope.isPresent();
    }

    /** Inserts one row and returns the affected-row count. */
    public int create(SqlCommand.Insert command) { return updateCount(command); }

    /** Inserts one row and maps the row returned by the same statement. */
    public <T> T createReturning(SqlCommand.InsertReturning command, RowMapper<T> mapper) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(mapper, "mapper");
        return queryRows(renderer.render(command), mapper, StatementOptions.defaults()).stream().findFirst()
                .orElseThrow(() -> new TuprelDatabaseException(
                        TuprelDatabaseException.Phase.EXECUTION,
                        new SQLException("Insert returned no row")));
    }

    /** Changes one row selected by a non-null identifier value. */
    public int updateById(SqlCommand.UpdateById command) { return updateCount(command); }

    /**
     * Changes one row and maps the row returned by the same statement; empty when no row has
     * the identifier or, with a version check, when the version differs.
     */
    public <T> Optional<T> updateReturning(SqlCommand.UpdateByIdReturning command, RowMapper<T> mapper) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(mapper, "mapper");
        return queryRows(renderer.render(command), mapper, StatementOptions.defaults()).stream().findFirst();
    }

    /** Deletes one row selected by a non-null identifier value. */
    public int deleteById(SqlCommand.DeleteById command) { return updateCount(command); }

    /**
     * Executes a command that returns no rows, such as a multi-row insert or a conditional
     * update or delete, and returns the affected-row count.
     */
    public long executeUpdate(SqlCommand command) {
        Objects.requireNonNull(command, "command");
        if (command instanceof SqlCommand.InsertReturning || command instanceof SqlCommand.UpdateByIdReturning
                || command instanceof SqlCommand.FindById) {
            throw new IllegalArgumentException("Command returns rows; use the matching query method");
        }
        return updateCount(command);
    }

    /** Reads one row and maps it before all JDBC resources are closed. */
    public <T> Optional<T> findById(SqlCommand.FindById command, RowMapper<T> mapper) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(mapper, "mapper");
        return queryRows(renderer.render(command), mapper, StatementOptions.defaults()).stream().findFirst();
    }

    /**
     * Executes a column query and maps every returned row, in database order, into an
     * immutable list. All rows are read before resources close; use {@link #stream} for large
     * results.
     */
    public <T> List<T> findMany(SqlQuery query, RowMapper<T> mapper) {
        return findMany(query, mapper, StatementOptions.defaults());
    }

    /** Executes a column query with statement options. */
    public <T> List<T> findMany(SqlQuery query, RowMapper<T> mapper, StatementOptions options) {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(mapper, "mapper");
        Objects.requireNonNull(options, "options");
        require(query, SqlQuery.Selection.Columns.class);
        return queryRows(renderer.render(query), mapper, options);
    }

    /** Executes a count query. */
    public long count(SqlQuery query) {
        return count(query, StatementOptions.defaults());
    }

    /** Executes a count query with statement options. */
    public long count(SqlQuery query, StatementOptions options) {
        Objects.requireNonNull(query, "query");
        require(query, SqlQuery.Selection.Count.class);
        return single(queryRows(renderer.render(query), row -> row.int64("count"), options));
    }

    /** Executes an exists query. */
    public boolean exists(SqlQuery query) {
        return exists(query, StatementOptions.defaults());
    }

    /** Executes an exists query with statement options. */
    public boolean exists(SqlQuery query, StatementOptions options) {
        Objects.requireNonNull(query, "query");
        require(query, SqlQuery.Selection.Exists.class);
        return single(queryRows(renderer.render(query), row -> row.bool("exists"), options));
    }

    /**
     * Opens a forward-only stream over a column query. Streaming requires an explicit
     * transaction, which keeps the database cursor open and closes the stream when it ends. A
     * fetch size of zero uses 100 rows per round trip.
     */
    public <T> TuprelStream<T> stream(SqlQuery query, RowMapper<T> mapper, StatementOptions options) {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(mapper, "mapper");
        Objects.requireNonNull(options, "options");
        require(query, SqlQuery.Selection.Columns.class);
        TransactionScope transaction = scope.orElseThrow(
                () -> new IllegalStateException("Streaming requires an explicit transaction"));
        RenderedSql plan = renderer.render(query);
        PreparedStatement statement = prepare(transaction.connection(), plan.text());
        try {
            bind(statement, plan);
            applyTimeout(statement, options);
            try {
                statement.setFetchSize(options.fetchSize() == 0 ? 100 : options.fetchSize());
            } catch (SQLException exception) {
                throw new TuprelDatabaseException(TuprelDatabaseException.Phase.PREPARATION, exception);
            }
            ResultSet result;
            try {
                result = statement.executeQuery();
            } catch (SQLException exception) {
                throw new TuprelDatabaseException(TuprelDatabaseException.Phase.EXECUTION, exception);
            }
            TuprelStream<T> stream = new TuprelStream<>(statement, result, mapper, transaction);
            transaction.register(stream);
            return stream;
        } catch (RuntimeException failure) {
            try {
                statement.close();
            } catch (SQLException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    /** Renders a query exactly as it would execute, without opening a connection. */
    public RenderedSql preview(SqlQuery query) {
        return renderer.render(Objects.requireNonNull(query, "query"));
    }

    /** Runs work in a transaction with connection defaults; see {@link #transaction(TransactionOptions, TransactionWork)}. */
    public <T> T transaction(TransactionWork<T> work) {
        return transaction(TransactionOptions.defaults(), work);
    }

    /**
     * Runs work in a transaction and commits when it returns normally.
     *
     * <p>If the work throws, the transaction is rolled back and the original exception is
     * rethrown, with rollback failures suppressed. A failing commit is reported as a
     * {@link TuprelDatabaseException} in the {@code TRANSACTION} phase. The connection's
     * autocommit, isolation and read-only settings are restored before it is closed.
     *
     * <p>Called on a transaction-bound instance, this runs a nested block with a savepoint:
     * a failure rolls back to the savepoint and rethrows, so the outer transaction continues
     * only if the caller handles the exception. Nested blocks inherit the outer options and
     * reject different ones. Nothing is retried automatically, including serialization
     * failures.
     */
    public <T> T transaction(TransactionOptions options, TransactionWork<T> work) {
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(work, "work");
        if (scope.isPresent()) {
            return nested(scope.get(), options, work);
        }
        Connection connection = open();
        ConnectionState original;
        try {
            original = new ConnectionState(connection.getTransactionIsolation(), connection.isReadOnly());
            if (options.readOnly()) {
                connection.setReadOnly(true);
            }
            if (options.isolation().isPresent()) {
                connection.setTransactionIsolation(options.isolation().get().jdbcLevel());
            }
            connection.setAutoCommit(false);
        } catch (SQLException | RuntimeException exception) {
            closeQuietly(connection, exception);
            throw new TuprelDatabaseException(TuprelDatabaseException.Phase.TRANSACTION, exception);
        }
        TransactionScope transaction = TransactionScope.root(connection, options.timeout());
        T result;
        try {
            result = work.execute(new TuprelDatabase(dataSource, renderer, binder, Optional.of(transaction)));
        } catch (RuntimeException | Error failure) {
            transaction.end().ifPresent(failure::addSuppressed);
            rollback(connection, failure);
            restore(connection, original, failure);
            throw failure;
        }
        Optional<RuntimeException> streamFailure = transaction.end();
        if (streamFailure.isPresent()) {
            rollback(connection, streamFailure.get());
            restore(connection, original, streamFailure.get());
            throw streamFailure.get();
        }
        try {
            connection.commit();
        } catch (SQLException exception) {
            TuprelDatabaseException failure =
                    new TuprelDatabaseException(TuprelDatabaseException.Phase.TRANSACTION, exception);
            rollback(connection, failure);
            restore(connection, original, failure);
            throw failure;
        }
        TuprelDatabaseException closing = null;
        try {
            restoreSettings(connection, original);
        } catch (SQLException exception) {
            closing = new TuprelDatabaseException(TuprelDatabaseException.Phase.CLOSING, exception);
        }
        try {
            connection.close();
        } catch (SQLException exception) {
            if (closing == null) {
                closing = new TuprelDatabaseException(TuprelDatabaseException.Phase.CLOSING, exception);
            } else {
                closing.addSuppressed(exception);
            }
        }
        if (closing != null) {
            throw closing;
        }
        return result;
    }

    private <T> T nested(TransactionScope parent, TransactionOptions options, TransactionWork<T> work) {
        if (!options.equals(TransactionOptions.defaults())) {
            throw new IllegalArgumentException("A nested transaction inherits the outer transaction's options");
        }
        Connection connection = parent.connection();
        Savepoint savepoint;
        try {
            savepoint = connection.setSavepoint();
        } catch (SQLException exception) {
            throw new TuprelDatabaseException(TuprelDatabaseException.Phase.TRANSACTION, exception);
        }
        TransactionScope child = parent.child();
        T result;
        try {
            result = work.execute(new TuprelDatabase(dataSource, renderer, binder, Optional.of(child)));
            Optional<RuntimeException> streamFailure = child.end();
            if (streamFailure.isPresent()) {
                throw streamFailure.get();
            }
        } catch (RuntimeException | Error failure) {
            child.end().ifPresent(failure::addSuppressed);
            try {
                connection.rollback(savepoint);
            } catch (SQLException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw failure;
        }
        try {
            connection.releaseSavepoint(savepoint);
        } catch (SQLException exception) {
            throw new TuprelDatabaseException(TuprelDatabaseException.Phase.TRANSACTION, exception);
        }
        return result;
    }

    private record ConnectionState(int isolation, boolean readOnly) { }

    private static void rollback(Connection connection, Throwable failure) {
        try {
            connection.rollback();
        } catch (SQLException | RuntimeException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }

    private static void restore(Connection connection, ConnectionState original, Throwable failure) {
        try {
            restoreSettings(connection, original);
        } catch (SQLException | RuntimeException restoreFailure) {
            failure.addSuppressed(restoreFailure);
        }
        closeQuietly(connection, failure);
    }

    private static void restoreSettings(Connection connection, ConnectionState original) throws SQLException {
        connection.setAutoCommit(true);
        connection.setTransactionIsolation(original.isolation());
        connection.setReadOnly(original.readOnly());
    }

    private static void closeQuietly(Connection connection, Throwable failure) {
        try {
            connection.close();
        } catch (SQLException | RuntimeException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    private static void require(SqlQuery query, Class<? extends SqlQuery.Selection> selection) {
        if (!selection.isInstance(query.selection())) {
            throw new IllegalArgumentException("Query selection does not match the operation");
        }
        if (query.lock().isPresent() && !selection.equals(SqlQuery.Selection.Columns.class)) {
            throw new IllegalArgumentException("Only column queries can lock rows");
        }
    }

    private static <T> T single(List<T> rows) {
        if (rows.size() != 1) {
            throw new TuprelDatabaseException(TuprelDatabaseException.Phase.EXECUTION,
                    new SQLException("Aggregate query did not return exactly one row"));
        }
        return rows.getFirst();
    }

    private int updateCount(SqlCommand command) {
        Objects.requireNonNull(command, "command");
        RenderedSql plan = renderer.render(command);
        try (Lease lease = lease(); PreparedStatement statement = prepare(lease.connection(), plan.text())) {
            bind(statement, plan);
            applyTimeout(statement, StatementOptions.defaults());
            try {
                return statement.executeUpdate();
            } catch (SQLException exception) {
                throw new TuprelDatabaseException(TuprelDatabaseException.Phase.EXECUTION, exception);
            }
        } catch (SQLException exception) {
            throw new TuprelDatabaseException(TuprelDatabaseException.Phase.CLOSING, exception);
        }
    }

    private <T> List<T> queryRows(RenderedSql plan, RowMapper<T> mapper, StatementOptions options) {
        try (Lease lease = lease(); PreparedStatement statement = prepare(lease.connection(), plan.text())) {
            bind(statement, plan);
            applyTimeout(statement, options);
            if (options.fetchSize() > 0) {
                try {
                    statement.setFetchSize(options.fetchSize());
                } catch (SQLException exception) {
                    throw new TuprelDatabaseException(TuprelDatabaseException.Phase.PREPARATION, exception);
                }
            }
            ResultSet opened;
            try {
                opened = statement.executeQuery();
            } catch (SQLException exception) {
                throw new TuprelDatabaseException(TuprelDatabaseException.Phase.EXECUTION, exception);
            }
            try (ResultSet result = opened) {
                List<T> values = new ArrayList<>();
                while (true) {
                    boolean hasRow;
                    try {
                        hasRow = result.next();
                    } catch (SQLException exception) {
                        throw new TuprelDatabaseException(TuprelDatabaseException.Phase.MAPPING, exception);
                    }
                    if (!hasRow) {
                        return List.copyOf(values);
                    }
                    try {
                        values.add(Objects.requireNonNull(mapper.map(new JdbcRowReader(result)),
                                "mapper result"));
                    } catch (TuprelDatabaseException exception) {
                        throw exception;
                    } catch (RuntimeException exception) {
                        throw new TuprelDatabaseException(TuprelDatabaseException.Phase.MAPPING, exception);
                    }
                }
            }
        } catch (SQLException exception) {
            throw new TuprelDatabaseException(TuprelDatabaseException.Phase.CLOSING, exception);
        }
    }

    /** A connection for one statement: owned and closed outside a transaction, borrowed inside. */
    private interface Lease extends AutoCloseable {
        Connection connection();

        @Override
        void close() throws SQLException;
    }

    private Lease lease() {
        if (scope.isPresent()) {
            Connection borrowed = scope.get().connection();
            return new Lease() {
                @Override public Connection connection() { return borrowed; }
                @Override public void close() { }
            };
        }
        Connection owned = open();
        return new Lease() {
            @Override public Connection connection() { return owned; }
            @Override public void close() throws SQLException { owned.close(); }
        };
    }

    private void applyTimeout(PreparedStatement statement, StatementOptions options) {
        Optional<Duration> remaining = scope.flatMap(TransactionScope::remaining);
        if (remaining.isPresent() && (remaining.get().isNegative() || remaining.get().isZero())) {
            throw new TuprelDatabaseException(TuprelDatabaseException.Phase.EXECUTION,
                    new SQLTimeoutException("Transaction deadline exceeded"));
        }
        Optional<Duration> effective = options.timeout();
        if (remaining.isPresent() && (effective.isEmpty() || remaining.get().compareTo(effective.get()) < 0)) {
            effective = remaining;
        }
        if (effective.isPresent()) {
            long millis = effective.get().toMillis();
            int seconds = (int) Math.min(Integer.MAX_VALUE, Math.max(1, (millis + 999) / 1000));
            try {
                statement.setQueryTimeout(seconds);
            } catch (SQLException exception) {
                throw new TuprelDatabaseException(TuprelDatabaseException.Phase.PREPARATION, exception);
            }
        }
    }

    private Connection open() {
        Connection connection;
        try {
            connection = dataSource.getConnection();
        } catch (SQLException | RuntimeException exception) {
            throw new TuprelDatabaseException(TuprelDatabaseException.Phase.CONNECTION, exception);
        }
        if (connection == null) {
            throw new TuprelDatabaseException(TuprelDatabaseException.Phase.CONNECTION,
                    new SQLException("DataSource returned no connection"));
        }
        try {
            if (!connection.getAutoCommit()) {
                throw new SQLException("Autocommit is required outside an explicit transaction");
            }
            return connection;
        } catch (SQLException | RuntimeException exception) {
            try {
                connection.close();
            } catch (SQLException closeFailure) {
                exception.addSuppressed(closeFailure);
            }
            throw new TuprelDatabaseException(TuprelDatabaseException.Phase.CONNECTION, exception);
        }
    }

    private static PreparedStatement prepare(Connection connection, String sql) {
        try {
            return connection.prepareStatement(sql);
        } catch (SQLException exception) {
            throw new TuprelDatabaseException(TuprelDatabaseException.Phase.PREPARATION, exception);
        }
    }

    private void bind(PreparedStatement statement, RenderedSql plan) {
        try {
            for (int index = 0; index < plan.binds().size(); index++) {
                binder.bind(statement, index + 1, plan.binds().get(index));
            }
        } catch (SQLException exception) {
            throw new TuprelDatabaseException(TuprelDatabaseException.Phase.BINDING, exception);
        }
    }
}
