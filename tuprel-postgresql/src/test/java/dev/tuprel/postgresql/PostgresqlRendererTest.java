package dev.tuprel.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.tuprel.sql.RenderedSql;
import dev.tuprel.sql.SqlCommand;
import dev.tuprel.sql.SqlIdentifier;
import dev.tuprel.sql.SqlValue;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PostgresqlRendererTest {
    private final PostgresqlRenderer renderer = new PostgresqlRenderer();
    private final SqlIdentifier table = new SqlIdentifier("Order");
    private final SqlIdentifier idColumn = new SqlIdentifier("id");
    private final SqlValue.Uuid id = new SqlValue.Uuid(UUID.fromString("e1642970-ad35-45d0-9be0-69d6cd20b607"));

    @Test
    void rendersCrudWithOrderedBindsAndQuotedIdentifiers() {
        String hostile = "Robert'); DROP TABLE users;--";
        SqlCommand.Assignment name = new SqlCommand.Assignment(new SqlIdentifier("name"),
                new SqlValue.Text(hostile));
        SqlCommand.Assignment age = new SqlCommand.Assignment(new SqlIdentifier("age"),
                new SqlValue.Int32(42));
        RenderedSql insert = renderer.render(new SqlCommand.Insert(table, List.of(name, age)));
        assertEquals("INSERT INTO \"Order\" (\"name\", \"age\") VALUES (?, ?)", insert.text());
        assertEquals(List.of(name.value(), age.value()), insert.binds());
        assertFalse(insert.text().contains(hostile));

        RenderedSql find = renderer.render(new SqlCommand.FindById(table, idColumn, id));
        assertEquals("SELECT * FROM \"Order\" WHERE \"id\" = ?", find.text());
        assertEquals(List.of(id), find.binds());

        RenderedSql update = renderer.render(new SqlCommand.UpdateById(table, idColumn, id, List.of(name)));
        assertEquals("UPDATE \"Order\" SET \"name\" = ? WHERE \"id\" = ?", update.text());
        assertEquals(List.of(name.value(), id), update.binds());

        RenderedSql delete = renderer.render(new SqlCommand.DeleteById(table, idColumn, id));
        assertEquals("DELETE FROM \"Order\" WHERE \"id\" = ?", delete.text());
        assertEquals(List.of(id), delete.binds());
    }
}
