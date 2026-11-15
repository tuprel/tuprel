package dev.tuprel.lsp;

import com.google.gson.JsonObject;
import dev.tuprel.schema.SourceSpan;
import java.util.Arrays;

final class DiagnosticRanges {
    private final String text;
    private final int[] starts;

    DiagnosticRanges(String text) {
        this.text = text;
        int[] offsets = new int[text.length() + 1];
        int count = 1;
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (current == '\r' && index + 1 < text.length() && text.charAt(index + 1) == '\n') {
                index++;
                current = '\n';
            }
            if (current == '\r' || current == '\n') {
                offsets[count++] = index + 1;
            }
        }
        starts = Arrays.copyOf(offsets, count);
    }

    int offset(int line, int character) {
        if (line < 0 || line >= starts.length || character < 0) {
            return -1;
        }
        int start = starts[line];
        int end = line + 1 < starts.length ? starts[line + 1] : text.length();
        while (end > start && (text.charAt(end - 1) == '\r' || text.charAt(end - 1) == '\n')) {
            end--;
        }
        return character <= end - start ? start + character : -1;
    }

    JsonObject range(SourceSpan span) {
        return range(span.startOffset(), span.endOffset());
    }

    JsonObject range(int start, int end) {
        JsonObject range = new JsonObject();
        range.add("start", position(start));
        range.add("end", position(end));
        return range;
    }

    private JsonObject position(int offset) {
        int index = Arrays.binarySearch(starts, offset);
        int line = index >= 0 ? index : -index - 2;
        JsonObject result = new JsonObject();
        result.addProperty("line", line);
        // Java offsets and the negotiated LSP positions both count UTF-16 units.
        result.addProperty("character", offset - starts[line]);
        return result;
    }
}
