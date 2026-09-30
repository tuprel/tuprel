package dev.tuprel.runtime.query;

import dev.tuprel.sql.SqlIdentifier;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Partial select of model {@code M}: reads only the listed columns and maps each row to
 * {@code R}. Reading an unlisted field inside the mapper fails, so an unselected column can
 * never be observed as {@code null}.
 *
 * @param <M> generated model type being queried
 * @param <R> result type produced for each row
 */
public final class Projection<M, R> {
    private final List<Field<M, ?>> fields;
    private final Function<? super ProjectedRow<M>, ? extends R> mapper;

    private Projection(List<Field<M, ?>> fields, Function<? super ProjectedRow<M>, ? extends R> mapper) {
        this.fields = fields;
        this.mapper = mapper;
    }

    /** Selects the fields, in order; the list must be non-empty and free of duplicates. */
    public static <M, R> Projection<M, R> of(List<? extends Field<M, ?>> fields,
            Function<? super ProjectedRow<M>, ? extends R> mapper) {
        List<Field<M, ?>> copy = List.copyOf(Objects.requireNonNull(fields, "fields"));
        if (copy.isEmpty()) {
            throw new IllegalArgumentException("A projection requires at least one field");
        }
        Set<SqlIdentifier> columns = new HashSet<>();
        for (Field<M, ?> field : copy) {
            if (!columns.add(field.column())) {
                throw new IllegalArgumentException("Duplicate projection field");
            }
        }
        return new Projection<>(copy, Objects.requireNonNull(mapper, "mapper"));
    }

    List<Field<M, ?>> fields() {
        return fields;
    }

    R map(ProjectedRow<M> row) {
        return Objects.requireNonNull(mapper.apply(row), "projection result");
    }
}
