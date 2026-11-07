package dev.tuprel.migrate.postgresql;

import dev.tuprel.migrate.postgresql.ddl.DdlStatement;
import dev.tuprel.sql.SqlIdentifier;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

/** Privileged inventory and receipt table installed by the provisioner outside application schemas. */
record InitialBootstrapLedger(SqlIdentifier schema, long oid) {
    private static final String NAME = "_tuprel_bootstrap";

    InitialBootstrapLedger {
        if (oid <= 0 || schema.name().startsWith("pg_") || schema.name().equals("information_schema")) {
            throw new IllegalArgumentException("Invalid administrative ledger binding");
        }
    }

    static String createSql(SqlIdentifier schema) {
        return "CREATE TABLE " + table(schema) + """
                 (
                    namespace pg_catalog.int8 PRIMARY KEY,
                    audience pg_catalog.uuid NOT NULL,
                    fingerprint pg_catalog.varchar(64) NOT NULL,
                    generation pg_catalog.uuid NOT NULL UNIQUE,
                    nonce pg_catalog.uuid UNIQUE,
                    history_oid pg_catalog.int8,
                    CHECK ((nonce IS NULL) = (history_oid IS NULL))
                )
                """;
    }

    boolean lock(Connection connection, boolean readOnly) throws SQLException {
        return lock(connection, readOnly, Optional.empty());
    }

    boolean lockManagedRead(Connection connection, SqlIdentifier owner) throws SQLException {
        return lock(connection, true, Optional.of(owner));
    }

    private boolean lock(Connection connection, boolean readOnly, Optional<SqlIdentifier> owner) throws SQLException {
        try (PreparedStatement query = MigrationCatalog.query(connection, """
                SELECT c.oid = ?::oid AND c.relkind = 'r' AND c.relpersistence = 'p'
                  AND a.amname = 'heap' AND c.relowner = r.oid AND n.nspowner = r.oid
                  AND (r.rolname = CURRENT_USER OR (
                    NOT pg_catalog.pg_has_role(CURRENT_USER, r.oid, 'MEMBER')
                    AND NOT pg_catalog.pg_has_role(SESSION_USER, r.oid, 'MEMBER')))
                  AND NOT c.relrowsecurity AND NOT c.relforcerowsecurity AND NOT c.relispartition
                  AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_inherits WHERE inhrelid = c.oid OR inhparent = c.oid)
                  AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_trigger WHERE tgrelid = c.oid)
                  AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_rewrite WHERE ev_class = c.oid)
                  AND c.relnatts = 6
                  AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_attribute a
                    WHERE a.attrelid = c.oid AND a.attnum > 0
                      AND (a.attisdropped OR a.atthasdef OR a.attidentity <> '' OR a.attgenerated <> ''
                        OR EXISTS (SELECT 1 FROM pg_catalog.aclexplode(a.attacl) AS acl
                          WHERE acl.grantee <> r.oid AND acl.privilege_type <> 'SELECT')))
                  AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_index i
                    WHERE i.indrelid = c.oid AND (i.indexprs IS NOT NULL OR i.indpred IS NOT NULL))
                  AND NOT EXISTS (SELECT 1 FROM pg_catalog.aclexplode(
                    COALESCE(c.relacl, pg_catalog.acldefault('r', c.relowner))) AS acl
                    WHERE acl.grantee <> r.oid AND acl.privilege_type <> 'SELECT')
                  AND NOT EXISTS (SELECT 1 FROM pg_catalog.aclexplode(
                    COALESCE(n.nspacl, pg_catalog.acldefault('n', n.nspowner))) AS acl
                    WHERE acl.grantee <> r.oid AND acl.privilege_type = 'CREATE')
                FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                JOIN pg_catalog.pg_roles r ON r.rolname = COALESCE(?::pg_catalog.text, CURRENT_USER::pg_catalog.text)
                JOIN pg_catalog.pg_am a ON a.oid = c.relam
                WHERE n.nspname = ? AND c.relname = ?
                """)) {
            query.setLong(1, oid);
            query.setString(2, owner.map(SqlIdentifier::name).orElse(null));
            query.setString(3, schema.name());
            query.setString(4, NAME);
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next() || !rows.getBoolean(1) || rows.next()) { return false; }
            }
        }
        MigrationCatalog.execute(connection, "LOCK TABLE ONLY " + table(schema)
                + (readOnly ? " IN ACCESS SHARE MODE" : " IN SHARE ROW EXCLUSIVE MODE"));
        return relationOid(connection, table(schema)) == oid;
    }

    Receipt read(Connection connection, InitialBootstrapAuthorization.Claims claims) throws SQLException {
        try (PreparedStatement query = MigrationCatalog.query(connection,
                "SELECT nonce, history_oid FROM " + table(schema)
                        + " WHERE namespace = ? AND audience = ? AND fingerprint = ? AND generation = ?")) {
            bind(query, 1, claims);
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) { return new Receipt(false, null, 0); }
                Receipt receipt = new Receipt(true, rows.getObject(1, UUID.class), rows.getLong(2));
                if (rows.next()) { throw new SQLException("Ambiguous provisioning record"); }
                return receipt;
            }
        }
    }

    boolean consume(Connection connection, InitialBootstrapAuthorization.Claims claims, long historyOid) throws SQLException {
        try (PreparedStatement update = MigrationCatalog.query(connection, "UPDATE " + table(schema)
                + " SET nonce = ?, history_oid = ? WHERE namespace = ? AND audience = ? AND fingerprint = ? "
                + "AND generation = ? AND nonce IS NULL AND history_oid IS NULL")) {
            update.setObject(1, claims.nonce());
            update.setLong(2, historyOid);
            bind(update, 3, claims);
            return update.executeUpdate() == 1;
        }
    }

    static long relationOid(Connection connection, String qualified) throws SQLException {
        try (PreparedStatement query = MigrationCatalog.query(connection,
                "SELECT pg_catalog.to_regclass(?)::pg_catalog.oid")) {
            query.setString(1, qualified);
            try (ResultSet rows = query.executeQuery()) { return rows.next() ? rows.getLong(1) : 0; }
        }
    }

    private static void bind(PreparedStatement query, int start, InitialBootstrapAuthorization.Claims claims)
            throws SQLException {
        query.setLong(start, claims.namespace());
        query.setObject(start + 1, claims.audience());
        query.setString(start + 2, claims.fingerprint().value());
        query.setObject(start + 3, claims.generation());
    }

    static String table(SqlIdentifier schema) {
        return MigrationCatalog.table(new DdlStatement.TableName(schema, new SqlIdentifier(NAME)));
    }

    record Receipt(boolean registered, UUID nonce, long historyOid) { }
}
