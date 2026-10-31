package dev.tuprel.migrate.postgresql;

/** Internal, cause-free failure used across the owned write path. */
final class MigrationRunFailure extends Exception {
    private static final long serialVersionUID = 1L;
    private final MigrationExecutionResult.Failure failure;
    private final boolean uncertain;
    MigrationRunFailure(MigrationExecutionResult.Failure failure, boolean uncertain) {
        super("TUPREL-MIGRATE-" + failure); this.failure = failure; this.uncertain = uncertain;
    }
    MigrationExecutionResult.Failure failure() { return failure; }
    boolean uncertain() { return uncertain; }
}
