package dev.tuprel.sql;

/** Renders a structural operation into SQL text and ordered binds. */
@FunctionalInterface
public interface SqlRenderer {
    RenderedSql render(SqlCommand command);
}
