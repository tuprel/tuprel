package dev.tuprel.runtime.query;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Relation from model {@code M} to model {@code T}, declared by generated code (RFC-003).
 *
 * <p>Rows of {@code M} and {@code T} are joined where the source fields equal the target fields,
 * position by position. A relation never loads by itself: it is loaded only when a query
 * includes it, and the loaded value is attached to a new copy of the source row.
 *
 * @param <M> source model
 * @param <T> target model
 */
public final class Relation<M, T> {
    /** How many target rows a source row relates to. */
    enum Kind { ONE, OPTIONAL, MANY }

    private final String name;
    private final Kind kind;
    private final List<Field<M, ?>> sourceFields;
    private final Supplier<? extends ModelTable<T, ?>> target;
    private final List<Field<T, ?>> targetFields;
    private final BiFunction<M, List<T>, M> attach;

    private Relation(String name, Kind kind, List<? extends Field<M, ?>> sourceFields,
            Supplier<? extends ModelTable<T, ?>> target, List<? extends Field<T, ?>> targetFields,
            BiFunction<M, List<T>, M> attach) {
        this.name = Objects.requireNonNull(name, "name");
        this.kind = kind;
        this.sourceFields = List.copyOf(Objects.requireNonNull(sourceFields, "sourceFields"));
        this.target = Objects.requireNonNull(target, "target");
        this.targetFields = List.copyOf(Objects.requireNonNull(targetFields, "targetFields"));
        this.attach = attach;
        if (this.sourceFields.isEmpty() || this.sourceFields.size() != this.targetFields.size()) {
            throw new IllegalArgumentException("A relation needs matching, non-empty key fields");
        }
    }

    /**
     * Relation to exactly one target row, such as a required foreign key. A missing target row
     * fails loading instead of producing an incomplete value.
     */
    public static <M, T> Relation<M, T> toOne(String name, List<? extends Field<M, ?>> sourceFields,
            Supplier<? extends ModelTable<T, ?>> target, List<? extends Field<T, ?>> targetFields,
            BiFunction<M, T, M> attach) {
        Objects.requireNonNull(attach, "attach");
        return new Relation<>(name, Kind.ONE, sourceFields, target, targetFields,
                (row, values) -> attach.apply(row, values.getFirst()));
    }

    /** Relation to at most one target row, such as a nullable foreign key or a one-to-one inverse. */
    public static <M, T> Relation<M, T> toOptional(String name, List<? extends Field<M, ?>> sourceFields,
            Supplier<? extends ModelTable<T, ?>> target, List<? extends Field<T, ?>> targetFields,
            BiFunction<M, Optional<T>, M> attach) {
        Objects.requireNonNull(attach, "attach");
        return new Relation<>(name, Kind.OPTIONAL, sourceFields, target, targetFields,
                (row, values) -> attach.apply(row, values.stream().findFirst()));
    }

    /** Relation to any number of target rows, such as the inverse of a foreign key. */
    public static <M, T> Relation<M, T> toMany(String name, List<? extends Field<M, ?>> sourceFields,
            Supplier<? extends ModelTable<T, ?>> target, List<? extends Field<T, ?>> targetFields,
            BiFunction<M, List<T>, M> attach) {
        return new Relation<>(name, Kind.MANY, sourceFields, target, targetFields,
                Objects.requireNonNull(attach, "attach"));
    }

    /** Includes the relation as is. */
    public Include<M> include() {
        return new Include<>(this, Query.all());
    }

    /**
     * Includes the relation with a nested query on the target. A to-many relation accepts a
     * condition, an ordering and nested includes; a to-one relation accepts nested includes
     * only. Other parts are rejected when the query runs.
     */
    public Include<M> include(UnaryOperator<Query<T>> query) {
        Objects.requireNonNull(query, "query");
        return new Include<>(this, Objects.requireNonNull(query.apply(Query.all()), "query result"));
    }

    /** Qualified relation name, such as {@code User.posts}. */
    public String name() {
        return name;
    }

    @Override
    public String toString() {
        return "Relation[" + name + "]";
    }

    Kind kind() {
        return kind;
    }

    List<Field<M, ?>> sourceFields() {
        return sourceFields;
    }

    ModelTable<T, ?> target() {
        return Objects.requireNonNull(target.get(), "target table");
    }

    List<Field<T, ?>> targetFields() {
        return targetFields;
    }

    M attach(M row, List<T> values) {
        return Objects.requireNonNull(attach.apply(row, values), "attached row");
    }
}
