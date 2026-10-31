package dev.tuprel.migrate.postgresql;

import dev.tuprel.migrate.MigrationChecksum;
import dev.tuprel.sql.SqlIdentifier;

/**
 * Credential-redacted configured target binding. Does not attest a live server,
 * permissions or snapshot provenance. Obtain through MigrationDatabaseSettings;
 * a future runner must independently rebuild/verify it under its session lock.
 */
public final class MigrationPlanTarget {
    private final SqlIdentifier schema;
    private final MigrationChecksum fingerprint;
    MigrationPlanTarget(SqlIdentifier schema, MigrationChecksum fingerprint) { this.schema = schema; this.fingerprint = fingerprint; }
    /**
     * Explicit target schema.
     * @return validated identifier
     */
    public SqlIdentifier schema() { return schema; }
    /**
     * Domain-separated digest of configured endpoint, schema, principal and TLS mode.
     * Passwords are excluded; no raw configuration is retained here.
     * @return target fingerprint
     */
    public MigrationChecksum fingerprint() { return fingerprint; }
    boolean supportedSchema() {
        return !schema.name().startsWith("pg_") && !schema.name().equals("information_schema");
    }
    /** Omits endpoint, username and schema. */
    @Override public String toString() { return "MigrationPlanTarget[redacted]"; }
}
