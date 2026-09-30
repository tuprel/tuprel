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
import java.io.ByteArrayOutputStream;
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
                "-classpath", System.getProperty("java.class.path"),
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
                "-classpath", System.getProperty("java.class.path"),
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

    @Test
    void reviewedGoldenOperationalSourcesMatchGeneratedBytes() throws IOException {
        GeneratedJavaSources generated = generate("""
                generator java { package = "dev.example.generated" }
                enum Role {
                    ADMIN
                    MEMBER
                }
                model Account {
                    id Long @id
                    email String
                    score Int?
                    role Role
                }
                """);
        for (String path : List.of("where/AccountWhere", "order/AccountOrder", "client/AccountClient",
                "TuprelClient")) {
            String golden = "/golden/" + path.substring(path.lastIndexOf('/') + 1) + ".java.txt";
            try (InputStream expected = getClass().getResourceAsStream(golden)) {
                assertNotNull(expected, golden);
                assertEquals(new String(expected.readAllBytes(), StandardCharsets.UTF_8),
                        generated.files().get("dev/example/generated/" + path + ".java"), golden);
            }
        }
    }

    @Test
    void generatedApiCompilesForTypedUsageAndRejectsCrossModelOrInvalidOperations() throws IOException {
        GeneratedJavaSources generated = generate("""
                generator java { package = "dev.example.generated" }
                model User {
                    id UUID @id
                    name String
                    age Int?
                }
                model Post {
                    id Long @id
                    title String
                }
                """);
        String valid = """
                package dev.example.usage;

                import dev.example.generated.TuprelClient;
                import dev.example.generated.model.User;
                import dev.example.generated.order.UserOrder;
                import dev.example.generated.where.UserWhere;
                import dev.tuprel.runtime.query.Condition;
                import dev.tuprel.runtime.query.Projection;
                import java.util.List;

                final class Usage {
                    private Usage() {}

                    static List<String> names(TuprelClient db) {
                        Condition<User> adults = UserWhere.age().gte(18).and(UserWhere.age().isNotNull());
                        List<User> users = db.user().findMany(query -> query
                                .where(Condition.anyOf(UserWhere.name().startsWith("A"), adults))
                                .orderBy(UserOrder.name().asc(), UserOrder.id().desc())
                                .skip(0)
                                .take(20));
                        return db.user().select(Projection.of(List.of(UserWhere.name()),
                                row -> row.get(UserWhere.name())), query -> query.where(adults)).isEmpty()
                                ? List.of() : users.stream().map(User::name).toList();
                    }
                }
                """;
        assertEquals(0, compile(generated, "dev/example/usage/Usage.java", valid).exitCode());

        CompilationResult crossModel = compile(generated, "dev/example/usage/CrossModel.java", """
                package dev.example.usage;

                import dev.example.generated.TuprelClient;
                import dev.example.generated.where.PostWhere;

                final class CrossModel {
                    private CrossModel() {}

                    static Object misuse(TuprelClient db) {
                        return db.user().findMany(query -> query.where(PostWhere.title().eq("x")));
                    }
                }
                """);
        assertTrue(crossModel.exitCode() != 0, "A Post condition must not compile in a User query");
        assertTrue(crossModel.errors().contains("Condition<Post>"), crossModel.errors());

        CompilationResult uuidPattern = compile(generated, "dev/example/usage/UuidPattern.java", """
                package dev.example.usage;

                import dev.example.generated.where.UserWhere;

                final class UuidPattern {
                    private UuidPattern() {}

                    static Object misuse() {
                        return UserWhere.id().contains("x");
                    }
                }
                """);
        assertTrue(uuidPattern.exitCode() != 0, "A UUID column must not expose text patterns");
        assertTrue(uuidPattern.errors().contains("cannot find symbol")
                && uuidPattern.errors().contains("contains"), uuidPattern.errors());
    }

    @Test
    void runtimeTypeNamesDoNotReserveModelOrEnumNames() throws IOException {
        GeneratedJavaSources generated = generate("""
                generator java { package = "dev.example.generated" }
                enum TextField { A B }
                model Query {
                    id Int @id
                    kind TextField
                }
                model Field {
                    id Int @id
                    label String?
                }
                model Optional {
                    id Int @id
                }
                """);
        String client = generated.files().get("dev/example/generated/client/QueryClient.java");
        assertTrue(client.contains("dev.tuprel.runtime.query.Query<Query>"));
        assertEquals(0, compile(generated, null, null).exitCode());
    }

    @Test
    void modelsWithListOrJsonColumnsGetTypedColumnsButNoClient() {
        GeneratedJavaSources generated = generate("""
                generator java { package = "dev.example.generated" }
                model Document {
                    id Int @id
                    title String
                    body Json
                    tags String[]
                }
                model Note {
                    id Int @id
                }
                """);
        assertFalse(generated.files().containsKey("dev/example/generated/client/DocumentClient.java"));
        assertTrue(generated.files().containsKey("dev/example/generated/client/NoteClient.java"));
        String where = generated.files().get("dev/example/generated/where/DocumentWhere.java");
        assertTrue(where.contains("TextField<Document> title()"));
        assertFalse(where.contains("body()"));
        assertFalse(where.contains("tags()"));
        String root = generated.files().get("dev/example/generated/TuprelClient.java");
        assertTrue(root.contains("NoteClient note()"));
        assertFalse(root.contains("DocumentClient"));
    }

    @Test
    void rejectsTableAndColumnNamesBeyondThePostgresqlIdentifierLimit() {
        String longName = "a".repeat(64);
        SchemaCompilation longColumn = compile("""
                generator java { package = "dev.example.generated" }
                model User {
                    id Int @id
                    %s String
                }
                """.formatted(longName));
        JavaGenerationResult result = new JavaCodeGenerator().generate(
                longColumn.validatedSchema().orElseThrow());
        assertFalse(result.isSuccess());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.code().equals("TUPREL-CODEGEN-014")));

        SchemaCompilation longTable = compile("""
                generator java { package = "dev.example.generated" }
                model %s {
                    id Int @id
                }
                """.formatted("U" + "a".repeat(63)));
        assertTrue(new JavaCodeGenerator().generate(longTable.validatedSchema().orElseThrow())
                .diagnostics().stream().anyMatch(d -> d.code().equals("TUPREL-CODEGEN-014")));
        assertTrue(generate("""
                generator java { package = "dev.example.generated" }
                model User {
                    id Int @id
                    %s String
                }
                """.formatted("a".repeat(63))).files().containsKey("dev/example/generated/client/UserClient.java"));
    }

    private record CompilationResult(int exitCode, String errors) { }

    private CompilationResult compile(GeneratedJavaSources generated, String extraPath, String extraSource)
            throws IOException {
        Path root = Files.createTempDirectory(temporaryDirectory, "compile");
        Path sources = root.resolve("sources");
        new GeneratedSourceWriter().write(sources, generated);
        List<String> arguments = new ArrayList<>(List.of("--release", "21", "-Xlint:all", "-Werror",
                "-classpath", System.getProperty("java.class.path"),
                "-d", Files.createDirectories(root.resolve("classes")).toString()));
        for (String relative : generated.files().keySet()) {
            arguments.add(sources.resolve(relative).toString());
        }
        if (extraPath != null) {
            Path extra = root.resolve("extra").resolve(extraPath);
            Files.createDirectories(extra.getParent());
            Files.writeString(extra, extraSource, StandardCharsets.UTF_8);
            arguments.add(extra.toString());
        }
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        int exitCode = ToolProvider.getSystemJavaCompiler().run(null, null, errors,
                arguments.toArray(String[]::new));
        return new CompilationResult(exitCode, errors.toString(StandardCharsets.UTF_8));
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
