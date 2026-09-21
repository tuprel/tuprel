package dev.tuprel.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuprel.schema.ast.SchemaDocument;
import dev.tuprel.schema.model.ValidatedSchema;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class SchemaCompilerTest {
    private final SchemaCompiler compiler = new SchemaCompiler();

    @Test
    void compilesMinimalSchemaToValidatedModel() {
        SchemaCompilation result =
                compiler.compile(
                        SourceText.of(
                                "memory.tuprel",
                                "model User {\n    id Int @id\n    name String\n}\n"));

        assertTrue(result.isValid());
        assertEquals(1, result.syntax().orElseThrow().declarations().size());
        ValidatedSchema schema = result.validatedSchema().orElseThrow();
        assertEquals("User", schema.models().getFirst().name());
        assertEquals("String", schema.models().getFirst().fields().get(1).type().name());
    }

    @Test
    void acceptsCompleteFixtureWithConfigurationEnumsIndexesAndRelations() throws IOException {
        String text = resource("schemas/valid/complete.tuprel");

        SchemaCompilation result = compiler.compile(SourceText.of("complete.tuprel", text));

        assertTrue(result.isValid(), () -> result.diagnostics().toString());
        assertEquals(2, result.validatedSchema().orElseThrow().models().size());
        assertEquals("UserStatus", result.validatedSchema().orElseThrow().enums().getFirst().name());
        assertTrue(result.validatedSchema().orElseThrow().datasource().isPresent());
    }

    @Test
    void reportsEmptySourceAsSemanticError() {
        SchemaCompilation result = compiler.compile(SourceText.of("empty.tuprel", ""));

        assertFalse(result.isValid());
        assertHasCode(result.diagnostics(), "TUPREL-SCHEMA-SEM-001");
    }

    @Test
    void reportsLexicalFailureWithLocation() {
        SchemaCompilation result =
                compiler.compile(SourceText.of("broken.tuprel", "model User {\n    id Int @id $\n}"));

        SchemaDiagnostic diagnostic = result.diagnostics().stream().findFirst().orElseThrow();
        assertEquals("TUPREL-SCHEMA-LEX-001", diagnostic.code());
        assertEquals(2, diagnostic.span().start().line());
        assertEquals(16, diagnostic.span().start().column());
    }

    @Test
    void reportsUnterminatedStringAndBlockComment() {
        SchemaCompilation stringResult =
                compiler.compile(SourceText.of("broken.tuprel", "model User { id String @default(\"x)"));
        SchemaCompilation commentResult = compiler.compile(SourceText.of("broken.tuprel", "/* no end"));

        assertHasCode(stringResult.diagnostics(), "TUPREL-SCHEMA-LEX-002");
        assertHasCode(commentResult.diagnostics(), "TUPREL-SCHEMA-LEX-004");
    }

    @Test
    void reportsParserFailureForIncompleteDeclaration() {
        SchemaCompilation result = compiler.compile(SourceText.of("broken.tuprel", "model User {"));

        assertFalse(result.isValid());
        assertHasCode(result.diagnostics(), "TUPREL-SCHEMA-PARSE-004");
    }

    @Test
    void reportsDuplicateModelsAndFields() {
        SchemaCompilation result =
                compiler.compile(
                        SourceText.of(
                                "duplicates.tuprel",
                                "model User { id Int @id id String }\nmodel User { id Int @id }"));

        assertHasCode(result.diagnostics(), "TUPREL-SCHEMA-SEM-005");
        assertHasCode(result.diagnostics(), "TUPREL-SCHEMA-SEM-002");
    }

    @Test
    void reportsUnknownTypeAndIncompatibleDefault() {
        SchemaCompilation result =
                compiler.compile(
                        SourceText.of(
                                "invalid.tuprel",
                                "model User {\n id Int @id @default(true)\n name Strng\n}"));

        assertHasCode(result.diagnostics(), "TUPREL-SCHEMA-SEM-004");
        assertHasCode(result.diagnostics(), "TUPREL-SCHEMA-SEM-012");
    }

    @Test
    void reportsDuplicateEnumMembersAndUnknownDefaultMember() {
        SchemaCompilation result =
                compiler.compile(
                        SourceText.of(
                                "invalid.tuprel",
                                "enum Status { ACTIVE ACTIVE }\n"
                                        + "model User { id Int @id status Status @default(MISSING) }"));

        assertHasCode(result.diagnostics(), "TUPREL-SCHEMA-SEM-014");
        assertHasCode(result.diagnostics(), "TUPREL-SCHEMA-SEM-012");
    }

    @Test
    void reportsInvalidIndexesAndRelationReferences() {
        SchemaCompilation result =
                compiler.compile(
                        SourceText.of(
                                "invalid.tuprel",
                                "model User { id Int @id }\n"
                                        + "model Post { id Int @id authorId Int author User @relation(fields: [missing], references: [id]) @@index([author]) }"));

        assertHasCode(result.diagnostics(), "TUPREL-SCHEMA-SEM-016");
        assertHasCode(result.diagnostics(), "TUPREL-SCHEMA-SEM-013");
    }

    @Test
    void acceptsWhitespaceCommentsMultipleScalarsAndLists() {
        String text = """
                /* header */
                model User {
                 // identifier
                 id Long @id
                 active Boolean @default(true)
                 tags String[]
                 created Instant @default(now())
                }
                """;

        SchemaCompilation result = compiler.compile(SourceText.of("comments.tuprel", text));

        assertTrue(result.isValid(), () -> result.diagnostics().toString());
        assertEquals(4, result.validatedSchema().orElseThrow().models().getFirst().fields().size());
    }

    @Test
    void keepsSyntaxTreeAvailableWhenSemanticValidationFails() {
        SchemaCompilation result = compiler.compile(SourceText.of("invalid.tuprel", "model User {}"));

        assertTrue(result.syntax().isPresent());
        assertTrue(result.validatedSchema().isEmpty());
    }

    private static void assertHasCode(List<SchemaDiagnostic> diagnostics, String code) {
        assertTrue(
                diagnostics.stream().anyMatch(diagnostic -> diagnostic.code().equals(code)),
                () -> "Expected " + code + " in " + diagnostics);
    }

    private static String resource(String path) throws IOException {
        try (InputStream stream = SchemaCompilerTest.class.getClassLoader().getResourceAsStream(path)) {
            if (stream == null) {
                throw new IOException("Missing test resource " + path);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
