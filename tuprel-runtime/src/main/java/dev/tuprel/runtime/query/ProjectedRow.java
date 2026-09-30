package dev.tuprel.runtime.query;

import dev.tuprel.runtime.RowReader;
import dev.tuprel.sql.SqlIdentifier;
import java.util.Objects;
import java.util.Set;

/**
 * Current row of a {@link Projection}; valid only while the projection mapper runs.
 *
 * @param <M> generated model type being queried
 */
public final class ProjectedRow<M> {
    private final RowReader row;
    private final Set<SqlIdentifier> selected;

    ProjectedRow(RowReader row, Set<SqlIdentifier> selected) {
        this.row = row;
        this.selected = selected;
    }

    /** Value of a selected field; fails if the field is not part of the projection. */
    public <T> T get(Field<M, T> field) {
        Objects.requireNonNull(field, "field");
        if (!selected.contains(field.column())) {
            throw new IllegalArgumentException("Field \"" + field.column().name()
                    + "\" is not part of the projection");
        }
        return field.read(row);
    }
}
