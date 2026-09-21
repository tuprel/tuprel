package dev.tuprel.schema;

/** A one-based line and column paired with its zero-based UTF-16 offset. */
public record SourcePosition(int offset, int line, int column) {
    /** Validates a source position. */
    public SourcePosition {
        if (offset < 0) {
            throw new IllegalArgumentException("offset must not be negative");
        }
        if (line < 1) {
            throw new IllegalArgumentException("line must be at least 1");
        }
        if (column < 1) {
            throw new IllegalArgumentException("column must be at least 1");
        }
    }
}
