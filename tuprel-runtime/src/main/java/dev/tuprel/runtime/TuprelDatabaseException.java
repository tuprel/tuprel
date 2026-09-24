package dev.tuprel.runtime;

import java.sql.SQLException;

/** Runtime failure with a stable phase and safe driver diagnostics. */
public final class TuprelDatabaseException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** Phase in which the operation failed. */
    public enum Phase { CONNECTION, PREPARATION, BINDING, EXECUTION, MAPPING, CLOSING }

    private final Phase phase;
    private final String sqlState;
    private final int vendorCode;

    TuprelDatabaseException(Phase phase, Throwable cause) {
        super("Database operation failed during " + phase.name().toLowerCase(java.util.Locale.ROOT), cause);
        this.phase = phase;
        if (cause instanceof SQLException sqlException) {
            this.sqlState = sqlException.getSQLState();
            this.vendorCode = sqlException.getErrorCode();
        } else {
            this.sqlState = null;
            this.vendorCode = 0;
        }
    }

    public Phase phase() { return phase; }
    public String sqlState() { return sqlState; }
    public int vendorCode() { return vendorCode; }
}
