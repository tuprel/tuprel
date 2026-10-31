package dev.tuprel.migrate.postgresql;

import dev.tuprel.migrate.postgresql.ddl.DdlStatement;
import dev.tuprel.sql.SqlIdentifier;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Conservative live gates, always inside the writing transaction and owned session. */
final class MigrationCatalog {
    private MigrationCatalog() { }

    static void environment(Connection connection) throws SQLException, MigrationRunFailure {
        try (PreparedStatement query = query(connection, """
                SELECT NOT EXISTS (SELECT 1 FROM pg_catalog.pg_event_trigger WHERE evtenabled <> 'D'),
                       pg_catalog.current_setting('transaction_isolation') = 'read committed',
                       pg_catalog.current_setting('transaction_read_only') = 'off',
                       pg_catalog.current_setting('session_replication_role') = 'origin'
                """); ResultSet rows = query.executeQuery()) {
            if (!rows.next() || !rows.getBoolean(1) || !rows.getBoolean(2) || !rows.getBoolean(3) || !rows.getBoolean(4)) {
                throw refused();
            }
        }
    }

    static void lockHistory(Connection connection, SqlIdentifier schema) throws SQLException, MigrationRunFailure {
        DdlStatement.TableName history = new DdlStatement.TableName(schema, new SqlIdentifier(MigrationHistoryTable.NAME));
        lock(connection, history, "ROW EXCLUSIVE");
        // Only the canonical PK may execute during INSERT. Unknown expression indexes are not trusted.
        try (PreparedStatement query = query(connection, """
                SELECT count(*) = 1 AND bool_and(i.indisprimary AND i.indisvalid AND i.indisready AND i.indislive
                  AND i.indisunique AND i.indimmediate AND NOT i.indisexclusion
                  AND i.indexprs IS NULL AND i.indpred IS NULL AND i.indnatts = 1
                  AND i.indnkeyatts = 1 AND i.indkey[0] = a.attnum AND i.indoption[0] = 0
                  AND i.indcollation[0] = a.attcollation AND am.amname = 'btree'
                  AND oc.opcdefault AND oc.opcnamespace = 'pg_catalog'::regnamespace
                  AND oc.opcname = 'text_ops' AND oc.opcintype = 'pg_catalog.text'::regtype
                  AND opf.opfnamespace = 'pg_catalog'::regnamespace)
                FROM pg_catalog.pg_index i JOIN pg_catalog.pg_class c ON c.oid = i.indexrelid
                JOIN pg_catalog.pg_am am ON am.oid = c.relam
                JOIN pg_catalog.pg_attribute a ON a.attrelid = i.indrelid AND a.attname = 'migration_id'
                JOIN pg_catalog.pg_opclass oc ON oc.oid = i.indclass[0]
                JOIN pg_catalog.pg_opfamily opf ON opf.oid = oc.opcfamily
                WHERE i.indrelid = ?::oid
                """)) {
            query.setLong(1, relation(connection, history));
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next() || !rows.getBoolean(1)) { throw refused(); }
            }
        }
    }

    static void lockTargets(Connection connection, List<DdlStatement> statements) throws SQLException, MigrationRunFailure {
        Set<DdlStatement.TableName> created = new HashSet<>();
        Map<String, DdlStatement.TableName> names = new TreeMap<>();
        Set<DdlStatement.TableName> exclusive = new HashSet<>();
        for (DdlStatement statement : statements) {
            if (statement instanceof DdlStatement.CreateTable) {
                absent(connection, statement.table()); created.add(statement.table());
            } else if (!created.contains(statement.table())) {
                names.put(table(statement.table()), statement.table());
                if (statement instanceof DdlStatement.AddColumn) { exclusive.add(statement.table()); }
            }
            if (statement instanceof DdlStatement.CreateIndex index) {
                absent(connection, new DdlStatement.TableName(index.table().schema(), index.name()));
            }
        }
        for (DdlStatement.TableName table : names.values()) {
            lock(connection, table, exclusive.contains(table) ? "ACCESS EXCLUSIVE" : "SHARE");
        }
    }

    static void validate(Connection connection, DdlStatement statement) throws SQLException, MigrationRunFailure {
        if (statement instanceof DdlStatement.CreateTable) { absent(connection, statement.table()); return; }
        long oid = relation(connection, statement.table());
        if (oid == 0) { throw refused(); }
        if (statement instanceof DdlStatement.CreateIndex index) {
            absent(connection, new DdlStatement.TableName(index.table().schema(), index.name()));
            for (SqlIdentifier column : index.columns()) { indexColumn(connection, oid, column); }
        }
    }

    private static void indexColumn(Connection connection, long relation, SqlIdentifier column)
            throws SQLException, MigrationRunFailure {
        try (PreparedStatement query = query(connection, """
                SELECT t.typnamespace = 'pg_catalog'::regnamespace
                  AND t.typname IN ('uuid','bool','int2','int4','int8','text','date','timestamptz')
                  AND t.typtype = 'b' AND NOT a.attisdropped AND a.attgenerated = ''
                  AND a.attcollation = t.typcollation
                  AND (a.attcollation = 0 OR co.collnamespace = 'pg_catalog'::regnamespace),
                  oc.opcnamespace = 'pg_catalog'::regnamespace AND opf.opfnamespace = 'pg_catalog'::regnamespace
                FROM pg_catalog.pg_attribute a JOIN pg_catalog.pg_type t ON t.oid = a.atttypid
                LEFT JOIN pg_catalog.pg_collation co ON co.oid = a.attcollation
                JOIN pg_catalog.pg_opclass oc ON oc.opcintype = t.oid AND oc.opcdefault
                JOIN pg_catalog.pg_am am ON am.oid = oc.opcmethod AND am.amname = 'btree'
                JOIN pg_catalog.pg_opfamily opf ON opf.oid = oc.opcfamily
                WHERE a.attrelid = ?::oid AND a.attname = ? AND a.attnum > 0
                """)) {
            query.setLong(1, relation); query.setString(2, column.name());
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next() || !rows.getBoolean(1) || !rows.getBoolean(2) || rows.next()) { throw refused(); }
            }
        }
    }

    static void absent(Connection connection, DdlStatement.TableName table) throws SQLException, MigrationRunFailure {
        if (relation(connection, table) != 0) { throw refused(); }
    }

    private static void lock(Connection connection, DdlStatement.TableName table, String mode)
            throws SQLException, MigrationRunFailure {
        long before = relation(connection, table);
        if (before == 0) { throw refused(); }
        execute(connection, "LOCK TABLE ONLY " + table(table) + " IN " + mode + " MODE");
        if (relation(connection, table) != before) { throw refused(); }
    }

    private static long relation(Connection connection, DdlStatement.TableName table) throws SQLException, MigrationRunFailure {
        try (PreparedStatement query = query(connection, """
                SELECT c.oid, c.relkind = 'r' AND c.relpersistence = 'p' AND am.amname = 'heap'
                  AND NOT c.relrowsecurity AND NOT c.relforcerowsecurity AND NOT c.relispartition
                  AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_inherits WHERE inhrelid = c.oid OR inhparent = c.oid)
                  AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_rewrite WHERE ev_class = c.oid)
                  AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_trigger WHERE tgrelid = c.oid AND NOT tgisinternal)
                FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                LEFT JOIN pg_catalog.pg_am am ON am.oid = c.relam WHERE n.nspname = ? AND c.relname = ?
                """)) {
            query.setString(1, table.schema().name()); query.setString(2, table.name().name());
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) { return 0; }
                if (!rows.getBoolean(2)) { throw refused(); }
                return rows.getLong(1);
            }
        }
    }

    static String table(DdlStatement.TableName table) {
        return "\"" + table.schema().name() + "\".\"" + table.name().name() + "\"";
    }

    static PreparedStatement query(Connection connection, String sql) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        try { statement.setQueryTimeout(20); return statement; }
        catch (SQLException exception) { statement.close(); throw exception; }
    }

    /** Only fixed library statements or an approved AST's canonical rendering reach this method. */
    static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(20); statement.execute(sql);
        }
    }

    private static MigrationRunFailure refused() { return new MigrationRunFailure(MigrationRunResult.Failure.CATALOG, false); }
}
