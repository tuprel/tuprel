package dev.tuprel.postgresql;

import dev.tuprel.sql.RenderedSql;
import dev.tuprel.sql.SqlCommand;
import dev.tuprel.sql.SqlIdentifier;
import dev.tuprel.sql.SqlRenderer;
import dev.tuprel.sql.SqlValue;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/** PostgreSQL renderer for the Phase 3 structural CRUD subset. */
public final class PostgresqlRenderer implements SqlRenderer {
    private static String quote(SqlIdentifier identifier) {
        return "\"" + identifier.name() + "\"";
    }

    @Override
    public RenderedSql render(SqlCommand command) {
        return switch (command) {
            case SqlCommand.Insert insert -> insert(insert);
            case SqlCommand.FindById find -> new RenderedSql(
                    "SELECT * FROM " + quote(find.table()) + " WHERE " + quote(find.idColumn()) + " = ?",
                    List.of(find.id()));
            case SqlCommand.UpdateById update -> update(update);
            case SqlCommand.DeleteById delete -> new RenderedSql(
                    "DELETE FROM " + quote(delete.table()) + " WHERE " + quote(delete.idColumn()) + " = ?",
                    List.of(delete.id()));
        };
    }

    private static RenderedSql insert(SqlCommand.Insert command) {
        StringJoiner columns = new StringJoiner(", ");
        StringJoiner placeholders = new StringJoiner(", ");
        List<SqlValue> binds = new ArrayList<>();
        for (SqlCommand.Assignment assignment : command.values()) {
            columns.add(quote(assignment.column()));
            placeholders.add("?");
            binds.add(assignment.value());
        }
        return new RenderedSql("INSERT INTO " + quote(command.table()) + " (" + columns
                + ") VALUES (" + placeholders + ")", binds);
    }

    private static RenderedSql update(SqlCommand.UpdateById command) {
        StringJoiner assignments = new StringJoiner(", ");
        List<SqlValue> binds = new ArrayList<>();
        for (SqlCommand.Assignment assignment : command.values()) {
            assignments.add(quote(assignment.column()) + " = ?");
            binds.add(assignment.value());
        }
        binds.add(command.id());
        return new RenderedSql("UPDATE " + quote(command.table()) + " SET " + assignments
                + " WHERE " + quote(command.idColumn()) + " = ?", binds);
    }
}
