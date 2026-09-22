package dev.tuprel.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuprel.schema.SchemaCompilation;
import dev.tuprel.schema.SchemaCompiler;
import dev.tuprel.schema.SourceText;
import dev.tuprel.schema.model.ValidatedSchema;
import dev.tuprel.schema.model.ValidatedSchema.ValidatedEnum;
import dev.tuprel.schema.model.ValidatedSchema.ValidatedField;
import dev.tuprel.schema.model.ValidatedSchema.ValidatedModel;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JavaCodeGeneratorTest {
    @TempDir Path temporaryDirectory;

    @Test
    void compilesGeneratedJavaFromRealValidatedSchema() throws IOException {
        GeneratedJavaSources generated = generate("""
                datasource db {
                    provider = "postgresql"
                    url = env("DATABASE_URL")
                }
                generator java {
                    package = "dev.example.generated"
                }
                enum Role {
                    ADMIN
                    USER
                }
                model User {
                    id UUID @id @default(uuid())
                    email String? @unique
                    active Boolean
                    balance Decimal?
                    bytes Bytes?
                    payload Json?
                    role Role
                    scores Int[]
                    posts Post[]
                }
                model Post {
                    id Long @id
                    authorId UUID
                    author User @relation(fields: [authorId], references: [id])
                }
                """);
        Path sourceRoot = temporaryDirectory.resolve("generated");
        new GeneratedSourceWriter().write(sourceRoot, generated);
        List<String> sourcePaths = new ArrayList<>();
        for (String relative : generated.files().keySet()) {
            sourcePaths.add(sourceRoot.resolve(relative).toString());
        }
        Path classes = temporaryDirectory.resolve("classes");
        Files.createDirectories(classes);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler);
        List<String> arguments = new ArrayList<>(List.of("--release", "21", "-Xlint:all", "-Werror",
                "-d", classes.toString()));
        arguments.addAll(sourcePaths);
        assertEquals(0, compiler.run(null, null, null, arguments.toArray(String[]::new)));
        assertTrue(Files.isRegularFile(classes.resolve("dev/example/generated/model/User.class")));
    }

    @Test
    void generationIsByteIdenticalAndUsesExpectedTypes() {
        String schema = """
                generator java { package = "dev.example.generated" }
                enum Status { ACTIVE INACTIVE }
                model User {
                    id Int @id
                    status Status
                    note String?
                    labels String[]
                }
                """;
        GeneratedJavaSources first = generate(schema);
        GeneratedJavaSources second = generate(schema);
        assertEquals(first.files(), second.files());
        String model = first.files().get("dev/example/generated/model/User.java");
        assertTrue(model.contains("int id"));
        assertTrue(model.contains("Status status"));
        assertTrue(model.contains("String note"));
        assertTrue(model.contains("List<String> labels"));
        assertTrue(model.contains("List.copyOf(labels)"));
        assertFalse(model.contains("import java.util.*"));
        assertTrue(first.files().get("dev/example/generated/fields/UserFields.java")
                .contains("TuprelField<List<String>> LABELS"));
    }

    @Test
    void reviewedGoldenModelMatchesGeneratedBytes() throws IOException {
        GeneratedJavaSources generated = generate("""
                generator java { package = "dev.example.generated" }
                model User {
                    id UUID @id
                    name String?
                }
                """);
        try (InputStream expected = getClass().getResourceAsStream("/golden/User.java.txt")) {
            assertNotNull(expected);
            assertEquals(new String(expected.readAllBytes(), StandardCharsets.UTF_8),
                    generated.files().get("dev/example/generated/model/User.java"));
        }
    }

    @Test
    void mapsEveryRemainingScalarWithNullableAndListForms() throws IOException {
        GeneratedJavaSources generated = generate("""
                generator java { package = "dev.example.generated" }
                model Metrics {
                    id Int @id
                    small Short
                    large Long?
                    amount Decimal
                    ratio Float
                    average Double?
                    when Instant
                    localStamp LocalDateTime?
                    day LocalDate
                    clock LocalTime?
                    flags Boolean[]
                }
                """);
        String model = generated.files().get("dev/example/generated/model/Metrics.java");
        assertTrue(model.contains("short small"));
        assertTrue(model.contains("Long large"));
        assertTrue(model.contains("BigDecimal amount"));
        assertTrue(model.contains("float ratio"));
        assertTrue(model.contains("Double average"));
        assertTrue(model.contains("Instant when"));
        assertTrue(model.contains("LocalDateTime localStamp"));
        assertTrue(model.contains("LocalDate day"));
        assertTrue(model.contains("LocalTime clock"));
        assertTrue(model.contains("List<Boolean> flags"));
        Path sources = temporaryDirectory.resolve("all-scalars");
        new GeneratedSourceWriter().write(sources, generated);
        Path classes = temporaryDirectory.resolve("all-scalars-classes");
        Files.createDirectories(classes);
        List<String> arguments = new ArrayList<>(List.of("--release", "21", "-Xlint:all", "-Werror",
                "-d", classes.toString()));
        for (String relative : generated.files().keySet()) {
            arguments.add(sources.resolve(relative).toString());
        }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(
                null, null, null, arguments.toArray(String[]::new)));
    }

    @Test
    void createAndUpdatePreservePresenceAndExcludeIdentityUpdate() {
        GeneratedJavaSources generated = generate("""
                generator java { package = "dev.example.generated" }
                model User {
                    id UUID @id @default(uuid())
                    name String
                    note String?
                }
                """);
        String create = generated.files().get("dev/example/generated/create/UserCreate.java");
        String update = generated.files().get("dev/example/generated/update/UserUpdate.java");
        assertTrue(create.contains("TuprelInput<UUID> id"));
        assertTrue(create.contains("if (!name.present())"));
        assertFalse(create.contains("if (!id.present())"));
        assertTrue(update.contains("TuprelInput<String> note"));
        assertFalse(update.contains("TuprelInput<UUID> id"));
    }

    @Test
    void rejectsNamesThatWouldMakeJavaAmbiguous() {
        SchemaCompilation collision = compile("""
                generator java { package = "dev.example.generated" }
                model List { id Int @id }
                """);
        JavaGenerationResult result = new JavaCodeGenerator().generate(
                collision.validatedSchema().orElseThrow());
        assertFalse(result.isSuccess());
        assertEquals("TUPREL-CODEGEN-003", result.diagnostics().getFirst().code());
    }

    @Test
    void rejectsDerivedCompanionNameCollisions() {
        SchemaCompilation collision = compile("""
                generator java { package = "dev.example.generated" }
                model User { id Int @id }
                model UserCreate { id Int @id }
                """);
        JavaGenerationResult result = new JavaCodeGenerator().generate(
                collision.validatedSchema().orElseThrow());
        assertFalse(result.isSuccess());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.code().equals("TUPREL-CODEGEN-007")));
    }

    @Test
    void requiresGeneratorPackageForCodegenButNotSchemaValidation() {
        SchemaCompilation compilation = compile("model User { id Int @id }\n");
        assertTrue(compilation.isValid());
        JavaGenerationResult result = new JavaCodeGenerator().generate(
                compilation.validatedSchema().orElseThrow());
        assertEquals("TUPREL-CODEGEN-001", result.diagnostics().getFirst().code());
    }

    @Test
    void rejectsReservedJavaPackageNamespace() {
        SchemaCompilation compilation = compile("""
                generator java { package = "java.example" }
                model User { id Int @id }
                """);
        assertTrue(compilation.isValid());
        JavaGenerationResult result = new JavaCodeGenerator().generate(
                compilation.validatedSchema().orElseThrow());
        assertFalse(result.isSuccess());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.code().equals("TUPREL-CODEGEN-009")));
    }

    @Test
    void rejectsForgedValidatedModelBeforeRenderingSource() {
        SchemaCompilation compilation = compile("""
                generator java { package = "dev.example.generated" }
                model User { id Int @id }
                """);
        ValidatedSchema valid = compilation.validatedSchema().orElseThrow();
        ValidatedModel model = valid.models().getFirst();
        ValidatedField field = model.fields().getFirst();
        ValidatedField injected = new ValidatedField("id; class Evil {}", field.type(),
                field.cardinality(), field.id(), field.unique(), field.defaultValue(),
                field.relation(), field.span());
        ValidatedModel forged = new ValidatedModel(model.name(), List.of(injected),
                model.indexes(), model.span());
        ValidatedSchema forgedSchema = new ValidatedSchema(valid.syntax(), List.of(forged),
                valid.enums(), valid.datasource(), valid.generator());

        JavaGenerationResult result = new JavaCodeGenerator().generate(forgedSchema);

        assertFalse(result.isSuccess());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.code().equals("TUPREL-CODEGEN-010")));
    }

    @Test
    void rejectsForgedEnumValueBeforeRenderingSource() {
        SchemaCompilation compilation = compile("""
                generator java { package = "dev.example.generated" }
                enum Role { ADMIN }
                model User { id Int @id role Role }
                """);
        ValidatedSchema valid = compilation.validatedSchema().orElseThrow();
        ValidatedEnum original = valid.enums().getFirst();
        ValidatedEnum forged = new ValidatedEnum(original.name(),
                List.of("ADMIN; static { System.exit(1); }"), original.span());
        ValidatedSchema forgedSchema = new ValidatedSchema(valid.syntax(), valid.models(),
                List.of(forged), valid.datasource(), valid.generator());

        JavaGenerationResult result = new JavaCodeGenerator().generate(forgedSchema);

        assertFalse(result.isSuccess());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.code().equals("TUPREL-CODEGEN-012")));
    }

    private static GeneratedJavaSources generate(String schema) {
        SchemaCompilation compilation = compile(schema);
        assertTrue(compilation.isValid(), () -> "Schema failed validation: " + compilation.diagnostics());
        JavaGenerationResult result = new JavaCodeGenerator().generate(
                compilation.validatedSchema().orElseThrow());
        assertTrue(result.isSuccess(), () -> "Generation failed: " + result.diagnostics());
        return result.sources().orElseThrow();
    }

    private static SchemaCompilation compile(String schema) {
        return new SchemaCompiler().compile(SourceText.of("test/schema.tuprel", schema));
    }
}
