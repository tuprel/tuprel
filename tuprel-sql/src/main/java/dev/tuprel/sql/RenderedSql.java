package dev.tuprel.sql;

import java.util.List;
import java.util.Objects;

/** Dialect output: structure and ordered bound values remain separate. */
public record RenderedSql(String text, List<SqlValue> binds) {
    public RenderedSql {
        Objects.requireNonNull(text, "text");
        if (text.isBlank()) {
            throw new IllegalArgumentException("SQL text is blank");
        }
        binds = List.copyOf(binds);
    }
}
