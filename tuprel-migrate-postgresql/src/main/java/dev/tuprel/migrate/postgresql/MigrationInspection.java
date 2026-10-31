package dev.tuprel.migrate.postgresql;

import dev.tuprel.migrate.MigrationHistory;
import java.util.Objects;

/**
 * Fresh history/plan observed under a session lock, without bootstrap or migration SQL.
 * Does not verify full physical drift or grant execution authority.
 * @param plan current plan and digest, requiring explicit review
 * @param history confirmed read-only history snapshot from the same owned connection
 */
public record MigrationInspection(MigrationPlan plan, MigrationHistory history) {
    /** Requires both immutable snapshots. */
    public MigrationInspection { Objects.requireNonNull(plan, "plan"); Objects.requireNonNull(history, "history"); }
    /** Excludes history details and SQL from incidental logging. */
    @Override public String toString() { return "MigrationInspection[state=" + plan.state() + ", applied=" + history.applied().size() + "]"; }
}
