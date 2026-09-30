package dev.tuprel.runtime.query;

/**
 * Column that can order query results of model {@code M}.
 *
 * <p>PostgreSQL places {@code NULL} last in ascending order and first in descending order.
 *
 * @param <M> generated model type that owns the column
 */
public sealed interface Sortable<M> permits Field {
    /** Ascending order. */
    Order<M> asc();

    /** Descending order. */
    Order<M> desc();
}
