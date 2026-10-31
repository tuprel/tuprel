package dev.tuprel.migrate;

/** Safe history diagnostic, independent of driver messages or credentials. */
public final class MigrationHistoryException extends Exception {
    private static final long serialVersionUID = 1L;
    /** Stable history failure categories. */
    public enum Reason {
        /** Explicit target/configuration is invalid. */ CONFIGURATION,
        /** Explicit target schema does not exist. */ MISSING_SCHEMA,
        /** USAGE or SELECT was refused. */ PERMISSION,
        /** Table, constraints, rows or version are unknown. */ INCOMPATIBLE,
        /** The dedicated connection or transaction contract was not met. */ TRANSACTION,
        /** Query or connection timeout. */ TIMEOUT,
        /** JDBC operation/connection/cleanup failed; no result is confirmed. */ JDBC
    }
    /**
     * Safe failure category.
     * @serial Stable category, never a driver message.
     */
    private final Reason reason;

    /**
     * Creates a diagnostic without attaching sensitive underlying exceptions.
     * @param reason safe failure category
     */
    public MigrationHistoryException(Reason reason) {
        super("TUPREL-MIGRATE-HISTORY-" + reason + ": " + switch (reason) {
            case CONFIGURATION -> "Set TUPREL_DATABASE_URL, TUPREL_DATABASE_USER and TUPREL_DATABASE_PASSWORD; use --target-schema with a valid identifier.";
            case MISSING_SCHEMA -> "The explicit target schema must already exist. Status never creates it.";
            case PERMISSION -> "Check USAGE on the target schema, SELECT on history and standard pg_catalog read access for the status role.";
            case INCOMPATIBLE -> "Inspect history structure, constraints, version and stored values; status does not repair them.";
            case TRANSACTION -> "Provide an idle dedicated connection supporting REPEATABLE READ, READ ONLY. No result is confirmed.";
            case TIMEOUT -> "Check connectivity and concurrent database work. No automatic retry was performed.";
            case JDBC -> "Could not read or close the history snapshot. Check connectivity and permissions; no automatic retry was performed.";
        });
        this.reason = reason;
    }
    /**
     * Returns the safe failure category.
     * @return category
     */
    public Reason reason() { return reason; }
}
