package dev.tuprel.runtime.query;

import dev.tuprel.runtime.RowReader;
import dev.tuprel.sql.SqlCondition;
import dev.tuprel.sql.SqlIdentifier;
import dev.tuprel.sql.SqlValue;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Typed column of model {@code M} holding values of type {@code T}.
 *
 * <p>Instances are created by generated code. Every predicate produces a {@link Condition} of
 * the same model, so a condition built from one model's fields cannot be applied to another
 * model's query. Predicate values are never {@code null}: comparisons follow SQL three-valued
 * logic and never match {@code NULL} columns, so {@code NULL} is tested with {@link #isNull()}.
 *
 * <p>This base type offers equality, membership and null tests; ordered and text types use
 * {@link ComparableField} and {@link TextField}.
 *
 * @param <M> generated model type that owns the column
 * @param <T> Java value type of the column
 */
public sealed class Field<M, T> implements Sortable<M> permits ComparableField, TextField {
    private final SqlIdentifier column;
    private final SqlValue.Type type;
    private final boolean nullable;
    private final Function<? super T, ? extends SqlValue> binder;
    private final BiFunction<RowReader, String, ? extends T> reader;
    private final Function<? super M, ? extends T> accessor;

    Field(String column, SqlValue.Type type, boolean nullable,
            Function<? super T, ? extends SqlValue> binder,
            BiFunction<RowReader, String, ? extends T> reader,
            Function<? super M, ? extends T> accessor) {
        this.column = new SqlIdentifier(Objects.requireNonNull(column, "column"));
        this.type = Objects.requireNonNull(type, "type");
        this.nullable = nullable;
        this.binder = Objects.requireNonNull(binder, "binder");
        this.reader = Objects.requireNonNull(reader, "reader");
        this.accessor = Objects.requireNonNull(accessor, "accessor");
    }

    /** Boolean column with equality predicates. */
    public static <M> Field<M, Boolean> bool(String column, boolean nullable,
            Function<? super M, Boolean> accessor) {
        return new Field<>(column, SqlValue.Type.BOOLEAN, nullable, SqlValue.Bool::new,
                RowReader::bool, accessor);
    }

    /** UUID column with equality predicates. */
    public static <M> Field<M, UUID> uuid(String column, boolean nullable,
            Function<? super M, UUID> accessor) {
        return new Field<>(column, SqlValue.Type.UUID, nullable, SqlValue.Uuid::new,
                RowReader::uuid, accessor);
    }

    /**
     * Enum column stored as the constant name in a text column; native PostgreSQL enum types
     * are not part of this contract.
     */
    public static <M, E extends Enum<E>> Field<M, E> enumeration(String column, boolean nullable,
            Function<? super M, E> accessor, Function<String, E> parser) {
        Objects.requireNonNull(parser, "parser");
        return new Field<>(column, SqlValue.Type.TEXT, nullable, value -> new SqlValue.Text(value.name()),
                (row, name) -> {
                    String text = row.text(name);
                    if (text == null) {
                        return null;
                    }
                    try {
                        return parser.apply(text);
                    } catch (IllegalArgumentException exception) {
                        throw new IllegalStateException("Column \"" + name
                                + "\" contains a value that is not a declared enum constant", exception);
                    }
                }, accessor);
    }

    /** Binary column with equality predicates, wrapped in an immutable value type. */
    public static <M, B> Field<M, B> bytes(String column, boolean nullable,
            Function<? super M, B> accessor, Function<byte[], B> wrap, Function<? super B, byte[]> unwrap) {
        Objects.requireNonNull(wrap, "wrap");
        Objects.requireNonNull(unwrap, "unwrap");
        return new Field<>(column, SqlValue.Type.BYTES, nullable,
                value -> new SqlValue.Binary(unwrap.apply(value)),
                (row, name) -> {
                    byte[] value = row.bytes(name);
                    return value == null ? null : wrap.apply(value);
                }, accessor);
    }

    /** Physical column name. */
    public SqlIdentifier column() {
        return column;
    }

    /** Whether the schema declares the column nullable. */
    public boolean nullable() {
        return nullable;
    }

    /**
     * Reads this column from the current row inside a row mapper. A {@code NULL} in a column the
     * schema declares required fails instead of producing a partially valid model.
     */
    public T read(RowReader row) {
        T value = reader.apply(Objects.requireNonNull(row, "row"), column.name());
        if (value == null && !nullable) {
            throw new IllegalStateException("Column \"" + column.name()
                    + "\" returned NULL for a required field");
        }
        return value;
    }

    /** Matches rows whose column equals the value. */
    public Condition<M> eq(T value) {
        return compare(SqlCondition.Operator.EQ, value);
    }

    /** Matches rows whose column differs from the value; {@code NULL} columns do not match. */
    public Condition<M> notEq(T value) {
        return compare(SqlCondition.Operator.NE, value);
    }

    /** Matches rows whose column equals one of the values; the collection cannot be empty. */
    public Condition<M> in(Collection<? extends T> values) {
        return many(SqlCondition.Operator.IN, values);
    }

    /**
     * Matches rows whose column differs from every value; {@code NULL} columns do not match.
     * The collection cannot be empty.
     */
    public Condition<M> notIn(Collection<? extends T> values) {
        return many(SqlCondition.Operator.NOT_IN, values);
    }

    /** Matches rows whose column is {@code NULL}. */
    public Condition<M> isNull() {
        return new Condition<>(new SqlCondition.NullCheck(column, false));
    }

    /** Matches rows whose column is not {@code NULL}. */
    public Condition<M> isNotNull() {
        return new Condition<>(new SqlCondition.NullCheck(column, true));
    }

    @Override
    public Order<M> asc() {
        return new Order<>(this, true);
    }

    @Override
    public Order<M> desc() {
        return new Order<>(this, false);
    }

    @Override
    public String toString() {
        return "Field[" + column.name() + "]";
    }

    SqlValue.Type type() {
        return type;
    }

    /** Binds a value for writes; {@code null} becomes a typed NULL only for nullable columns. */
    SqlValue bind(T value) {
        if (value == null) {
            if (!nullable) {
                throw new IllegalArgumentException("Field \"" + column.name() + "\" is required");
            }
            return new SqlValue.Null(type);
        }
        return predicateValue(value);
    }

    T valueOf(M model) {
        return accessor.apply(model);
    }

    final SqlValue predicateValue(T value) {
        return Objects.requireNonNull(binder.apply(Objects.requireNonNull(value, "value")),
                "bound value");
    }

    final Condition<M> compare(SqlCondition.Operator operator, T value) {
        return new Condition<>(new SqlCondition.Comparison(column, operator,
                List.of(predicateValue(value))));
    }

    private Condition<M> many(SqlCondition.Operator operator, Collection<? extends T> values) {
        Objects.requireNonNull(values, "values");
        return new Condition<>(new SqlCondition.Comparison(column, operator,
                values.stream().map(this::predicateValue).toList()));
    }
}
