package dev.tuprel.migrate.postgresql.diff;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuprel.migrate.MigrationFile;
import dev.tuprel.migrate.MigrationId;
import dev.tuprel.migrate.postgresql.PostgresqlMigrationHistory;
import dev.tuprel.migrate.postgresql.ddl.DdlParseResult;
import dev.tuprel.migrate.postgresql.ddl.PostgresqlDdlParser;
import dev.tuprel.sql.SqlIdentifier;
import dev.tuprel.testsupport.TestPostgresql;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Explicitly applies preview only in test-owned schemas; the diff API itself has no I/O. */
class SchemaDiffIntegrationTest {
    @Test void generatedAdditiveProposalsHaveTheExpectedPostgresqlTypesNullabilityAndIndexOrder() throws Exception {
        try (TestPostgresql database = TestPostgresql.open(); Connection connection = database.dataSource().getConnection()) {
            String schema = database.dataSource().getCurrentSchema();
            String create = "CREATE TABLE \"" + schema + "\".\"Thing\" (\"id\" UUID PRIMARY KEY, \"flag\" BOOLEAN,"
                    + " \"small\" SMALLINT, \"number\" INTEGER, \"large\" BIGINT, \"text\" TEXT, \"day\" DATE, \"time\" TIMESTAMPTZ);\n";
            String additions = "ALTER TABLE \"" + schema + "\".\"Thing\" ADD COLUMN \"extra\" TEXT;\n"
                    + "CREATE INDEX \"by_extra\" ON \"" + schema + "\".\"Thing\" (\"extra\", \"id\");\n";
            SchemaSnapshot initial = snapshot(schema, create);
            SchemaDiff first = SchemaDiff.compare(SchemaSnapshot.replay(new SqlIdentifier(schema), List.of()), initial);
            SchemaDiff second = SchemaDiff.compare(initial, snapshot(schema, create + additions));
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.setQueryTimeout(10);
                statement.execute(first.preview());
                statement.execute(second.preview());
            }
            Map<String, String> columns = new LinkedHashMap<>();
            try (PreparedStatement query = connection.prepareStatement("""
                    SELECT a.attname, t.typname, a.attnotnull
                    FROM pg_catalog.pg_attribute a
                    JOIN pg_catalog.pg_class c ON c.oid = a.attrelid
                    JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                    JOIN pg_catalog.pg_type t ON t.oid = a.atttypid
                    WHERE n.nspname = ? AND c.relname = ? AND a.attnum > 0 AND NOT a.attisdropped
                    ORDER BY a.attname
                    """)) {
                query.setString(1, schema); query.setString(2, "Thing");
                try (ResultSet rows = query.executeQuery()) {
                    while (rows.next()) {
                        columns.put(rows.getString(1), rows.getString(2));
                        assertEquals(rows.getString(1).equals("id"), rows.getBoolean(3));
                    }
                }
            }
            assertEquals(Map.of("id", "uuid", "flag", "bool", "small", "int2", "number", "int4", "large", "int8",
                    "text", "text", "day", "date", "time", "timestamptz", "extra", "text"), columns);
            try (PreparedStatement query = connection.prepareStatement("""
                    SELECT pg_catalog.pg_get_indexdef(c.oid, 1, false), pg_catalog.pg_get_indexdef(c.oid, 2, false)
                    FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname = ? AND c.relname = ?
                    """)) {
                query.setString(1, schema); query.setString(2, "by_extra");
                try (ResultSet rows = query.executeQuery()) {
                    assertTrue(rows.next()); assertEquals("extra", rows.getString(1)); assertEquals("id", rows.getString(2)); assertFalse(rows.next());
                }
            }
            connection.rollback();
            assertFalse(new PostgresqlMigrationHistory(database.dataSource()).read(schema).initialised());
        }
    }

    @Test void comparisonAndBlockedPreviewDoNotCreateHistoryOrChangeExistingRowsAndCatalog() throws Exception {
        try (TestPostgresql database = TestPostgresql.open(); Connection connection = database.dataSource().getConnection()) {
            String schema = database.dataSource().getCurrentSchema();
            String create = "CREATE TABLE \"" + schema + "\".\"Thing\" (\"id\" INTEGER);\n";
            try (Statement statement = connection.createStatement()) {
                statement.setQueryTimeout(10); statement.execute(parse(create).preview());
            }
            try (PreparedStatement insert = connection.prepareStatement("INSERT INTO \"" + schema + "\".\"Thing\" VALUES (?)")) {
                insert.setInt(1, 73); assertEquals(1, insert.executeUpdate());
            }
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setReadOnly(true); connection.setAutoCommit(false);
            SchemaSnapshot before = snapshot(schema, create);
            SchemaDiff removal = SchemaDiff.compare(before, SchemaSnapshot.replay(new SqlIdentifier(schema), List.of()));
            assertThrows(IllegalStateException.class, removal::preview);
            SchemaDiff addition = SchemaDiff.compare(before, snapshot(schema, create.replace(");", ", \"extra\" TEXT);")));
            assertEquals(SchemaDiff.State.REVIEW_REQUIRED, addition.state());
            try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("SELECT id FROM \"" + schema + "\".\"Thing\"")) {
                assertTrue(rows.next()); assertEquals(73, rows.getInt(1)); assertFalse(rows.next());
            }
            try (PreparedStatement query = connection.prepareStatement("""
                    SELECT count(*) FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname = ?
                    """)) {
                query.setString(1, schema);
                try (ResultSet rows = query.executeQuery()) { assertTrue(rows.next()); assertEquals(1, rows.getInt(1)); }
            }
            connection.rollback();
            assertFalse(new PostgresqlMigrationHistory(database.dataSource()).read(schema).initialised());
        }
    }

    private static SchemaSnapshot snapshot(String schema, String sql) {
        return SchemaSnapshot.fromParsed(new SqlIdentifier(schema), List.of(parse(sql)));
    }

    private static DdlParseResult parse(String sql) {
        return new PostgresqlDdlParser().parse(new MigrationFile(new MigrationId("20261031_120000"), "example", sql.getBytes(StandardCharsets.UTF_8)));
    }
}
