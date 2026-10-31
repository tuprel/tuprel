package dev.tuprel.migrate.postgresql;

import dev.tuprel.migrate.MigrationChecksum;

/**
 * Proposed context-bound digest. A digest is not human approval or an execution capability.
 * @param value canonical SHA-256 hexadecimal digest
 */
public record MigrationPlanDigest(String value) {
    /** Reuses the canonical SHA-256 validation contract. */
    public MigrationPlanDigest { new MigrationChecksum(value); }
}
