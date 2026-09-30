package dev.tuprel.runtime;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Execution options of one statement.
 *
 * <p>The timeout is applied with JDBC statement timeouts, which have a granularity of whole
 * seconds: a non-zero duration is rounded up to the next second. Inside a transaction with a
 * deadline, the shorter of the two limits applies. The fetch size is a hint for how many rows
 * the driver reads per round trip; zero leaves the driver default.
 *
 * @param timeout maximum execution time of the statement
 * @param fetchSize rows fetched per round trip, or zero for the driver default
 */
public record StatementOptions(Optional<Duration> timeout, int fetchSize) {
    private static final StatementOptions DEFAULTS = new StatementOptions(Optional.empty(), 0);

    public StatementOptions {
        Objects.requireNonNull(timeout, "timeout");
        timeout.ifPresent(StatementOptions::requirePositive);
        if (fetchSize < 0) {
            throw new IllegalArgumentException("Fetch size cannot be negative");
        }
    }

    /** No timeout, driver fetch size. */
    public static StatementOptions defaults() {
        return DEFAULTS;
    }

    /** Returns a copy with a statement timeout. */
    public StatementOptions withTimeout(Duration value) {
        return new StatementOptions(Optional.of(Objects.requireNonNull(value, "value")), fetchSize);
    }

    /** Returns a copy with a fetch size. */
    public StatementOptions withFetchSize(int value) {
        return new StatementOptions(timeout, value);
    }

    static void requirePositive(Duration value) {
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException("Timeout must be positive");
        }
    }
}
