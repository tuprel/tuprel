package dev.tuprel.migrate.postgresql.diff;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuprel.migrate.MigrationFile;
import dev.tuprel.migrate.MigrationId;
import dev.tuprel.migrate.postgresql.ddl.PostgresqlDdlParser;
import dev.tuprel.schema.SchemaCompiler;
import dev.tuprel.schema.SourceText;
import dev.tuprel.sql.SqlIdentifier;
import dev.tuprel.testsupport.TestPostgresql;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SchemaProjectorIntegrationTest {
    @Test void projectedSchemaRoundTripsAndMatchesRealPostgresqlCatalog() throws Exception {
        try (TestPostgresql database = TestPostgresql.open(); Connection connection = database.dataSource().getConnection()) {
            SqlIdentifier schema = new SqlIdentifier(database.dataSource().getCurrentSchema());
            SchemaProjection projected = project(schema, """
                    model Thing {
                      id UUID @id
                      flag Boolean?
                      small Short
                      number Int @version
                      large Long
                      label String?
                      day LocalDate
                      time Instant
                      @@index([label, id])
                    }
                    """);
            SchemaSnapshot snapshot = projected.snapshot().orElseThrow();
            String preview = SchemaDiff.compare(SchemaSnapshot.replay(schema, List.of()), snapshot).preview();
            MigrationFile file = new MigrationFile(new MigrationId("20261031_120000"), "example", preview.getBytes(StandardCharsets.UTF_8));
            assertEquals(SchemaDiff.State.NO_CHANGES, SchemaDiff.compare(snapshot,
                    SchemaSnapshot.fromParsed(schema, List.of(new PostgresqlDdlParser().parse(file)))).state());
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) { statement.setQueryTimeout(10); statement.execute(preview); }
            Map<String, String> types = new HashMap<>();
            try (PreparedStatement query = connection.prepareStatement("""
                    SELECT a.attname, t.typname, a.attnotnull, a.atthasdef
                    FROM pg_catalog.pg_attribute a JOIN pg_catalog.pg_type t ON t.oid = a.atttypid
                    JOIN pg_catalog.pg_class c ON c.oid = a.attrelid
                    JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname = ? AND c.relname = ? AND a.attnum > 0 AND NOT a.attisdropped
                    """)) {
                query.setString(1, schema.name()); query.setString(2, "Thing");
                try (ResultSet rows = query.executeQuery()) {
                    while (rows.next()) {
                        types.put(rows.getString(1), rows.getString(2));
                        assertEquals(!List.of("flag", "label").contains(rows.getString(1)), rows.getBoolean(3));
                        assertFalse(rows.getBoolean(4));
                    }
                }
            }
            assertEquals(Map.of("id", "uuid", "flag", "bool", "small", "int2", "number", "int4", "large", "int8",
                    "label", "text", "day", "date", "time", "timestamptz"), types);
            try (PreparedStatement query = connection.prepareStatement("""
                    SELECT pg_catalog.pg_get_indexdef(c.oid, 1, false), pg_catalog.pg_get_indexdef(c.oid, 2, false)
                    FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname = ? AND c.relname = ?
                    """)) {
                query.setString(1, schema.name()); query.setString(2, "Thing_label_id_idx");
                try (ResultSet rows = query.executeQuery()) { assertTrue(rows.next()); assertEquals("label", rows.getString(1)); assertEquals("id", rows.getString(2)); }
            }
            connection.rollback();
        }
    }

    @Test void refusedProjectionLeavesFixtureCatalogEmpty() throws Exception {
        try (TestPostgresql database = TestPostgresql.open(); Connection connection = database.dataSource().getConnection()) {
            SqlIdentifier schema = new SqlIdentifier(database.dataSource().getCurrentSchema());
            assertTrue(project(schema, "model Good { id UUID @id } model Bad { id UUID @id v String @default(\"private_value\") }").snapshot().isEmpty());
            try (PreparedStatement query = connection.prepareStatement("SELECT count(*) FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = ?")) {
                query.setString(1, schema.name());
                try (ResultSet rows = query.executeQuery()) { assertTrue(rows.next()); assertEquals(0, rows.getInt(1)); }
            }
        }
    }

    private static SchemaProjection project(SqlIdentifier schema, String text) {
        return new PostgresqlSchemaProjector().project(schema, new SchemaCompiler().compile(SourceText.of("schema.tuprel", text)).validatedSchema().orElseThrow());
    }
}
