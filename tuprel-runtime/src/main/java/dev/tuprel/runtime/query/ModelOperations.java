package dev.tuprel.runtime.query;

import dev.tuprel.runtime.StatementOptions;
import dev.tuprel.runtime.TuprelDatabase;
import dev.tuprel.runtime.TuprelStream;
import dev.tuprel.sql.RenderedSql;
import dev.tuprel.sql.SqlCommand;
import dev.tuprel.sql.SqlCondition;
import dev.tuprel.sql.SqlIdentifier;
import dev.tuprel.sql.SqlQuery;
import dev.tuprel.sql.SqlValue;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Explicit, typed operations over one model table, executed through {@link TuprelDatabase}.
 *
 * <p>Generated clients delegate to this class. Each operation runs one SQL statement, plus one
 * statement per included relation and level, plus the explicitly documented exceptions for
 * batches and optimistic-lock conflicts. Outside a transaction every statement uses its own
 * autocommit connection; on a transaction-bound {@link TuprelDatabase} all statements share the
 * transaction. There is no session, identity map, dirty checking, lazy loading or implicit
 * transaction. Instances hold no mutable state.
 *
 * @param <M> generated model type
 * @param <I> Java type of the identifier field
 */
public final class ModelOperations<M, I> {
    /** Bind parameters per batch statement; far below the PostgreSQL limit of 65 535. */
    static final int BATCH_BINDS = 30_000;

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
     * Relations of the returned row are not loaded.
     */
    public M create(Assignments<M> values) {
        Objects.requireNonNull(values, "values");
        if (values.isEmpty()) {
            throw new IllegalArgumentException("Create requires at least one present field");
        }
        return database.createReturning(new SqlCommand.InsertReturning(table.table(), values.toSql(),
                table.columns()), table.mapper());
    }

