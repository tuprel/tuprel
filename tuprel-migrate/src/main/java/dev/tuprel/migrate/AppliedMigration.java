package dev.tuprel.migrate;

import java.time.Instant;
import java.util.Objects;

/**
 * Confirmed history row; does not authorize SQL execution.
 * @param id canonical identity
 * @param name canonical name
 * @param checksum exact applied bytes digest
 * @param startedAt recorded start
 * @param finishedAt recorded finish
 * @param executionMillis nonnegative monotonic duration
 * @param engineVersion identifiable engine version
 */
public record AppliedMigration(MigrationId id, String name, MigrationChecksum checksum,
        Instant startedAt, Instant finishedAt, long executionMillis, String engineVersion) {
    /** Validates all stored values independently of database constraints. */
    public AppliedMigration {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(checksum, "checksum");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(finishedAt, "finishedAt");
        Objects.requireNonNull(engineVersion, "engineVersion");
        MigrationFile.validateName(name);
        if (finishedAt.isBefore(startedAt) || executionMillis < 0
                || !engineVersion.matches("[A-Za-z0-9][A-Za-z0-9._+\\-]{0,63}")) {
            throw new IllegalArgumentException("Invalid migration history values");
        }
    }
}
