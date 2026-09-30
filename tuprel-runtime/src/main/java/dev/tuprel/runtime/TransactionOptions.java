package dev.tuprel.runtime;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Options of an outermost transaction.
 *
 * <p>Without an isolation level the connection's current level is used. The timeout is a
 * deadline for the whole transaction: every statement receives the remaining time as its
 * statement timeout, and a statement that would start after the deadline fails without running.
 *
 * @param isolation isolation level to set for the transaction
 * @param readOnly whether the transaction is read-only
 * @param timeout deadline measured from the start of the transaction
 */
public record TransactionOptions(Optional<IsolationLevel> isolation, boolean readOnly, Optional<Duration> timeout) {
    private static final TransactionOptions DEFAULTS =
            new TransactionOptions(Optional.empty(), false, Optional.empty());

    public TransactionOptions {
        Objects.requireNonNull(isolation, "isolation");
        Objects.requireNonNull(timeout, "timeout");
        timeout.ifPresent(StatementOptions::requirePositive);
    }

    /** Connection defaults, read-write, no deadline. */
    public static TransactionOptions defaults() {
        return DEFAULTS;
    }

    /** Returns a copy with the isolation level. */
    public TransactionOptions withIsolation(IsolationLevel value) {
        return new TransactionOptions(Optional.of(Objects.requireNonNull(value, "value")), readOnly, timeout);
    }

    /** Returns a copy that is read-only or read-write. */
    public TransactionOptions withReadOnly(boolean value) {
        return new TransactionOptions(isolation, value, timeout);
    }

    /** Returns a copy with a deadline for the whole transaction. */
    public TransactionOptions withTimeout(Duration value) {
        return new TransactionOptions(isolation, readOnly, Optional.of(Objects.requireNonNull(value, "value")));
    }
}
