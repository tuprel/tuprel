package dev.tuprel.sql;

import java.util.Objects;

/** One ordering term of a structural query; placement of NULLs follows the dialect default. */
public record SqlOrder(SqlIdentifier column, Direction direction) {
    public SqlOrder {
        Objects.requireNonNull(column, "column");
        Objects.requireNonNull(direction, "direction");
    }

    /** Sort direction. */
    public enum Direction { ASC, DESC }
}
