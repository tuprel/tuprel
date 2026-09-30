package dev.tuprel.runtime;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * Forward-only, single-pass rows of a query read in batches of the fetch size, holding an open
 * statement and result set until {@link #close()}.
 *
 * <p>A stream only exists inside an explicit transaction and is confined to its thread. Use it
 * with try-with-resources; the transaction also closes any stream still open when it ends, and
 * using the stream afterwards fails. It can be iterated once.
 *
 * @param <T> row type
 */
public final class TuprelStream<T> implements Iterable<T>, AutoCloseable {
    private final PreparedStatement statement;
    private final ResultSet result;
    private final RowMapper<T> mapper;
    private final TransactionScope scope;
    private boolean iterated;
    private boolean closed;

    TuprelStream(PreparedStatement statement, ResultSet result, RowMapper<T> mapper, TransactionScope scope) {
        this.statement = statement;
        this.result = result;
        this.mapper = mapper;
        this.scope = scope;
    }

    /** The single iterator over the remaining rows. */
    @Override
    public Iterator<T> iterator() {
        ensureOpen();
        if (iterated) {
            throw new IllegalStateException("A stream can only be iterated once");
        }
        iterated = true;
        return new Iterator<>() {
            private boolean fetched;
            private boolean available;

            @Override
            public boolean hasNext() {
                ensureOpen();
                if (!fetched) {
                    try {
                        available = result.next();
                    } catch (SQLException exception) {
                        throw new TuprelDatabaseException(TuprelDatabaseException.Phase.MAPPING, exception);
                    }
                    fetched = true;
                }
                return available;
            }

            @Override
            public T next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                fetched = false;
                try {
                    return Objects.requireNonNull(mapper.map(new JdbcRowReader(result)), "mapper result");
                } catch (TuprelDatabaseException exception) {
                    throw exception;
                } catch (RuntimeException exception) {
                    throw new TuprelDatabaseException(TuprelDatabaseException.Phase.MAPPING, exception);
                }
            }
        };
    }

    /** Closes the result set and statement; closing again has no effect. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        scope.unregister(this);
        SQLException failure = null;
        try {
            result.close();
        } catch (SQLException exception) {
            failure = exception;
        }
        try {
            statement.close();
        } catch (SQLException exception) {
            if (failure == null) {
                failure = exception;
            } else {
                failure.addSuppressed(exception);
            }
        }
        if (failure != null) {
            throw new TuprelDatabaseException(TuprelDatabaseException.Phase.CLOSING, failure);
        }
    }

    private void ensureOpen() {
        scope.connection();
        if (closed) {
            throw new IllegalStateException("The stream is closed");
        }
    }
}
