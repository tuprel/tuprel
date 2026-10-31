package dev.tuprel.migrate.postgresql;

/** Internal, cause-free failure used across the owned write path. */
final class MigrationRunFailure extends Exception {
    private static final long serialVersionUID = 1L;
    private final MigrationRunResult.Failure failure;
    private final boolean uncertain;
    MigrationRunFailure(MigrationRunResult.Failure failure, boolean uncertain) {
        super("TUPREL-MIGRATE-" + failure); this.failure = failure; this.uncertain = uncertain;
    }
    MigrationRunResult.Failure failure() { return failure; }
    boolean uncertain() { return uncertain; }
}
