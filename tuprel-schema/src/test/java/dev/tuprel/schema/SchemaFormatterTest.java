package dev.tuprel.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
