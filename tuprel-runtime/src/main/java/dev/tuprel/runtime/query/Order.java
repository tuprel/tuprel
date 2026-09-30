package dev.tuprel.runtime.query;

import dev.tuprel.sql.SqlOrder;
import java.util.Objects;

/**
 * Immutable ordering term of model {@code M}, created by {@link Sortable#asc()} or
 * {@link Sortable#desc()}.
 *
 * @param <M> generated model type the ordering applies to
 */
public final class Order<M> {
    private final Field<M, ?> field;
    private final boolean ascending;

    Order(Field<M, ?> field, boolean ascending) {
        this.field = Objects.requireNonNull(field, "field");
        this.ascending = ascending;
    }

    /** Structural form, for inspection and for the low-level runtime API. */
    public SqlOrder toSql() {
        return new SqlOrder(field.column(), ascending ? SqlOrder.Direction.ASC : SqlOrder.Direction.DESC);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Order<?> order && field.column().equals(order.field.column())
                && ascending == order.ascending;
    }

    @Override
    public int hashCode() {
        return Objects.hash(field.column(), ascending);
    }

    @Override
    public String toString() {
        return "Order[" + field.column().name() + (ascending ? " ASC" : " DESC") + "]";
    }

    Field<M, ?> field() {
        return field;
    }

    boolean ascending() {
        return ascending;
    }
}
