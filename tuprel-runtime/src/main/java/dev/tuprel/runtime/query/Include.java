package dev.tuprel.runtime.query;

import java.util.List;
import java.util.Objects;

/**
 * An explicit request to load one relation of model {@code M}, created by a generated
 * {@code ...Include} class.
 *
 * @param <M> source model
 */
public final class Include<M> {
    private final Relation<M, ?> relation;
    private final Loading<M> loading;

    <T> Include(Relation<M, T> relation, Query<T> query) {
        this.relation = Objects.requireNonNull(relation, "relation");
        Objects.requireNonNull(query, "query");
        this.loading = (loader, rows) -> loader.load(relation, query, rows);
    }

    @Override
    public String toString() {
        return "Include[" + relation.name() + "]";
    }

    Relation<M, ?> relation() {
        return relation;
    }

    List<M> load(RelationLoader loader, List<M> rows) {
        return loading.load(loader, rows);
    }

    @FunctionalInterface
    private interface Loading<M> {
        List<M> load(RelationLoader loader, List<M> rows);
    }
}
