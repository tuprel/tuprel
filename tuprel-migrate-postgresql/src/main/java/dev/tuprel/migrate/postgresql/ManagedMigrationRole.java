package dev.tuprel.migrate.postgresql;

import dev.tuprel.sql.SqlIdentifier;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;

/** Trusted deployment configuration. Login credentials are supplied by the managed executor's issuer. */
record ManagedMigrationRole(SqlIdentifier owner, SqlIdentifier ledgerOwner, Optional<ManagedCredentialIssuer.Seal> seal) {
    ManagedMigrationRole(SqlIdentifier owner, SqlIdentifier ledgerOwner) {
        this(owner, ledgerOwner, Optional.empty());
    }

    ManagedMigrationRole {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(ledgerOwner, "ledgerOwner");
        Objects.requireNonNull(seal, "seal");
        if (owner.equals(ledgerOwner)) { throw new IllegalArgumentException("The executor must not own its provenance ledger"); }
    }

    boolean enter(Connection connection, SqlIdentifier schema) throws SQLException {
        if (!checked(connection, schema, false)) { return false; }
        if (seal.isPresent()) {
            try (PreparedStatement query = MigrationCatalog.query(connection, """
                    SELECT pg_catalog.set_config('transaction_timeout',
                        LEAST(20000, GREATEST(1, pg_catalog.ceil(
                            EXTRACT(EPOCH FROM (?::pg_catalog.timestamptz - pg_catalog.clock_timestamp())) * 1000
                        )))::pg_catalog.text || 'ms', true)
                    """)) {
                query.setObject(1, seal.orElseThrow().deadline().atOffset(java.time.ZoneOffset.UTC));
                try (ResultSet row = query.executeQuery()) {
                    if (!row.next()) { return false; }
                }
            }
        }
        // Only a validated identifier can be used in this transaction-scoped utility command.
        MigrationCatalog.execute(connection, "SET LOCAL ROLE \"" + owner.name() + "\"");
        return true;
    }

    boolean stillTrusted(Connection connection, SqlIdentifier schema) throws SQLException {
        return checked(connection, schema, true);
    }

    private boolean checked(Connection connection, SqlIdentifier schema, boolean assumed) throws SQLException {
        if (connection.getAutoCommit() || (seal.isPresent() && !seal.orElseThrow().matches(connection))) { return false; }
        try (PreparedStatement query = MigrationCatalog.query(connection, """
                SELECT r.rolname COLLATE pg_catalog."C" = ? AND NOT r.rolcanlogin
                  AND NOT r.rolsuper AND NOT r.rolcreaterole AND NOT r.rolcreatedb
                  AND NOT r.rolreplication AND NOT r.rolbypassrls
                  AND u.rolcanlogin = ? AND NOT u.rolsuper AND NOT u.rolcreaterole AND NOT u.rolcreatedb
                  AND NOT u.rolreplication AND NOT u.rolbypassrls
                  AND (?::pg_catalog.timestamptz IS NULL OR (
                    pg_catalog.clock_timestamp() < ?::pg_catalog.timestamptz
                    AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_stat_activity s
                        WHERE s.usesysid = u.oid AND s.pid <> pg_catalog.pg_backend_pid())))
                  AND CURRENT_USER = CASE WHEN ? THEN r.rolname ELSE SESSION_USER END
                  AND EXISTS (SELECT 1 FROM pg_catalog.pg_auth_members m WHERE m.roleid = r.oid AND m.member = u.oid
                              AND m.set_option AND NOT m.inherit_option AND NOT m.admin_option)
                  AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_auth_members m
                                  WHERE (m.roleid = r.oid AND m.member <> u.oid)
                                     OR (m.member = u.oid AND m.roleid <> r.oid) OR m.member = r.oid
                                     OR (m.roleid = r.oid AND m.member = u.oid
                                         AND (NOT m.set_option OR m.inherit_option OR m.admin_option)))
                  AND NOT EXISTS (
                    SELECT 1 FROM pg_catalog.pg_class c WHERE c.relnamespace = n.oid AND c.relkind IN ('r', 'i')
                      AND (c.relowner <> r.oid OR EXISTS (SELECT 1 FROM pg_catalog.aclexplode(c.relacl) acl
                        WHERE acl.grantee <> r.oid
                          AND (acl.privilege_type = 'TRIGGER' OR
                            (c.relname = '_tuprel_migrations' AND acl.privilege_type <> 'SELECT')))))
                  AND NOT EXISTS (
                    SELECT 1 FROM pg_catalog.pg_class c JOIN pg_catalog.pg_attribute a ON a.attrelid = c.oid
                    CROSS JOIN LATERAL pg_catalog.aclexplode(a.attacl) acl
                    WHERE c.relnamespace = n.oid AND c.relname = '_tuprel_migrations'
                      AND a.attnum > 0 AND acl.grantee <> r.oid AND acl.privilege_type <> 'SELECT')
                FROM pg_catalog.pg_namespace n
                JOIN pg_catalog.pg_roles r ON r.oid = n.nspowner
                JOIN pg_catalog.pg_roles u ON u.rolname = SESSION_USER
                WHERE n.nspname COLLATE pg_catalog."C" = ?
                """)) {
            query.setString(1, owner.name());
            query.setBoolean(2, seal.isEmpty());
            java.time.OffsetDateTime deadline = seal
                    .map(value -> value.deadline().atOffset(java.time.ZoneOffset.UTC)).orElse(null);
            query.setObject(3, deadline, java.sql.Types.TIMESTAMP_WITH_TIMEZONE);
            query.setObject(4, deadline, java.sql.Types.TIMESTAMP_WITH_TIMEZONE);
            query.setBoolean(5, assumed);
            query.setString(6, schema.name());
            try (ResultSet rows = query.executeQuery()) {
                return rows.next() && rows.getBoolean(1) && !rows.next();
            }
        }
    }
}
