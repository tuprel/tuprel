package dev.tuprel.schema;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Immutable schema source and deterministic offset-to-position mapping. */
public final class SourceText {
    private final String name;
    private final String text;
    private final int[] lineStarts;

    private SourceText(String name, String text) {
        this.name = Objects.requireNonNull(name, "name");
        this.text = Objects.requireNonNull(text, "text");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        this.lineStarts = computeLineStarts(text);
    }

    /** Creates source text with a logical name used only in diagnostics. */
    public static SourceText of(String name, String text) {
        return new SourceText(name, text);
    }

    /** Returns the logical source name. */
    public String name() {
        return name;
    }

    /** Returns the complete immutable source content. */
    public String text() {
        return text;
    }

    /** Returns the source length in UTF-16 code units. */
    public int length() {
        return text.length();
    }

    /** Creates a validated half-open span in this source. */
    public SourceSpan span(int startOffset, int endOffset) {
        return new SourceSpan(this, startOffset, endOffset);
    }

    /** Maps a UTF-16 offset to a one-based line and Unicode code-point column. */
    public SourcePosition positionAt(int offset) {
        if (offset < 0 || offset > text.length()) {
            throw new IllegalArgumentException("offset is outside the source");
        }

        int low = 0;
        int high = lineStarts.length - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            if (lineStarts[middle] <= offset) {
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }

        int lineIndex = Math.max(0, high);
        int lineStart = lineStarts[lineIndex];
        int column = text.codePointCount(lineStart, offset) + 1;
        return new SourcePosition(offset, lineIndex + 1, column);
    }

    /** Returns one source line without its line terminator. */
    public String lineText(int oneBasedLine) {
        if (oneBasedLine < 1 || oneBasedLine > lineStarts.length) {
            throw new IllegalArgumentException("line is outside the source");
        }
        int start = lineStarts[oneBasedLine - 1];
        int end = oneBasedLine == lineStarts.length ? text.length() : lineStarts[oneBasedLine] - 1;
        if (end > start && text.charAt(end - 1) == '\r') {
            end--;
        }
        return text.substring(start, end);
    }

    private static int[] computeLineStarts(String value) {
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current == '\r') {
                if (index + 1 < value.length() && value.charAt(index + 1) == '\n') {
                    index++;
                }
                starts.add(index + 1);
            } else if (current == '\n') {
                starts.add(index + 1);
            }
        }
        return starts.stream().mapToInt(Integer::intValue).toArray();
    }
}
