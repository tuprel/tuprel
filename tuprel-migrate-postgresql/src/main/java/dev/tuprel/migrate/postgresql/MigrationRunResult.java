package dev.tuprel.migrate.postgresql;

import dev.tuprel.migrate.AppliedMigration;
import java.util.List;

/** Internal confirmed evidence, never inferred from an attempted or lost commit. */
record MigrationRunResult(Outcome outcome, Failure failure, List<AppliedMigration> confirmed) {
    enum Outcome { APPLIED, NO_CHANGES, REFUSED, FAILED, OUTCOME_UNKNOWN }
    enum Failure { NONE, SESSION, FILES, HISTORY, APPROVAL, BLOCKED, CATALOG, PERMISSION, TIMEOUT, STATEMENT, CONNECTION, COMMIT, ROLLBACK, CLEANUP }
    MigrationRunResult { confirmed = List.copyOf(confirmed); }
}
