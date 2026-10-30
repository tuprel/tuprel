package dev.tuprel.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuprel.migrate.MigrationDirectory;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CliExperienceTest {
    @TempDir Path project;

    @BeforeEach
    void useCanonicalFixtureRoot() throws IOException {
        project = project.toRealPath();
    }

    @Test
    void globalHelpAndVersionAreReadOnlyPlainText() throws Exception {
        Result help = run("--help");
        Result version = run("--version");
        assertEquals(0, help.code());
        assertEquals(0, version.code());
        assertTrue(help.out().contains("Commands: init, validate, format, generate"));
        assertFalse(help.out().contains("migrate deploy"));
        assertFalse(help.out().contains("\u001B"));
        assertTrue(help.err().isEmpty());
        assertEquals("tuprel development", version.out().strip());
        try (java.util.stream.Stream<Path> entries = Files.list(project)) { assertEquals(0L, entries.count()); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"init", "validate", "format", "generate"})
    void eachImplementedCommandHasHelpWithoutRequiringSchema(String command) {
        Result result = run(command, "--help");
        assertEquals(0, result.code());
        assertTrue(result.out().contains("Usage: tuprel " + command));
        assertTrue(result.err().isEmpty());
        assertFalse(Files.exists(project.resolve("tuprel")));
    }

    @Test
    void initPreviewsThenCreatesMinimalUsableProjectWithoutChangingBuildOrSources() throws Exception {
        Path pom = Files.writeString(project.resolve("pom.xml"), "existing-build\n");
        Path javaSources = Files.createDirectories(project.resolve("src/main/java"));
        Files.writeString(javaSources.resolve("Existing.java"), "class Existing {}\n");
        assertEquals(0, run("init", "--dry-run").code());
        assertFalse(Files.exists(project.resolve("tuprel")));
        assertEquals(0, run("init").code());
        assertTrue(MigrationDirectory.discover(project.resolve("tuprel/migrations")).isEmpty());
        assertEquals(0, run("validate").code());
        assertEquals(0, run("format", "--check").code());
        assertEquals(0, run("generate", "--dry-run").code());
        assertFalse(Files.exists(project.resolve("build")));
        assertEquals(1, run("generate", "--check").code());
        assertEquals(0, run("generate").code());
        assertEquals(0, run("generate", "--check").code());
        byte[] before = Files.readAllBytes(project.resolve("tuprel/schema.tuprel"));
        assertEquals(2, run("init").code());
        assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(project.resolve("tuprel/schema.tuprel"))));
        assertEquals("existing-build\n", Files.readString(pom));
        assertEquals("class Existing {}\n", Files.readString(javaSources.resolve("Existing.java")));
        assertFalse(Files.exists(project.resolve(".env")));
    }

    @Test
    void initRefusesCaseFoldedExistingPathAndNeverChangesIt() throws Exception {
        Path occupied = Files.writeString(project.resolve("TUPREL"), "keep\n");
        assertEquals(2, run("init", "--dry-run").code());
        assertEquals(2, run("init").code());
        assertEquals("keep\n", Files.readString(occupied));
    }

    @Test
    void concurrentInitializersHaveExactlyOneWinner() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Integer> first = executor.submit(() -> { start.await(); return run("init").code(); });
            Future<Integer> second = executor.submit(() -> { start.await(); return run("init").code(); });
            start.countDown();
            assertEquals(List.of(0, 2), List.of(first.get(), second.get()).stream().sorted().toList());
        }
        assertEquals(0, run("validate").code());
        assertTrue(MigrationDirectory.discover(project.resolve("tuprel/migrations")).isEmpty());
    }

    @Test
    void explicitAndPositionalSchemaPathsWorkWithSpacesAndLiteralDash() throws Exception {
        Path schema = Files.writeString(project.resolve("models with spaces.tuprel"), "model User{ id Int @id }\n");
        assertEquals(0, run("validate", "--schema", schema.getFileName().toString()).code());
        assertEquals(0, run("validate", schema.toString()).code());
        assertEquals(1, run("format", "--schema", schema.toString(), "--check").code());
        assertEquals(0, run("format", "--dry-run", "--schema", schema.toString()).code());
        assertEquals("model User{ id Int @id }\n", Files.readString(schema));
        assertEquals(0, run("format", "--schema", schema.toString()).code());
        assertEquals(0, run("format", schema.toString(), "--check").code());
        Files.writeString(project.resolve("-schema.tuprel"), Files.readString(schema));
        assertEquals(0, run("validate", "--", "-schema.tuprel").code());
    }

    @Test
    void invalidArgumentsAndUnimplementedCommandsHaveNoSideEffects() throws Exception {
        List<String[]> invalid = List.of(new String[] {}, new String[] {"--help", "extra"},
                new String[] {"--version", "extra"}, new String[] {"migrate", "deploy"}, new String[] {"doctor"},
                new String[] {"init", "--schema", "other"}, new String[] {"validate", "--dry-run"},
                new String[] {"format", "--check", "--dry-run"}, new String[] {"format", "--dry-run", "--dry-run"},
                new String[] {"generate", "--schema"}, new String[] {"generate", "--schema", "a", "b"},
                new String[] {"format", "--schema", "a", "--schema", "b"}, new String[] {"validate", "--help", "extra"});
        for (String[] arguments : invalid) {
            assertEquals(2, run(arguments).code());
        }
        try (java.util.stream.Stream<Path> entries = Files.list(project)) { assertEquals(0L, entries.count()); }
    }

    @Test
    void diagnosticsAndUsageNeverEchoSchemaValuesOrSensitiveArguments() throws Exception {
        String privateValue = "privateCredentialSentinel";
        Path schema = Files.writeString(project.resolve("schema.tuprel"), "model User { id " + privateValue + " @id }\n");
        Result invalid = run("validate", schema.toString());
        assertEquals(1, invalid.code());
        assertTrue(invalid.err().contains("TUPREL-SCHEMA-SEM-004"));
        assertTrue(invalid.err().contains("1:"));
        assertFalse(invalid.err().contains(privateValue));
        assertFalse(run("--password=" + privateValue).err().contains(privateValue));
        assertFalse(run("validate", privateValue).err().contains(privateValue));
        Files.writeString(schema, "datasource db { provider = \"postgresql\" url = \"jdbc:postgresql://localhost/dev?password="
                + privateValue + "\" }\nmodel User { id UnknownType @id }\n");
        Result connectionLiteral = run("validate", schema.toString());
        assertEquals(1, connectionLiteral.code());
        assertFalse(connectionLiteral.err().contains(privateValue));
        assertFalse(connectionLiteral.err().contains("jdbc:postgresql"));
        assertEquals(2, run("validate", "bad\0path").code());
    }

    @Test
    void generatorDryRunRefusesEditedOutputWithoutWriting() throws Exception {
        assertEquals(0, run("init").code());
        assertEquals(0, run("generate").code());
        Path model = project.resolve("build/generated/sources/tuprel/main/dev/example/tuprel/model/User.java");
        Files.writeString(model, "// private edit\n");
        Result result = run("generate", "--dry-run");
        assertEquals(2, result.code());
        assertFalse(result.err().contains("private edit"));
        assertEquals("// private edit\n", Files.readString(model));
    }

    @Test
    void rejectsSymlinksForInitAndSchemaWrites() throws Exception {
        Path external = Files.createDirectory(project.resolve("external"));
        link(project.resolve("tuprel"), external);
        assertEquals(2, run("init").code());
        assertFalse(Files.exists(external.resolve("schema.tuprel")));
        Files.delete(project.resolve("tuprel"));
        Path schema = Files.writeString(external.resolve("schema.tuprel"), "model User{ id Int @id }\n");
        Path alias = project.resolve("alias.tuprel");
        Files.createSymbolicLink(alias, schema);
        assertEquals(2, run("format", alias.toString()).code());
        assertEquals("model User{ id Int @id }\n", Files.readString(schema));
    }

    private Result run(String... arguments) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int code = TuprelCli.run(arguments, new PrintWriter(out, true), new PrintWriter(err, true), project);
        return new Result(code, out.toString(), err.toString());
    }

    private static void link(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            if (System.getProperty("os.name").startsWith("Windows")) {
                Assumptions.abort("Windows symlink privilege unavailable; Linux CI requires this test");
            }
            throw exception;
        }
    }

    private record Result(int code, String out, String err) {}
}
