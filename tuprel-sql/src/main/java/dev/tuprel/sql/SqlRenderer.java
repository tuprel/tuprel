package dev.tuprel.sql;

/** Renders a structural operation into SQL text and ordered binds. */
@FunctionalInterface
public interface SqlRenderer {
    RenderedSql render(SqlCommand command);

    /**
     * Renders a structural query. Renderers that predate the query model keep compiling and
     * fail explicitly instead of producing partial SQL.
     */
    default RenderedSql render(SqlQuery query) {
        throw new UnsupportedOperationException("Query rendering is not supported by this renderer");
    }
}
