package dev.tuprel.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SourceTextTest {
    @Test
    void mapsLfAndCrLfToOneBasedPositions() {
        SourceText source = SourceText.of("schema.tuprel", "one\r\ntwo\nthree");

        assertEquals(new SourcePosition(0, 1, 1), source.positionAt(0));
        assertEquals(new SourcePosition(5, 2, 1), source.positionAt(5));
        assertEquals(new SourcePosition(9, 3, 1), source.positionAt(9));
    }

    @Test
    void countsUnicodeCodePointsInColumns() {
        SourceText source = SourceText.of("schema.tuprel", "😀 name");

        assertEquals(new SourcePosition(3, 1, 3), source.positionAt(3));
        assertEquals("😀 name", source.lineText(1));
    }

    @Test
    void createsExactHalfOpenSpans() {
        SourceText source = SourceText.of("schema.tuprel", "model User {}");

        SourceSpan span = source.span(6, 10);

        assertEquals("User", span.text());
        assertEquals(1, span.start().line());
        assertEquals(7, span.start().column());
    }
}
