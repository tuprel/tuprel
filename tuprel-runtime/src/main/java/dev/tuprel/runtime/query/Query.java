package dev.tuprel.runtime.query;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable query description for model {@code M}; every method returns a new query.
 *
 * <p>A query only describes rows; nothing runs until it is passed to an explicit operation of a
 * generated client. Each {@link #where(Condition)} is combined with the previous ones by AND,
 * and {@link #orderBy} and {@link #include} append. Without an ordering, the row order of
 * results is unspecified. Operations reject parts they cannot honour instead of ignoring them.
 *
 * @param <M> generated model type being queried
 */
public final class Query<M> {
    private static final Query<?> EMPTY = new Query<>(new State<>(Optional.empty(), List.of(), Optional.empty(),
            Optional.empty(), Optional.empty(), List.of(), Optional.empty(), Optional.empty(), 0));

    private record State<M>(Optional<Condition<M>> condition, List<Order<M>> orders, Optional<Integer> offset,
            Optional<Integer> limit, Optional<Cursor> after, List<Include<M>> includes, Optional<RowLock> lock,
            Optional<Duration> timeout, int fetchSize) {
        State {
            orders = List.copyOf(orders);
            includes = List.copyOf(includes);
        }
    }

    private final State<M> state;

    private Query(State<M> state) {
        this.state = state;
    }

    /** A query matching every row, without ordering or paging. */
    @SuppressWarnings("unchecked") // EMPTY holds no value of type M.
    public static <M> Query<M> all() {
        return (Query<M>) EMPTY;
    }

    /** Adds a condition, combined by AND with any previous condition. */
    public Query<M> where(Condition<M> value) {
        Objects.requireNonNull(value, "value");
        return with(new State<>(Optional.of(state.condition().map(existing -> existing.and(value)).orElse(value)),
                state.orders(), state.offset(), state.limit(), state.after(), state.includes(), state.lock(),
                state.timeout(), state.fetchSize()));
    }

    /** Appends ordering terms; earlier terms take precedence. */
    @SafeVarargs
    public final Query<M> orderBy(Order<M>... values) {
        Objects.requireNonNull(values, "values");
        List<Order<M>> appended = new ArrayList<>(state.orders());
        for (Order<M> value : values) {
            appended.add(Objects.requireNonNull(value, "value"));
        }
        return with(new State<>(state.condition(), appended, state.offset(), state.limit(), state.after(),
                state.includes(), state.lock(), state.timeout(), state.fetchSize()));
    }

    /** Skips the first {@code count} rows (offset pagination). */
    public Query<M> skip(int count) {
        if (count < 0) {
            throw new IllegalArgumentException("Skip cannot be negative");
        }
        return with(new State<>(state.condition(), state.orders(), Optional.of(count), state.limit(), state.after(),
                state.includes(), state.lock(), state.timeout(), state.fetchSize()));
    }

    /** Returns at most {@code count} rows; for cursor pagination it is the page size. */
    public Query<M> take(int count) {
        if (count < 1) {
            throw new IllegalArgumentException("Take must be positive");
        }
        return with(new State<>(state.condition(), state.orders(), state.offset(), Optional.of(count), state.after(),
                state.includes(), state.lock(), state.timeout(), state.fetchSize()));
    }

    /** Continues cursor pagination after the given cursor; only valid for cursor operations. */
    public Query<M> after(Cursor cursor) {
        return with(new State<>(state.condition(), state.orders(), state.offset(), state.limit(),
                Optional.of(Objects.requireNonNull(cursor, "cursor")), state.includes(), state.lock(),
                state.timeout(), state.fetchSize()));
    }

    /**
     * Loads relations of the returned rows explicitly. Each included relation costs one extra
     * query per level for all rows together, never one query per row. Including the same
     * relation twice is rejected.
     */
    @SafeVarargs
    public final Query<M> include(Include<M>... values) {
        Objects.requireNonNull(values, "values");
        List<Include<M>> appended = new ArrayList<>(state.includes());
        for (Include<M> value : values) {
            Objects.requireNonNull(value, "value");
            for (Include<M> existing : appended) {
                if (existing.relation().name().equals(value.relation().name())) {
                    throw new IllegalArgumentException("Relation " + value.relation().name() + " is already included");
                }
            }
            appended.add(value);
        }
        return with(new State<>(state.condition(), state.orders(), state.offset(), state.limit(), state.after(),
                appended, state.lock(), state.timeout(), state.fetchSize()));
    }

    /** Locks the returned rows until the enclosing transaction ends; requires a transaction. */
    public Query<M> lock(RowLock value) {
        return with(new State<>(state.condition(), state.orders(), state.offset(), state.limit(), state.after(),
                state.includes(), Optional.of(Objects.requireNonNull(value, "value")), state.timeout(),
                state.fetchSize()));
    }

    /**
     * Limits the execution time of each statement the operation runs, rounded up to whole
     * seconds; a statement that exceeds it is cancelled by the database.
     */
    public Query<M> timeout(Duration value) {
        Objects.requireNonNull(value, "value");
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException("Timeout must be positive");
        }
        return with(new State<>(state.condition(), state.orders(), state.offset(), state.limit(), state.after(),
                state.includes(), state.lock(), Optional.of(value), state.fetchSize()));
    }

    /** Rows fetched per round trip; mainly for streaming. */
    public Query<M> fetchSize(int value) {
        if (value < 1) {
            throw new IllegalArgumentException("Fetch size must be positive");
        }
        return with(new State<>(state.condition(), state.orders(), state.offset(), state.limit(), state.after(),
                state.includes(), state.lock(), state.timeout(), value));
    }

    @Override
    public String toString() {
        return "Query[condition=" + state.condition().isPresent() + ", orders=" + state.orders()
                + ", skip=" + state.offset().map(String::valueOf).orElse("none")
                + ", take=" + state.limit().map(String::valueOf).orElse("none")
                + ", after=" + state.after().isPresent()
                + ", includes=" + state.includes().stream().map(include -> include.relation().name()).toList()
                + ", lock=" + state.lock().map(Enum::name).orElse("none") + "]";
    }

    private Query<M> with(State<M> next) {
        return new Query<>(next);
    }

    Optional<Condition<M>> condition() {
        return state.condition();
    }

    List<Order<M>> orders() {
        return state.orders();
    }

    Optional<Integer> offset() {
        return state.offset();
    }

    Optional<Integer> limit() {
        return state.limit();
    }

    Optional<Cursor> cursor() {
        return state.after();
    }

    List<Include<M>> includes() {
        return state.includes();
    }

    Optional<RowLock> lock() {
        return state.lock();
    }

    Optional<Duration> timeout() {
        return state.timeout();
    }

    int fetchSize() {
        return state.fetchSize();
    }
}
