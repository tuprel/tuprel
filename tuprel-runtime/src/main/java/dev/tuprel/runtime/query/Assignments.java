package dev.tuprel.runtime.query;

import dev.tuprel.sql.SqlCommand;
import dev.tuprel.sql.SqlIdentifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Immutable, ordered column values of model {@code M} for an insert or update. Generated
 * clients build it from create and update inputs, including only fields that are present, so
 * an omitted field is never written while a present {@code null} writes SQL {@code NULL}.
 *
 * @param <M> generated model type being written
 */
public final class Assignments<M> {
    private static final Assignments<?> EMPTY = new Assignments<>(List.of());

    private final List<Entry> entries;

    private Assignments(List<Entry> entries) {
        this.entries = List.copyOf(entries);
    }

    /** No values. */
    @SuppressWarnings("unchecked") // EMPTY holds no value of type M.
    public static <M> Assignments<M> empty() {
        return (Assignments<M>) EMPTY;
    }

    /**
     * Returns a copy that also writes {@code value} to the field. {@code null} is accepted only
     * for nullable fields; setting the same field twice is rejected.
     */
    public <T> Assignments<M> set(Field<M, T> field, T value) {
        Objects.requireNonNull(field, "field");
        for (Entry entry : entries) {
            if (entry.field().column().equals(field.column())) {
                throw new IllegalArgumentException("Field \"" + field.column().name() + "\" is set twice");
            }
        }
        List<Entry> appended = new ArrayList<>(entries);
        appended.add(new Entry(field, new SqlCommand.Assignment(field.column(), field.bind(value))));
        return new Assignments<>(appended);
    }

    /** Whether no field is set. */
    public boolean isEmpty() {
        return entries.isEmpty();
    }

    @Override
    public String toString() {
        return "Assignments" + entries.stream().map(entry -> entry.field().column().name()).toList();
    }

    boolean contains(SqlIdentifier column) {
        return entries.stream().anyMatch(entry -> entry.field().column().equals(column));
    }

    List<SqlCommand.Assignment> toSql() {
        return entries.stream().map(Entry::assignment).toList();
    }

    private record Entry(Field<?, ?> field, SqlCommand.Assignment assignment) { }
}
