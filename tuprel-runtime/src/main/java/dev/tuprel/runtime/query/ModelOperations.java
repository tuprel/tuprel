package dev.tuprel.runtime.query;

import dev.tuprel.runtime.TuprelDatabase;
import dev.tuprel.sql.RenderedSql;
import dev.tuprel.sql.SqlCommand;
import dev.tuprel.sql.SqlCondition;
import dev.tuprel.sql.SqlIdentifier;
import dev.tuprel.sql.SqlOrder;
import dev.tuprel.sql.SqlQuery;
import dev.tuprel.sql.SqlValue;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Explicit, typed operations over one model table, executed through {@link TuprelDatabase}.
 *
 * <p>Generated clients delegate to this class. Every method runs exactly one SQL statement on
 * its own connection in autocommit mode, as defined for the runtime; there is no session,
 * identity map, dirty checking, lazy loading or implicit transaction. Instances hold no mutable
 * state and can be shared when the underlying {@code DataSource} can.
 *
 * @param <M> generated model type
 * @param <I> Java type of the identifier field
 */
public final class ModelOperations<M, I> {
    private final TuprelDatabase database;
    private final ModelTable<M, I> table;

    /** Binds a model table to a database runtime. */
    public ModelOperations(TuprelDatabase database, ModelTable<M, I> table) {
        this.database = Objects.requireNonNull(database, "database");
        this.table = Objects.requireNonNull(table, "table");
    }

    /**
     * Inserts one row with the present values and returns the stored row, including values
     * produced by database defaults, from the same {@code INSERT ... RETURNING} statement.
     */
    public M create(Assignments<M> values) {
        Objects.requireNonNull(values, "values");
        if (values.isEmpty()) {
            throw new IllegalArgumentException("Create requires at least one present field");
        }
        return database.createReturning(new SqlCommand.InsertReturning(table.table(), values.toSql(),
                table.columns()), table.mapper());
    }

    /** Reads the row with the identifier. */
    public Optional<M> findById(I id) {
        Objects.requireNonNull(id, "id");
        List<M> rows = database.findMany(SqlQuery.select(table.table(), table.columns())
                .where(table.id().eq(id).toSql()), table.mapper());
        if (rows.size() > 1) {
            throw new IllegalStateException("More than one row has the identifier; the identifier "
                    + "column of table \"" + table.table().name() + "\" must be unique");
        }
        return rows.stream().findFirst();
    }

    /** Reads every row matching the query. */
    public List<M> findMany(Query<M> query) {
        return database.findMany(rows(query), table.mapper());
    }

    /** Reads the first row matching the query; use an ordering to make "first" well defined. */
    public Optional<M> findFirst(Query<M> query) {
        return database.findMany(rows(query).limit(1), table.mapper()).stream().findFirst();
    }

    /** Reads only the projected columns of every row matching the query. */
    public <R> List<R> select(Projection<M, R> projection, Query<M> query) {
        Objects.requireNonNull(projection, "projection");
        List<SqlIdentifier> columns = projection.fields().stream().map(Field::column).toList();
        Set<SqlIdentifier> selected = Set.copyOf(columns);
        return database.findMany(paged(query, SqlQuery.select(table.table(), columns)),
                row -> projection.map(new ProjectedRow<>(row, selected)));
    }

    /** Counts rows matching the query condition; ordering and paging are rejected. */
    public long count(Query<M> query) {
        return database.count(filtered(unpaged(query), SqlQuery.count(table.table())));
    }

    /** Whether any row matches the query condition; ordering and paging are rejected. */
    public boolean exists(Query<M> query) {
        return database.exists(filtered(unpaged(query), SqlQuery.exists(table.table())));
    }

    /**
     * Reads one page of keyset (cursor) pagination.
     *
     * <p>The query must set {@code take} (the page size) and cannot set {@code skip}. Without
     * an ordering, rows are ordered by the identifier ascending. An explicit ordering must end
     * with the identifier, which makes the order total and deterministic, and cannot use nullable
     * fields. The next page starts strictly after the last row of this one, so rows inserted or
     * removed between requests do not shift later pages.
     */
    public CursorPage<M> findManyCursor(Query<M> query) {
        Objects.requireNonNull(query, "query");
        if (query.offset().isPresent()) {
            throw new IllegalArgumentException("Cursor pagination cannot be combined with skip");
        }
        int size = query.limit().orElseThrow(
                () -> new IllegalArgumentException("Cursor pagination requires take"));
        List<Order<M>> orders = cursorOrdering(query.orders());
        String ordering = ordering(orders);
        SqlQuery sql = SqlQuery.select(table.table(), table.columns())
                .orderBy(orders.stream().map(Order::toSql).toList())
                .limit(size + 1);
        List<SqlCondition> terms = new ArrayList<>();
        query.condition().ifPresent(condition -> terms.add(condition.toSql()));
        query.cursor().ifPresent(cursor -> terms.add(after(orders, ordering, cursor)));
        if (!terms.isEmpty()) {
            sql = sql.where(terms.size() == 1 ? terms.getFirst() : new SqlCondition.And(terms));
        }
        List<M> rows = database.findMany(sql, table.mapper());
        if (rows.size() <= size) {
            return new CursorPage<>(rows, Optional.empty());
        }
        List<M> page = rows.subList(0, size);
        M last = page.getLast();
        List<SqlValue> key = new ArrayList<>();
        for (Order<M> order : orders) {
            key.add(keyValue(order.field(), last));
        }
        return new CursorPage<>(page, Optional.of(new Cursor(ordering, key)));
    }

