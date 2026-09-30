package dev.tuprel.sql;

import java.util.List;
import java.util.Objects;

/**
 * Dialect output: structure and ordered bound values remain separate.
 *
 * <p>{@link #toString()} shows the SQL text and only the number of binds, so logging a
 * preview never discloses parameter values; {@link #binds()} returns them explicitly.
 */
public record RenderedSql(String text, List<SqlValue> binds) {
    public RenderedSql {
        Objects.requireNonNull(text, "text");
        if (text.isBlank()) {
            throw new IllegalArgumentException("SQL text is blank");
        }
        binds = List.copyOf(binds);
    }

    @Override
    public String toString() {
        return "RenderedSql[text=" + text + ", binds=" + binds.size() + " redacted]";
    }
}
