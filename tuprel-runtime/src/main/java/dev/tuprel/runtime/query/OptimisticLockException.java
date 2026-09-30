package dev.tuprel.runtime.query;

/**
 * An update carried an expected {@code @version} that no longer matches the stored row: another
 * transaction changed it since it was read. Nothing was written. The message names the table and
 * the expected version only, never the identifier or other values.
 */
public final class OptimisticLockException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final String table;
    private final long expectedVersion;

    OptimisticLockException(String table, long expectedVersion) {
        super("Row in table \"" + table + "\" was changed concurrently; expected version " + expectedVersion);
        this.table = table;
        this.expectedVersion = expectedVersion;
    }

    /** Table of the conflicting row. */
    public String table() {
        return table;
    }

    /** Version the caller expected. */
    public long expectedVersion() {
        return expectedVersion;
    }
}
