package dev.tuprel.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SchemaCompletionTest {
    private final SchemaCompletion completion = new SchemaCompletion();

    @Test
    void completesDeclarationKeywordsAndReplacesTheWholeWord() {
        assertEquals(List.of("datasource", "enum", "generator", "model"), labels("|"));
        assertEquals(List.of("model"), labels("model Record { id Int @id }\r\nmo|"));
        assertEquals(List.of("model"), labels("model Broken { field }\nmo|"));
        SchemaCompletion.Result result = query("ge|nerato").orElseThrow();
        assertEquals(List.of(new SchemaCompletion.Item("generator", SchemaCompletion.Kind.KEYWORD)), result.items());
        assertEquals(0, result.replacement().startOffset());
        assertEquals(8, result.replacement().endOffset());
        assertThrows(UnsupportedOperationException.class, () -> result.items().clear());
    }

    @Test
    void reusesOfficialScalarsAndRecoveredDeclarationsEvenWithErrors() {
        String marked = """
                model Team { next T| broken Unknown }
                model Twin {}
                enum Tone { Up }
                model Team {}
                model String {}
                model {}
                """;
        SchemaCompletion.Result result = query(marked).orElseThrow();
        assertEquals(List.of(
                new SchemaCompletion.Item("Team", SchemaCompletion.Kind.MODEL),
                new SchemaCompletion.Item("Tone", SchemaCompletion.Kind.ENUM),
                new SchemaCompletion.Item("Twin", SchemaCompletion.Kind.MODEL)), result.items());
        assertEquals(result.items(), query(marked).orElseThrow().items());

        assertEquals(List.of("Boolean", "Bytes", "Decimal", "Double", "Float", "Instant", "Int",
                "Json", "LocalDate", "LocalDateTime", "LocalTime", "Long", "Owner", "Short", "String", "UUID"),
                labels("model Owner { x |"));
        assertEquals(List.of("String"), labels("model Thing { x Str| }\nmodel String {}"));
        assertEquals(List.of("Float", "Foo"), labels("{ model Fake {} }\nmodel Foo { field F| }"));
    }

    @Test
    void keepsFieldAndModelAttributeSlotsSeparate() {
        assertEquals(List.of("@default", "@id", "@relation", "@unique", "@version"),
                labels("model User { id Int @|"));
        assertEquals(List.of("@default", "@id", "@relation", "@unique", "@version"),
                labels("model User { id Int[] |"));
        assertEquals(List.of("@@index", "@@unique"), labels("model User {\r\n  |"));
        assertEquals(List.of("@@index", "@@unique"), labels("model User { id UUID @id\n  @@|"));
        assertEquals(List.of("@@index"), labels("model User { id UUID @id @@in|dex([id]) }"));
        assertEquals(List.of("@unique"), labels("model User { id UUID @un|ique }"));
        assertEquals("@true", query("model User { id Int @|true }").orElseThrow().replacement().text());
        assertTrue(query("model User { @| }").isEmpty());
        assertTrue(query("model User { id @| }").isEmpty());
        assertTrue(query("model User { id Int @ /* wait */ | }").isEmpty());
        assertTrue(query("model User { |id Int }").isEmpty());
    }

    @Test
    void avoidsCommentsStringsArgumentsAndAmbiguousSyntax() {
        for (String marked : List.of(
                "// model|", "/* enum| */\n", "/*/|", "model X { id Int /* open |",
                "model X { id String @default(\"abc|\") }",
                "model X { id String @default(\"unterminated|",
                "model X { id Int @default(foo(|)) }",
                "model X { id Int @default([0 |",
                "model X { name $\t |",
                "model X name Str|",
                "{ model X { name Str|",
                "model X { field\n |",
                "datasource db { provider = |", "enum Direction { N|", "model |")) {
            assertTrue(query(marked).isEmpty(), marked);
        }
        assertEquals(List.of("model"), labels("/* closed */mo|"));
    }

    @Test
    void treatsSupplementaryCharactersAsTwoUnitsAndRejectsInvalidOffsets() {
        String marked = "model User {\r\n    /* \uD83E\uDDEA */ field Str|ing\r\n}";
        SchemaCompletion.Result result = query(marked).orElseThrow();
        assertEquals(List.of("String"), result.items().stream().map(SchemaCompletion.Item::label).toList());
        assertEquals(marked.indexOf("Str"), result.replacement().startOffset());
        assertEquals(marked.indexOf("Str") + "String".length(), result.replacement().endOffset());
        SourceText source = SourceText.of("test.tuprel", "/* \uD83E\uDDEA */");
        assertTrue(completion.complete(source, 4).isEmpty());
        assertTrue(completion.complete(source, -1).isEmpty());
        assertTrue(completion.complete(source, source.length() + 1).isEmpty());
    }

    private List<String> labels(String marked) {
        return query(marked).orElseThrow().items().stream().map(SchemaCompletion.Item::label).toList();
    }

    private Optional<SchemaCompletion.Result> query(String marked) {
        return completion.complete(SourceText.of("test.tuprel", marked.replace("|", "")), marked.indexOf('|'));
    }
}
