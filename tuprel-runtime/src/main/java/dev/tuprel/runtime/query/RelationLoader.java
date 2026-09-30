package dev.tuprel.runtime.query;

import dev.tuprel.runtime.StatementOptions;
import dev.tuprel.runtime.TuprelDatabase;
import dev.tuprel.sql.SqlCondition;
import dev.tuprel.sql.SqlOrder;
import dev.tuprel.sql.SqlQuery;
import dev.tuprel.sql.SqlValue;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Loads included relations in batches: one query per included relation and nesting level for
 * all rows together, never one query per row. Key sets larger than {@link #KEYS_PER_QUERY} are
 * split across queries, which preserves the order of each row's related rows.
 */
final class RelationLoader {
    /** Keys per relation query; keeps statements far below bind-parameter limits. */
    static final int KEYS_PER_QUERY = 500;

    private final TuprelDatabase database;
    private final StatementOptions options;

    RelationLoader(TuprelDatabase database, StatementOptions options) {
        this.database = database;
        this.options = options;
    }

    <M> List<M> loadAll(List<M> rows, List<Include<M>> includes) {
        List<M> current = rows;
        for (Include<M> include : includes) {
            current = include.load(this, current);
        }
        return current;
    }

    <M, T> List<M> load(Relation<M, T> relation, Query<T> query, List<M> rows) {
        validate(relation, query);
        if (rows.isEmpty()) {
            return rows;
        }
        ModelTable<T, ?> target = relation.target();
        Set<List<SqlValue>> keys = new LinkedHashSet<>();
        for (M row : rows) {
            key(relation.sourceFields(), row).ifPresent(keys::add);
        }
        List<List<SqlValue>> distinct = new ArrayList<>(keys);
        List<SqlOrder> orders = query.orders().isEmpty()
                ? List.of(target.id().asc().toSql())
                : query.orders().stream().map(Order::toSql).toList();
        List<T> loaded = new ArrayList<>();
        for (int from = 0; from < distinct.size(); from += KEYS_PER_QUERY) {
            SqlCondition condition = keyCondition(relation.targetFields(),
                    distinct.subList(from, Math.min(distinct.size(), from + KEYS_PER_QUERY)));
            if (query.condition().isPresent()) {
                condition = new SqlCondition.And(List.of(condition, query.condition().get().toSql()));
            }
            loaded.addAll(database.findMany(SqlQuery.select(target.table(), target.columns())
                    .where(condition).orderBy(orders), target.mapper(), options));
        }
        loaded = loadAll(loaded, query.includes());
        Map<List<SqlValue>, List<T>> grouped = new HashMap<>();
        for (T value : loaded) {
            List<SqlValue> key = key(relation.targetFields(), value).orElseThrow(
                    () -> new IllegalStateException("Relation " + relation.name() + " loaded a row without key"));
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(value);
        }
        List<M> result = new ArrayList<>(rows.size());
        for (M row : rows) {
            List<T> related = key(relation.sourceFields(), row)
                    .map(key -> grouped.getOrDefault(key, List.of()))
                    .orElse(List.of());
            if (relation.kind() == Relation.Kind.ONE && related.size() != 1) {
                throw new IllegalStateException("Relation " + relation.name() + " requires exactly one related row"
                        + " but found " + related.size());
            }
            if (relation.kind() == Relation.Kind.OPTIONAL && related.size() > 1) {
                throw new IllegalStateException("Relation " + relation.name() + " matched more than one row");
            }
            result.add(relation.attach(row, List.copyOf(related)));
        }
        return List.copyOf(result);
    }

    private static <T> void validate(Relation<?, T> relation, Query<T> query) {
        if (query.offset().isPresent() || query.limit().isPresent() || query.cursor().isPresent()
                || query.lock().isPresent() || query.timeout().isPresent() || query.fetchSize() != 0) {
            throw new IllegalArgumentException("Included relation " + relation.name()
                    + " accepts only a condition, an ordering and nested includes");
        }
        if (relation.kind() != Relation.Kind.MANY
                && (query.condition().isPresent() || !query.orders().isEmpty())) {
            throw new IllegalArgumentException("Included to-one relation " + relation.name()
                    + " accepts only nested includes");
        }
    }

    /** Key of a row, or empty when any key column is NULL; decimals compare numerically. */
    private static <R> Optional<List<SqlValue>> key(List<Field<R, ?>> fields, R row) {
        List<SqlValue> values = new ArrayList<>(fields.size());
        for (Field<R, ?> field : fields) {
            SqlValue value = keyValue(field, row);
            if (value instanceof SqlValue.Null) {
                return Optional.empty();
            }
            values.add(value instanceof SqlValue.Decimal decimal
                    ? new SqlValue.Decimal(decimal.value().stripTrailingZeros())
                    : value);
        }
        return Optional.of(List.copyOf(values));
    }

    private static <R, V> SqlValue keyValue(Field<R, V> field, R row) {
        V value = field.valueOf(Objects.requireNonNull(row, "row"));
        return value == null ? new SqlValue.Null(field.type()) : field.predicateValue(value);
    }

    private static SqlCondition keyCondition(List<? extends Field<?, ?>> fields, List<List<SqlValue>> keys) {
        if (fields.size() == 1) {
            return new SqlCondition.Comparison(fields.getFirst().column(), SqlCondition.Operator.IN,
                    keys.stream().map(List::getFirst).toList());
        }
        List<SqlCondition> alternatives = new ArrayList<>(keys.size());
        for (List<SqlValue> key : keys) {
            List<SqlCondition> terms = new ArrayList<>(fields.size());
            for (int index = 0; index < fields.size(); index++) {
                terms.add(new SqlCondition.Comparison(fields.get(index).column(), SqlCondition.Operator.EQ,
                        List.of(key.get(index))));
            }
            alternatives.add(new SqlCondition.And(terms));
        }
        return alternatives.size() == 1 ? alternatives.getFirst() : new SqlCondition.Or(alternatives);
    }
}
