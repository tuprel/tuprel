package dev.tuprel.runtime;

/**
 * Work run inside a transaction. The {@link TuprelDatabase} passed to it is bound to the
 * transaction's connection and becomes unusable when the work returns or throws.
 *
 * @param <T> result type
 */
@FunctionalInterface
public interface TransactionWork<T> {
    T execute(TuprelDatabase transaction);
}