    /**
     * Changes the present fields of the row with the identifier and returns the changed row
     * from the same {@code UPDATE ... RETURNING} statement; empty when no row has the identifier.
     */
    public Optional<M> updateById(I id, Assignments<M> values) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(values, "values");
        if (values.isEmpty()) {
            throw new IllegalArgumentException("Update requires at least one present field");
        }
        if (values.contains(table.id().column())) {
            throw new IllegalArgumentException("The identifier cannot be updated");
        }
        return database.updateReturning(new SqlCommand.UpdateByIdReturning(table.table(),
                table.id().column(), table.id().bind(id), values.toSql(), table.columns()), table.mapper());
    }

    /** Deletes the row with the identifier; returns whether a row was deleted. */
    public boolean deleteById(I id) {
        Objects.requireNonNull(id, "id");
        return database.deleteById(new SqlCommand.DeleteById(table.table(), table.id().column(),
                table.id().bind(id))) != 0;
    }

    /** Renders the statement {@link #findMany} would execute, without opening a connection. */
    public RenderedSql preview(Query<M> query) {
        return database.preview(rows(query));
    }

    private SqlQuery rows(Query<M> query) {
        return paged(query, SqlQuery.select(table.table(), table.columns()));
    }

    private static <M> SqlQuery paged(Query<M> query, SqlQuery base) {
        Objects.requireNonNull(query, "query");
        if (query.cursor().isPresent()) {
            throw new IllegalArgumentException("A cursor is only valid for cursor pagination");
        }
        SqlQuery sql = filtered(query, base).orderBy(query.orders().stream().map(Order::toSql).toList());
        if (query.limit().isPresent()) {
            sql = sql.limit(query.limit().get());
        }
        if (query.offset().isPresent()) {
            sql = sql.offset(query.offset().get());
        }
        return sql;
    }

    private static <M> SqlQuery filtered(Query<M> query, SqlQuery base) {
        return query.condition().map(condition -> base.where(condition.toSql())).orElse(base);
    }

    private static <M> Query<M> unpaged(Query<M> query) {
        Objects.requireNonNull(query, "query");
        if (!query.orders().isEmpty() || query.offset().isPresent() || query.limit().isPresent()
                || query.cursor().isPresent()) {
            throw new IllegalArgumentException("Count and exists only accept a condition");
        }
        return query;
    }

    private List<Order<M>> cursorOrdering(List<Order<M>> requested) {
        if (requested.isEmpty()) {
            return List.of(table.id().asc());
        }
        Set<SqlIdentifier> columns = new HashSet<>();
        for (Order<M> order : requested) {
            if (!columns.add(order.field().column())) {
                throw new IllegalArgumentException("Cursor ordering repeats a field");
            }
            if (order.field().nullable()) {
                throw new IllegalArgumentException("Cursor ordering cannot use nullable field \""
                        + order.field().column().name() + "\"");
            }
        }
        if (!requested.getLast().field().column().equals(table.id().column())) {
            throw new IllegalArgumentException("Cursor ordering must end with the identifier field");
        }
        return requested;
    }

    private String ordering(List<Order<M>> orders) {
        StringJoiner joined = new StringJoiner(",", table.table().name() + ":", "");
        for (Order<M> order : orders) {
            joined.add(order.field().column().name() + (order.ascending() ? "+" : "-"));
        }
        return joined.toString();
    }

    private SqlCondition after(List<Order<M>> orders, String ordering, Cursor cursor) {
        if (!cursor.ordering().equals(ordering) || cursor.values().size() != orders.size()) {
            throw new IllegalArgumentException("Cursor does not match this query ordering");
        }
        List<SqlCondition> alternatives = new ArrayList<>();
        for (int index = 0; index < orders.size(); index++) {
            List<SqlCondition> terms = new ArrayList<>();
            for (int previous = 0; previous < index; previous++) {
                terms.add(comparison(orders.get(previous), SqlCondition.Operator.EQ,
                        cursor.values().get(previous)));
            }
            Order<M> order = orders.get(index);
            terms.add(comparison(order, order.ascending() ? SqlCondition.Operator.GT
                    : SqlCondition.Operator.LT, cursor.values().get(index)));
            alternatives.add(terms.size() == 1 ? terms.getFirst() : new SqlCondition.And(terms));
        }
        return alternatives.size() == 1 ? alternatives.getFirst() : new SqlCondition.Or(alternatives);
    }

    private static SqlCondition comparison(Order<?> order, SqlCondition.Operator operator, SqlValue value) {
        if (Cursor.typeOf(value) != order.field().type()) {
            throw new IllegalArgumentException("Cursor does not match this query ordering");
        }
        return new SqlCondition.Comparison(order.field().column(), operator, List.of(value));
    }

    private static <M, T> SqlValue keyValue(Field<M, T> field, M row) {
        return field.bind(field.valueOf(row));
    }
}
