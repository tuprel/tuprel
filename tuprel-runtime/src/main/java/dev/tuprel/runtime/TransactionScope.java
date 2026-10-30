package dev.tuprel.runtime;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * State of one transaction or savepoint block: the shared connection, the owning thread, the
 * deadline and the streams opened inside it. It is confined to the owning thread.
 */
final class TransactionScope {
    private final Connection connection;
    private final Thread owner;
    private final Optional<Long> deadlineNanos;
    private final Optional<TransactionScope> parent;
    private final LongSupplier nanoTime;
    private final List<TuprelStream<?>> streams = new ArrayList<>();
    private boolean active = true;
    private Optional<Throwable> rollbackOnly = Optional.empty();

    private TransactionScope(Connection connection, Thread owner, Optional<Long> deadlineNanos,
            Optional<TransactionScope> parent, LongSupplier nanoTime) {
        this.connection = connection;
        this.owner = owner;
        this.deadlineNanos = deadlineNanos;
        this.parent = parent;
        this.nanoTime = nanoTime;
    }

    static TransactionScope root(Connection connection, Optional<Duration> timeout) {
        return root(connection, timeout, System::nanoTime);
    }

    static TransactionScope root(Connection connection, Optional<Duration> timeout, LongSupplier nanoTime) {
        return new TransactionScope(connection, Thread.currentThread(),
                timeout.map(value -> nanoTime.getAsLong() + value.toNanos()), Optional.empty(), nanoTime);
    }

    TransactionScope child() {
        return new TransactionScope(connection, owner, deadlineNanos, Optional.of(this), nanoTime);
    }

    /** Fails when used from another thread or after the block has ended. */
    Connection connection() {
        if (!owner.equals(Thread.currentThread())) {
            throw new IllegalStateException("A transaction is confined to the thread that started it");
        }
        if (!isActive()) {
            throw new IllegalStateException("The transaction is no longer active");
        }
        return connection;
    }

    private boolean isActive() {
        return active && parent.map(TransactionScope::isActive).orElse(true);
    }

    /** Remaining time before the deadline; empty without a deadline, zero or negative when due. */
    Optional<Duration> remaining() {
        return deadlineNanos.map(deadline -> Duration.ofNanos(deadline - nanoTime.getAsLong()));
    }

    void register(TuprelStream<?> stream) {
        streams.add(stream);
    }

    void unregister(TuprelStream<?> stream) {
        streams.remove(stream);
    }

    void markRollbackOnly(Throwable failure) {
        if (rollbackOnly.isEmpty()) {
            rollbackOnly = Optional.of(failure);
        }
        parent.ifPresent(scope -> scope.markRollbackOnly(failure));
    }

    void markStatementFailure(SQLException failure) {
        if (rollbackOnly.isEmpty()) {
            rollbackOnly = Optional.of(failure);
        }
    }

    void requireCommittable() {
        if (rollbackOnly.isPresent()) {
            throw new TuprelDatabaseException(TuprelDatabaseException.Phase.TRANSACTION,
                    new SQLException("Transaction cannot commit after a failed JDBC operation or savepoint recovery", "25000",
                            rollbackOnly.get()));
        }
        Optional<Duration> time = remaining();
        if (time.isPresent() && (time.get().isZero() || time.get().isNegative())) {
            throw new TuprelDatabaseException(TuprelDatabaseException.Phase.TRANSACTION,
                    new SQLTimeoutException("Transaction deadline exceeded"));
        }
    }

    /** Ends the block and closes streams left open; returns the first close failure. */
    Optional<RuntimeException> end() {
        RuntimeException failure = null;
        for (TuprelStream<?> stream : List.copyOf(streams)) {
            try {
                stream.close();
            } catch (RuntimeException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        streams.clear();
        active = false;
        return Optional.ofNullable(failure);
    }
}
