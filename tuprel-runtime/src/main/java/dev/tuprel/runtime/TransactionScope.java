package dev.tuprel.runtime;

import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * State of one transaction or savepoint block: the shared connection, the owning thread, the
 * deadline and the streams opened inside it. It is confined to the owning thread.
 */
final class TransactionScope {
    private final Connection connection;
    private final Thread owner;
    private final Optional<Long> deadlineNanos;
    private final Optional<TransactionScope> parent;
    private final List<TuprelStream<?>> streams = new ArrayList<>();
    private boolean active = true;

    private TransactionScope(Connection connection, Thread owner, Optional<Long> deadlineNanos,
            Optional<TransactionScope> parent) {
        this.connection = connection;
        this.owner = owner;
        this.deadlineNanos = deadlineNanos;
        this.parent = parent;
    }

    static TransactionScope root(Connection connection, Optional<Duration> timeout) {
        return new TransactionScope(connection, Thread.currentThread(),
                timeout.map(value -> System.nanoTime() + value.toNanos()), Optional.empty());
    }

    TransactionScope child() {
        return new TransactionScope(connection, owner, deadlineNanos, Optional.of(this));
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
        return deadlineNanos.map(deadline -> Duration.ofNanos(deadline - System.nanoTime()));
    }

    void register(TuprelStream<?> stream) {
        streams.add(stream);
    }

    void unregister(TuprelStream<?> stream) {
        streams.remove(stream);
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
