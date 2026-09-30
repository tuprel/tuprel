package dev.tuprel.sql;

import java.util.List;
import java.util.Objects;

/**
 * Structural predicate of a query. Values remain typed binds and never become SQL text.
 *
 * <p>Comparisons follow SQL three-valued logic: a comparison never matches a {@code NULL}
 * column. {@code NULL} is only tested through {@link NullCheck}, so comparison values cannot be
 * {@link SqlValue.Null}. Text pattern operators carry the raw operand; escaping wildcard
 * characters is the dialect's responsibility.
 */
public sealed interface SqlCondition permits SqlCondition.Comparison, SqlCondition.NullCheck,
        SqlCondition.And, SqlCondition.Or, SqlCondition.Not {

    /** Comparison operators of the dialect-neutral query model. */
    enum Operator {
        EQ, NE, LT, LTE, GT, GTE, BETWEEN, IN, NOT_IN,
        /** Case-sensitive substring match of a text operand. */
        CONTAINS,
        /** Case-sensitive prefix match of a text operand. */
        STARTS_WITH,
        /** Case-sensitive suffix match of a text operand. */
        ENDS_WITH
    }

    /** Compares one column with one or more bound values. */
    record Comparison(SqlIdentifier column, Operator operator, List<SqlValue> values)
            implements SqlCondition {
        public Comparison {
            Objects.requireNonNull(column, "column");
            Objects.requireNonNull(operator, "operator");
            values = List.copyOf(values);
            switch (operator) {
                case BETWEEN -> require(values.size() == 2, "BETWEEN requires two values");
                case IN, NOT_IN -> require(!values.isEmpty(), "IN requires at least one value");
                default -> require(values.size() == 1, "Comparison requires one value");
            }
            for (SqlValue value : values) {
                require(!(value instanceof SqlValue.Null),
                        "Comparison values cannot be NULL; use a null check");
            }
            if (operator == Operator.CONTAINS || operator == Operator.STARTS_WITH
                    || operator == Operator.ENDS_WITH) {
                require(values.getFirst() instanceof SqlValue.Text,
                        "Text pattern operators require a text value");
            }
        }
    }

    /** Tests a column for {@code NULL}, or for {@code NOT NULL} when negated. */
    record NullCheck(SqlIdentifier column, boolean negated) implements SqlCondition {
        public NullCheck {
            Objects.requireNonNull(column, "column");
        }
    }

    /** Conjunction of at least one term. */
    record And(List<SqlCondition> terms) implements SqlCondition {
        public And {
            terms = List.copyOf(terms);
            require(!terms.isEmpty(), "AND requires at least one term");
        }
    }

    /** Disjunction of at least one term. */
    record Or(List<SqlCondition> terms) implements SqlCondition {
        public Or {
            terms = List.copyOf(terms);
            require(!terms.isEmpty(), "OR requires at least one term");
        }
    }

    /** Negation of one term. */
    record Not(SqlCondition term) implements SqlCondition {
        public Not {
            Objects.requireNonNull(term, "term");
        }
    }

    private static void require(boolean valid, String message) {
        if (!valid) {
            throw new IllegalArgumentException(message);
        }
    }
}
