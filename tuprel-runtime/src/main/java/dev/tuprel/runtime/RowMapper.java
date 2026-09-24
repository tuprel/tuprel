package dev.tuprel.runtime;

/** Maps one row synchronously, without exposing JDBC resources. */
@FunctionalInterface
public interface RowMapper<T> {
    T map(RowReader row);
}
