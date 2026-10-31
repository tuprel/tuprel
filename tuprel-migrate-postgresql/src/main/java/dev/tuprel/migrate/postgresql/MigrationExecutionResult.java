package dev.tuprel.migrate.postgresql;

import dev.tuprel.migrate.AppliedMigration;
import dev.tuprel.migrate.MigrationId;
import java.util.List;
import java.util.Objects;

/**
 * Confirmed evidence for one invocation, never inferred from an attempted/lost commit.
 * Earlier confirmed migrations survive any later failure; this is not a global rollback result.
 * @param outcome whole invocation outcome
 * @param failure safe category without driver text or configuration
 * @param confirmed newly committed migrations from this invocation, in ID order
 */
public record MigrationExecutionResult(Outcome outcome, Failure failure, List<AppliedMigration> confirmed) {
    /** Whole invocation state, independent of its confirmed prefix. */
    public enum Outcome {
        /** All pending migrations committed. */ APPLIED,
        /** No pending migrations, with matching approval; no DDL. */ NO_CHANGES,
        /** A safety/configuration gate refused further work. */ REFUSED,
        /** A known failure, including cleanup after confirmed commits. */ FAILED,
        /** Commit, connection or rollback could not be established; stop and inspect anew. */ OUTCOME_UNKNOWN
    }
    /** Stable, cause-free categories. None contains SQL, endpoints or credentials. */
    public enum Failure {
        /** Successful invocation. */ NONE,
        /** Invalid or mismatched live target. */ CONFIGURATION,
        /** A connection is not a dedicated idle session. */ SESSION,
        /** Session lock acquisition deadline elapsed. */ LOCK_BUSY,
        /** Invocation interrupted; no automatic continuation. */ INTERRUPTED,
        /** Files could not be safely discovered/validated. */ FILES,
        /** History structure, rows or expected prefix differ. */ HISTORY,
        /** Approved digest differs from the newly observed plan. */ APPROVAL,
        /** Unsupported, destructive or otherwise blocked plan. */ BLOCKED,
        /** Physical metadata lies outside the admitted subset. */ CATALOG,
        /** Insufficient PostgreSQL privileges. */ PERMISSION,
        /** A bounded query or relation lock timed out. */ TIMEOUT,
        /** Statement failed and rollback was confirmed. */ STATEMENT,
        /** Connection/protocol could not establish an outcome. */ CONNECTION,
        /** Commit acknowledgement could not establish an outcome. */ COMMIT,
        /** Rollback could not be confirmed. */ ROLLBACK,
        /** Lock release/connection cleanup failed; confirmed prefix remains valid. */ CLEANUP
    }
    /** Enforces immutable, ordered evidence and consistent success/failure labels. */
    public MigrationExecutionResult {
        Objects.requireNonNull(outcome, "outcome"); Objects.requireNonNull(failure, "failure");
        confirmed = List.copyOf(confirmed);
        boolean success = outcome == Outcome.APPLIED || outcome == Outcome.NO_CHANGES;
        if (success != (failure == Failure.NONE) || (outcome == Outcome.APPLIED && confirmed.isEmpty())
                || (outcome == Outcome.NO_CHANGES && !confirmed.isEmpty())) {
            throw new IllegalArgumentException("Inconsistent migration execution result");
        }
        MigrationId previous = null;
        for (AppliedMigration migration : confirmed) {
            if (previous != null && previous.compareTo(migration.id()) >= 0) {
                throw new IllegalArgumentException("Confirmed migrations must be strictly ordered");
            }
            previous = migration.id();
        }
    }
    /** Omits migration names, SQL and incidental metadata from logging. */
    @Override public String toString() {
        return "MigrationExecutionResult[outcome=" + outcome + ", failure=" + failure + ", confirmed=" + confirmed.size() + "]";
    }
}
