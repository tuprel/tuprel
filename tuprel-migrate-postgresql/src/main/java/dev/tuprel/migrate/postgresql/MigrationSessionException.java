package dev.tuprel.migrate.postgresql;

/** Safe internal lifecycle diagnostic; never retains driver causes or configuration. */
final class MigrationSessionException extends Exception {
    private static final long serialVersionUID = 1L;
    enum Reason { TARGET, ACTIVE_TRANSACTION, PREHELD_LOCK, LOCK_BUSY, INTERRUPTED, CONNECTION, CLEANUP, OUTCOME_UNKNOWN }
    private final Reason reason;
    MigrationSessionException(Reason reason) {
        super("TUPREL-MIGRATE-SESSION-" + reason);
        this.reason = reason;
    }
    Reason reason() { return reason; }
}