    /**
     * Inserts rows with multi-row {@code INSERT} statements and returns the number of inserted
     * rows. Fields absent from a row take the column default. A batch that fits in one statement
     * is atomic on its own; a larger batch needs several statements and is rejected outside an
     * explicit transaction, so it can never be partially stored by accident.
     */
    public long createMany(List<Assignments<M>> rows) {
        Objects.requireNonNull(rows, "rows");
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("Create many requires at least one row");
        }
        Map<SqlIdentifier, Integer> positions = new LinkedHashMap<>();
        List<Map<SqlIdentifier, SqlValue>> cells = new ArrayList<>(rows.size());
        for (Assignments<M> row : rows) {
            Map<SqlIdentifier, SqlValue> values = new LinkedHashMap<>();
            for (SqlCommand.Assignment assignment : Objects.requireNonNull(row, "row").toSql()) {
                positions.putIfAbsent(assignment.column(), positions.size());
                values.put(assignment.column(), assignment.value());
            }
            cells.add(values);
        }
        if (positions.isEmpty()) {
            throw new IllegalArgumentException("Create many requires at least one present field");
        }
        List<SqlIdentifier> columns = List.copyOf(positions.keySet());
        int perStatement = Math.max(1, BATCH_BINDS / columns.size());
        if (rows.size() > perStatement && !database.inTransaction()) {
            throw new IllegalStateException("A batch of " + rows.size() + " rows needs several statements; "
                    + "run it inside a transaction to keep it atomic");
        }
        long inserted = 0;
        for (int from = 0; from < cells.size(); from += perStatement) {
            List<List<Optional<SqlValue>>> statementRows = new ArrayList<>();
            for (Map<SqlIdentifier, SqlValue> row : cells.subList(from, Math.min(cells.size(), from + perStatement))) {
                statementRows.add(columns.stream().map(column -> Optional.ofNullable(row.get(column))).toList());
            }
            inserted += database.executeUpdate(new SqlCommand.InsertMany(table.table(), columns, statementRows));
        }
        return inserted;
    }

    /** Reads the row with the identifier. */
    public Optional<M> findById(I id) {
        return findById(id, Query.all());
    }

    /**
     * Reads the row with the identifier, accepting only includes, a row lock and a timeout from
     * the query.
     */
    public Optional<M> findById(I id, Query<M> query) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(query, "query");
        if (query.condition().isPresent() || !query.orders().isEmpty() || query.offset().isPresent()
                || query.limit().isPresent() || query.cursor().isPresent()) {
            throw new IllegalArgumentException("Find by id accepts only includes, a row lock and a timeout");
        }
        SqlQuery sql = locked(query, SqlQuery.select(table.table(), table.columns())
                .where(table.id().eq(id).toSql()));
        List<M> rows = database.findMany(sql, table.mapper(), options(query));
        if (rows.size() > 1) {
            throw new IllegalStateException("More than one row has the identifier; the identifier "
                    + "column of table \"" + table.table().name() + "\" must be unique");
        }
        return loadIncludes(rows, query).stream().findFirst();
    }

    /** Reads every row matching the query, with its included relations. */
    public List<M> findMany(Query<M> query) {
        return loadIncludes(database.findMany(rows(query), table.mapper(), options(query)), query);
    }

    /** Reads the first row matching the query; use an ordering to make "first" well defined. */
    public Optional<M> findFirst(Query<M> query) {
        List<M> rows = database.findMany(rows(query).limit(1), table.mapper(), options(query));
        return loadIncludes(rows, query).stream().findFirst();
    }

    /**
     * Opens a forward-only stream of the rows matching the query. Streaming requires an
     * explicit transaction and does not load relations; see {@link TuprelStream}.
     */
    public TuprelStream<M> stream(Query<M> query) {
        Objects.requireNonNull(query, "query");
        if (!query.includes().isEmpty()) {
            throw new IllegalArgumentException("Streams do not load relations");
        }
        return database.stream(rows(query), table.mapper(), options(query));
    }

    /** Reads only the projected columns of every row matching the query. */
    public <R> List<R> select(Projection<M, R> projection, Query<M> query) {
        Objects.requireNonNull(projection, "projection");
        Objects.requireNonNull(query, "query");
        if (!query.includes().isEmpty()) {
            throw new IllegalArgumentException("Projections do not load relations");
        }
        List<SqlIdentifier> columns = projection.fields().stream().map(Field::column).toList();
        Set<SqlIdentifier> selected = Set.copyOf(columns);
        return database.findMany(paged(query, SqlQuery.select(table.table(), columns)),
                row -> projection.map(new ProjectedRow<>(row, selected)), options(query));
    }

    /** Counts rows matching the query condition; ordering, paging, locks and includes are rejected. */
    public long count(Query<M> query) {
        return database.count(filtered(unpaged(query), SqlQuery.count(table.table())), options(query));
    }

    /** Whether any row matches the query condition; ordering, paging, locks and includes are rejected. */
    public boolean exists(Query<M> query) {
        return database.exists(filtered(unpaged(query), SqlQuery.exists(table.table())), options(query));
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
        List<M> rows = database.findMany(locked(query, sql), table.mapper(), options(query));
        if (rows.size() <= size) {
            return new CursorPage<>(loadIncludes(rows, query), Optional.empty());
        }
        List<M> page = rows.subList(0, size);
        M last = page.getLast();
        List<SqlValue> key = new ArrayList<>();
        for (Order<M> order : orders) {
            key.add(keyValue(order.field(), last));
        }
        return new CursorPage<>(loadIncludes(page, query), Optional.of(new Cursor(ordering, key)));
    }

    /**
     * Changes the present fields of the row with the identifier and returns the changed row
     * from the same {@code UPDATE ... RETURNING} statement; empty when no row has the identifier.
     * Models with a {@code @version} field must use {@link #updateById(Object, long, Assignments)}.
     */
    public Optional<M> updateById(I id, Assignments<M> values) {
        if (table.version().isPresent()) {
            throw new IllegalStateException("Table \"" + table.table().name()
                    + "\" uses optimistic locking; pass the expected version");
        }
        return database.updateReturning(updateCommand(id, values, Optional.empty()), table.mapper());
    }

    /**
     * Changes the present fields of the row with the identifier only if its {@code @version}
     * equals {@code expectedVersion}, increments the version in the same statement and returns
     * the changed row. Returns empty when no row has the identifier and throws
     * {@link OptimisticLockException} when the row exists with another version. Telling those
     * two cases apart takes a second statement after a failed update; nothing is written by it.
     */
    public Optional<M> updateById(I id, long expectedVersion, Assignments<M> values) {
        ComparableField<M, ? extends Number> version = table.version().orElseThrow(() ->
                new IllegalStateException("Table \"" + table.table().name() + "\" has no @version field"));
        SqlValue expected = versionValue(version, expectedVersion);
        Optional<M> updated = database.updateReturning(updateCommand(id, values,
                Optional.of(new SqlCommand.VersionCheck(version.column(), expected))), table.mapper());
        if (updated.isEmpty() && database.exists(SqlQuery.exists(table.table()).where(table.id().eq(id).toSql()))) {
            throw new OptimisticLockException(table.table().name(), expectedVersion);
        }
        return updated;
    }

    /**
     * Changes the present fields of every row matching the condition and returns the number of
     * changed rows. The condition is mandatory. A {@code @version} field is incremented on every
     * changed row without an expected-version check.
     */
    public long updateMany(Condition<M> where, Assignments<M> values) {
        Objects.requireNonNull(where, "where");
        Objects.requireNonNull(values, "values");
        if (values.isEmpty()) {
            throw new IllegalArgumentException("Update requires at least one present field");
        }
        rejectProtectedColumns(values);
        return database.executeUpdate(new SqlCommand.UpdateWhere(table.table(), values.toSql(), where.toSql(),
                table.version().map(Field::column)));
    }

    /** Deletes the row with the identifier; returns whether a row was deleted. */
    public boolean deleteById(I id) {
        Objects.requireNonNull(id, "id");
        return database.deleteById(new SqlCommand.DeleteById(table.table(), table.id().column(),
                table.id().bind(id))) != 0;
    }

    /**
     * Deletes every row matching the condition and returns the number of deleted rows. The
     * condition is mandatory; related rows are affected only by the database's foreign keys.
     */
    public long deleteMany(Condition<M> where) {
        Objects.requireNonNull(where, "where");
        return database.executeUpdate(new SqlCommand.DeleteWhere(table.table(), where.toSql()));
    }

    /** Renders the statement {@link #findMany} would execute for the rows, without opening a connection. */
    public RenderedSql preview(Query<M> query) {
        return database.preview(rows(query));
    }

    private SqlCommand.UpdateByIdReturning updateCommand(I id, Assignments<M> values,
            Optional<SqlCommand.VersionCheck> version) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(values, "values");
        if (values.isEmpty()) {
            throw new IllegalArgumentException("Update requires at least one present field");
        }
        rejectProtectedColumns(values);
        return new SqlCommand.UpdateByIdReturning(table.table(), table.id().column(), table.id().bind(id),
                values.toSql(), table.columns(), version);
    }

    private void rejectProtectedColumns(Assignments<M> values) {
        if (values.contains(table.id().column())) {
            throw new IllegalArgumentException("The identifier cannot be updated");
        }
        if (table.version().isPresent() && values.contains(table.version().get().column())) {
            throw new IllegalArgumentException("The version is maintained by optimistic locking");
        }
    }

    private static SqlValue versionValue(ComparableField<?, ? extends Number> version, long expected) {
        if (version.type() == SqlValue.Type.INT32) {
            if (expected < Integer.MIN_VALUE || expected > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Expected version is outside the Int range");
            }
            return new SqlValue.Int32((int) expected);
        }
        return new SqlValue.Int64(expected);
    }

    private List<M> loadIncludes(List<M> rows, Query<M> query) {
        if (query.includes().isEmpty()) {
            return rows;
        }
        return new RelationLoader(database, options(query)).loadAll(rows, query.includes());
    }

    private StatementOptions options(Query<M> query) {
        StatementOptions options = StatementOptions.defaults();
        if (query.timeout().isPresent()) {
            options = options.withTimeout(query.timeout().get());
        }
        return query.fetchSize() == 0 ? options : options.withFetchSize(query.fetchSize());
    }

    private SqlQuery rows(Query<M> query) {
        return locked(query, paged(query, SqlQuery.select(table.table(), table.columns())));
    }

    private SqlQuery locked(Query<M> query, SqlQuery sql) {
        if (query.lock().isEmpty()) {
            return sql;
        }
        if (!database.inTransaction()) {
            throw new IllegalStateException("Row locks require an explicit transaction");
        }
        return sql.lock(query.lock().get().toSql());
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
                || query.cursor().isPresent() || query.lock().isPresent() || !query.includes().isEmpty()) {
            throw new IllegalArgumentException("Count and exists only accept a condition and a timeout");
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
