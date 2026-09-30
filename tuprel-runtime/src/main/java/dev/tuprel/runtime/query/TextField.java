package dev.tuprel.runtime.query;

import dev.tuprel.runtime.RowReader;
import dev.tuprel.sql.SqlCondition;
import dev.tuprel.sql.SqlValue;
import java.util.function.Function;

/**
 * Typed text column. Pattern predicates are case-sensitive and treat their argument as literal
 * text: {@code %} and {@code _} in the argument are matched literally, never as wildcards.
 * Range predicates are deliberately absent because text ordering depends on database collation.
 *
 * @param <M> generated model type that owns the column
 */
public final class TextField<M> extends Field<M, String> {
    private TextField(String column, boolean nullable, Function<? super M, String> accessor) {
        super(column, SqlValue.Type.TEXT, nullable, SqlValue.Text::new, RowReader::text, accessor);
    }

    /** {@code String} column. */
    public static <M> TextField<M> text(String column, boolean nullable, Function<? super M, String> accessor) {
        return new TextField<>(column, nullable, accessor);
    }

    /** Matches rows whose column contains the literal text. */
    public Condition<M> contains(String value) {
        return compare(SqlCondition.Operator.CONTAINS, value);
    }

    /** Matches rows whose column starts with the literal text. */
    public Condition<M> startsWith(String value) {
        return compare(SqlCondition.Operator.STARTS_WITH, value);
    }

    /** Matches rows whose column ends with the literal text. */
    public Condition<M> endsWith(String value) {
        return compare(SqlCondition.Operator.ENDS_WITH, value);
    }
}
