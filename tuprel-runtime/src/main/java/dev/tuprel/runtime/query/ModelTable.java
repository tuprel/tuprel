package dev.tuprel.runtime.query;

import dev.tuprel.runtime.RowMapper;
import dev.tuprel.sql.SqlIdentifier;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable mapping of model {@code M} to one table: its physical name, identifier field, every
 * mapped field in schema order and the row mapper. Generated clients declare one per model.
 *
 * @param <M> generated model type
 * @param <I> Java type of the identifier field
 */
public final class ModelTable<M, I> {
    private final SqlIdentifier table;
    private final Field<M, I> id;
    private final List<Field<M, ?>> fields;
    private final RowMapper<M> mapper;

    private ModelTable(SqlIdentifier table, Field<M, I> id, List<Field<M, ?>> fields, RowMapper<M> mapper) {
        this.table = table;
        this.id = id;
        this.fields = fields;
        this.mapper = mapper;
    }

    /**
     * Declares a model table. Fields must be non-empty, have distinct columns and include the
     * identifier field, which must be required.
     */
    public static <M, I> ModelTable<M, I> of(String table, Field<M, I> id, List<? extends Field<M, ?>> fields,
            RowMapper<M> mapper) {
        SqlIdentifier name = new SqlIdentifier(Objects.requireNonNull(table, "table"));
        Objects.requireNonNull(id, "id");
        List<Field<M, ?>> copy = List.copyOf(Objects.requireNonNull(fields, "fields"));
        Set<SqlIdentifier> columns = new HashSet<>();
        for (Field<M, ?> field : copy) {
            if (!columns.add(field.column())) {
                throw new IllegalArgumentException("Duplicate model column");
            }
        }
        if (!copy.contains(id)) {
            throw new IllegalArgumentException("The identifier must be one of the model fields");
        }
        if (id.nullable()) {
            throw new IllegalArgumentException("The identifier field cannot be nullable");
        }
        return new ModelTable<>(name, id, copy, Objects.requireNonNull(mapper, "mapper"));
    }

    /** Physical table name. */
    public SqlIdentifier table() {
        return table;
    }

    Field<M, I> id() {
        return id;
    }

    List<Field<M, ?>> fields() {
        return fields;
    }

    List<SqlIdentifier> columns() {
        return fields.stream().map(Field::column).toList();
    }

    RowMapper<M> mapper() {
        return mapper;
    }
}
