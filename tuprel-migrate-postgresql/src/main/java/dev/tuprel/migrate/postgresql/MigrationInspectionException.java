package dev.tuprel.migrate.postgresql;

/** Safe inspection failure; no snapshot is returned after failed rollback/cleanup. */
public final class MigrationInspectionException extends Exception {
    /** Java serialization identifier; serialized exceptions are not a migration receipt format. */
    private static final long serialVersionUID = 1L;
    /**
     * Stable diagnostic without original driver details.
     * @serial Safe category only; no credentials or SQL are retained.
     */
    private final MigrationExecutionResult.Failure reason;
    MigrationInspectionException(MigrationExecutionResult.Failure reason) {
        super("TUPREL-MIGRATE-INSPECT-" + reason); this.reason = reason;
    }
    /**
     * Returns a cause-free diagnostic category.
     * @return stable category, without driver text or configuration
     */
    public MigrationExecutionResult.Failure reason() { return reason; }
}
