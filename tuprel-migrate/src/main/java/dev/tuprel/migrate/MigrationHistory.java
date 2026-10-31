package dev.tuprel.migrate;

import java.util.HashSet;
import java.util.List;

/**
 * Immutable, bounded database history snapshot.
 * @param initialised whether the known history table exists
 * @param applied confirmed rows, with distinct IDs
 */
public record MigrationHistory(boolean initialised, List<AppliedMigration> applied) {
    /** Copies and validates the snapshot, without imposing database iteration order. */
    public MigrationHistory {
        applied = List.copyOf(applied);
        if ((!initialised && !applied.isEmpty()) || applied.size() > MigrationDirectory.MAX_MIGRATIONS
                || new HashSet<>(applied.stream().map(AppliedMigration::id).toList()).size() != applied.size()) {
            throw new IllegalArgumentException("Invalid migration history snapshot");
        }
    }
}
