package dev.tuprel.schema;

import java.util.Objects;

/** A half-open interval in one immutable {@link SourceText}. */
public record SourceSpan(SourceText source, int startOffset, int endOffset) {
    /** Validates span ownership and bounds. */
    public SourceSpan {
        Objects.requireNonNull(source, "source");
        if (startOffset < 0 || endOffset < startOffset || endOffset > source.length()) {
            throw new IllegalArgumentException("invalid source span");
        }
    }

    /** Returns the start position. */
    public SourcePosition start() {
        return source.positionAt(startOffset);
    }

    /** Returns the end position. */
    public SourcePosition end() {
        return source.positionAt(endOffset);
    }

    /** Returns the exact source text covered by this span. */
    public String text() {
        return source.text().substring(startOffset, endOffset);
    }
}
