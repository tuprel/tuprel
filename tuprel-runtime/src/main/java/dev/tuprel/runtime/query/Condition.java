package dev.tuprel.runtime.query;

import dev.tuprel.sql.SqlCondition;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Immutable predicate over rows of model {@code M}, built from that model's generated fields.
 *
 * @param <M> generated model type the predicate applies to
 */
public final class Condition<M> {
    private final SqlCondition sql;

    Condition(SqlCondition sql) {
        this.sql = Objects.requireNonNull(sql, "sql");
    }

    /** Matches rows satisfying every condition; at least one is required. */
    @SafeVarargs
    public static <M> Condition<M> allOf(Condition<M>... conditions) {
        Objects.requireNonNull(conditions, "conditions");
        List<SqlCondition> terms = new ArrayList<>(conditions.length);
        for (Condition<M> condition : conditions) {
            terms.add(Objects.requireNonNull(condition, "condition").sql);
        }
        return new Condition<>(new SqlCondition.And(terms));
    }

    /** Matches rows satisfying at least one condition; at least one is required. */
    @SafeVarargs
    public static <M> Condition<M> anyOf(Condition<M>... conditions) {
        Objects.requireNonNull(conditions, "conditions");
        List<SqlCondition> terms = new ArrayList<>(conditions.length);
        for (Condition<M> condition : conditions) {
            terms.add(Objects.requireNonNull(condition, "condition").sql);
        }
        return new Condition<>(new SqlCondition.Or(terms));
    }

    /** Matches rows satisfying both this and the other condition. */
    public Condition<M> and(Condition<M> other) {
        return new Condition<>(new SqlCondition.And(List.of(sql, Objects.requireNonNull(other, "other").sql)));
    }

    /** Matches rows satisfying this or the other condition. */
    public Condition<M> or(Condition<M> other) {
        return new Condition<>(new SqlCondition.Or(List.of(sql, Objects.requireNonNull(other, "other").sql)));
    }

    /**
     * Matches rows not satisfying this condition. Under SQL three-valued logic, rows for which
     * this condition is unknown because of {@code NULL} match neither it nor its negation.
     */
    public Condition<M> not() {
        return new Condition<>(new SqlCondition.Not(sql));
    }

    /** Structural form, for inspection and for the low-level runtime API. */
    public SqlCondition toSql() {
        return sql;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Condition<?> condition && sql.equals(condition.sql);
    }

    @Override
    public int hashCode() {
        return sql.hashCode();
    }

    @Override
    public String toString() {
        return "Condition[redacted]";
    }
}
