package dev.tuprel.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class SchemaFormatterTest {
    private final SchemaFormatter formatter = new SchemaFormatter();

    @Test
    void formatsAstDeterministicallyAndIsIdempotent() {
        SourceText source =
                SourceText.of(
                        "format.tuprel",
                        "model User{\n id   Int @id\n name String?\n}\n\nmodel Post { id Int @id }\n");

        String formatted = formatter.format(source).formattedText().orElseThrow();

        assertEquals(
                "model User {\n    id Int @id\n    name String?\n}\n\nmodel Post {\n    id Int @id\n}\n",
                formatted);
        assertEquals(formatted, formatter.format(SourceText.of("format.tuprel", formatted)).formattedText().orElseThrow());
    }

    @Test
    void preservesCommentsAndNormalizesIndentation() {
        SourceText source =
                SourceText.of(
                        "format.tuprel",
                        "model User {\n  // keep this\n id   Int @id\n}\n");

        String formatted = formatter.format(source).formattedText().orElseThrow();

        assertTrue(formatted.contains("// keep this"));
        assertTrue(formatted.contains("    id Int @id"));
        assertEquals(formatted, formatter.format(SourceText.of("format.tuprel", formatted)).formattedText().orElseThrow());
    }

    @Test
    void preservesStandaloneBlockCommentExactly() {
        String comment = "/* block\n\t * aligned 🧪\n\n\n    tail  \n */";
        String input = comment + "\n\n\nmodel Product{\n id   UUID @id\n}\n";
        String expected = comment + "\n\nmodel Product {\n    id UUID @id\n}\n";
        assertPreservedFormatting(input, expected);
        assertPreservedFormatting(input.replace("\n", "\r\n"), expected);
    }

    @Test
    void formatsCodeAroundInlineBlockCommentsWithoutChangingTokens() {
        String opening = "/* opening { //\n    exact indentation\n  */";
        String betweenAttributes = "/* attribute }\n\t spacing\n\n */";
        String footer = "/* footer\n  */";
        String literal = "\"literal /* } // \\\"quote\\\" 🧪 \\u0041\"";
        String input = "model Product{" + opening + "\n"
                + " id   UUID @id" + betweenAttributes + "@default(uuid())\n"
                + " name String @default(" + literal + ")\n"
                + " " + footer + "}\n"
                + "  // next declaration\n"
                + "model Other{\n id    Int @id\n}\n";
        String expected = "model Product { " + opening + "\n"
                + "    id UUID @id " + betweenAttributes + " @default(uuid())\n"
                + "    name String @default(" + literal + ")\n"
                + "    " + footer + " }\n"
                + "// next declaration\n"
                + "model Other {\n    id Int @id\n}\n";
        assertPreservedFormatting(input, expected);
    }

    private void assertPreservedFormatting(String input, String expected) {
        SourceText original = SourceText.of("before.tuprel", input);
        String formatted = formatter.format(original).formattedText().orElseThrow();
        assertEquals(expected, formatted);

        SourceText result = SourceText.of("after.tuprel", formatted);
        assertTrue(new SchemaCompiler().compile(result).isValid());
        assertEquals(tokensWithoutPositions(original), tokensWithoutPositions(result));
        assertEquals(formatted, formatter.format(result).formattedText().orElseThrow());
    }

    private List<List<String>> tokensWithoutPositions(SourceText source) {
        return Lexer.lex(source).tokens().stream()
                .map(token -> List.of(token.kind().name(), token.text().replace("\r\n", "\n")))
                .toList();
    }

    @Test
    void keepsSpaceBeforeRelationFieldLists() {
        SourceText source =
                SourceText.of(
                        "format.tuprel",
                        """
                        model User {
                         id Int @id
                         posts Post[]
                        }
                        model Post {
                         id Int @id
                         userId Int
                         user User @relation(fields:[userId], references:[id])
                        }
                        """);

        String formatted = formatter.format(source).formattedText().orElseThrow();

        assertTrue(formatted.contains("fields: [userId], references: [id]"));
    }

    @Test
    void refusesToFormatInvalidSchema() {
        SchemaFormatResult result = formatter.format(SourceText.of("invalid.tuprel", "model User {}"));

        assertTrue(result.formattedText().isEmpty());
        assertTrue(result.diagnostics().stream().anyMatch(item -> item.code().endsWith("SEM-020")));
    }

    @Test
    void formatsListTypesAndCallArgumentsWithoutArtificialSpaces() {
        SourceText source =
                SourceText.of(
                        "format.tuprel",
                        "model User { id UUID @id @default(uuid()) tags String[] }\n");

        assertEquals(
                "model User {\n    id UUID @id @default(uuid())\n    tags String[]\n}\n",
                formatter.format(source).formattedText().orElseThrow());
    }
}
