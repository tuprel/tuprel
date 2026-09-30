package dev.tuprel.runtime.query;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable query description for model {@code M}; every method returns a new query.
 *
 * <p>A query only describes rows; nothing runs until it is passed to an explicit operation of a
 * generated client. Each {@link #where(Condition)} is combined with the previous ones by AND,
 * and each {@link #orderBy} appends terms. Without an ordering, the row order of results is
 * unspecified.
 *
 * @param <M> generated model type being queried
 */
public final class Query<M> {
    private static final Query<?> EMPTY = new Query<>(Optional.empty(), List.of(), Optional.empty(),
            Optional.empty(), Optional.empty());

    private final Optional<Condition<M>> condition;
    private final List<Order<M>> orders;
    private final Optional<Integer> offset;
    private final Optional<Integer> limit;
    private final Optional<Cursor> after;

    private Query(Optional<Condition<M>> condition, List<Order<M>> orders, Optional<Integer> offset,
            Optional<Integer> limit, Optional<Cursor> after) {
        this.condition = condition;
        this.orders = List.copyOf(orders);
        this.offset = offset;
        this.limit = limit;
        this.after = after;
    }

    /** A query matching every row, without ordering or paging. */
    @SuppressWarnings("unchecked") // EMPTY holds no value of type M.
    public static <M> Query<M> all() {
        return (Query<M>) EMPTY;
    }

    /** Adds a condition, combined by AND with any previous condition. */
    public Query<M> where(Condition<M> value) {
        Objects.requireNonNull(value, "value");
        return new Query<>(Optional.of(condition.map(existing -> existing.and(value)).orElse(value)),
                orders, offset, limit, after);
    }

    /** Appends ordering terms; earlier terms take precedence. */
    @SafeVarargs
    public final Query<M> orderBy(Order<M>... values) {
        Objects.requireNonNull(values, "values");
        List<Order<M>> appended = new ArrayList<>(orders);
        for (Order<M> value : values) {
            appended.add(Objects.requireNonNull(value, "value"));
        }
        return new Query<>(condition, appended, offset, limit, after);
    }

    /** Skips the first {@code count} rows (offset pagination). */
    public Query<M> skip(int count) {
        if (count < 0) {
            throw new IllegalArgumentException("Skip cannot be negative");
        }
        return new Query<>(condition, orders, Optional.of(count), limit, after);
    }

    /** Returns at most {@code count} rows; for cursor pagination it is the page size. */
    public Query<M> take(int count) {
        if (count < 1) {
            throw new IllegalArgumentException("Take must be positive");
        }
        return new Query<>(condition, orders, offset, Optional.of(count), after);
    }

    /** Continues cursor pagination after the given cursor; only valid for cursor operations. */
    public Query<M> after(Cursor cursor) {
        return new Query<>(condition, orders, offset, limit,
                Optional.of(Objects.requireNonNull(cursor, "cursor")));
    }

    @Override
    public String toString() {
        return "Query[condition=" + condition.isPresent() + ", orders=" + orders
                + ", skip=" + offset.map(String::valueOf).orElse("none")
                + ", take=" + limit.map(String::valueOf).orElse("none")
                + ", after=" + after.isPresent() + "]";
    }

    Optional<Condition<M>> condition() {
        return condition;
    }

    List<Order<M>> orders() {
        return orders;
    }

    Optional<Integer> offset() {
        return offset;
    }

    Optional<Integer> limit() {
        return limit;
    }

    Optional<Cursor> cursor() {
        return after;
    }
}
