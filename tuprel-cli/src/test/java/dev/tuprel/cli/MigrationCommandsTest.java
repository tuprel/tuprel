package dev.tuprel.cli;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuprel.migrate.MigrationCreator;
import dev.tuprel.migrate.MigrationDirectory;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MigrationCommandsTest {
    @TempDir Path root;
    private final MigrationCommands commands = new MigrationCommands(new MigrationCreator(
            Clock.fixed(Instant.parse("2026-10-30T12:00:00Z"), ZoneOffset.UTC)));

    @BeforeEach
    void canonical() throws Exception { root = root.toRealPath(); }

    @Test
    void globalAndNestedHelpAdvertiseOnlyImplementedCommandsWithoutAccessingFiles() throws Exception {
        for (String[] arguments : List.of(new String[]{"migrate", "--help"}, new String[]{"migrate", "create", "--help"},
                new String[]{"migrate", "create", "-h"})) {
            Result result = cli(root.resolve("missing"), arguments);
            assertEquals(0, result.code());
            assertTrue(result.out().contains("create"));
            assertFalse(result.out().contains("deploy"));
            assertEquals("", result.err());
        }
        assertTrue(cli(root, "--help").out().contains("migrate"));
        assertFalse(Files.exists(root.resolve("missing")));
    }

    @Test
    void initPreviewCreateAndLegacyCommandsRemainCompatible() throws Exception {
        assertEquals(0, cli(root, "init").code());
        Result preview = run("migrate", "create", "--dry-run", "--name", "add_products");
        assertEquals(0, preview.code());
        assertTrue(preview.out().contains("20261030_120000_add_products/migration.sql"));
        assertTrue(preview.out().contains("not reserved"));
        assertFalse(Files.exists(root.resolve("tuprel/.migrations.create.lock")));
        Result created = run("migrate", "create", "--name", "add_products");
        assertEquals(0, created.code());
        assertTrue(created.out().contains("Created draft:"));
        assertTrue(created.out().contains("No SQL was applied or approved"));
        assertEquals(1, MigrationDirectory.discover(root.resolve("tuprel/migrations")).size());
        for (String[] arguments : List.of(new String[]{"validate"}, new String[]{"format", "--check"},
                new String[]{"generate", "--dry-run"}, new String[]{"generate"}, new String[]{"generate", "--check"})) {
            assertEquals(0, cli(root, arguments).code());
        }
    }

    @Test
    void creationNeedsOnlyTheExistingCatalogAndNeverReadsSchemaOrEnvironment() throws Exception {
        Path migrations = Files.createDirectories(root.resolve("tuprel/migrations"));
        Files.writeString(migrations.resolve(MigrationDirectory.FORMAT_FILE), MigrationDirectory.FORMAT_HEADER);
        Files.writeString(root.resolve(".env"), "not a production configuration\n");
        assertEquals(0, cli(root, "migrate", "create", "--name", "initial").code());
        assertFalse(Files.exists(root.resolve("tuprel/schema.tuprel")));
        assertEquals("not a production configuration\n", Files.readString(root.resolve(".env")));
    }

    @Test
    void invalidOptionsAndFutureCommandsReturnUsageTwo() {
        for (String[] arguments : List.of(new String[]{"migrate"}, new String[]{"migrate", "deploy"},
                new String[]{"migrate", "status"}, new String[]{"migrate", "create"},
                new String[]{"migrate", "create", "--name"}, new String[]{"migrate", "create", "--name", "--dry-run"},
                new String[]{"migrate", "create", "--name", "a", "--name", "b"},
                new String[]{"migrate", "create", "--name", "a", "--dry-run", "--dry-run"},
                new String[]{"migrate", "create", "--name", "a", "--schema", "private_value"},
                new String[]{"migrate", "create", "--id", "20261030_120000", "--name", "a"})) {
            Result result = run(arguments);
            assertEquals(2, result.code());
            assertTrue(result.err().contains("TUPREL-CLI-USAGE"));
            assertFalse(result.err().contains("private_value"));
        }
        assertFalse(Files.exists(root.resolve("tuprel")));
    }

    @Test
    void invalidNameAndCollisionsAreSafeFailures() throws Exception {
        assertEquals(0, cli(root, "init").code());
        Result invalid = run("migrate", "create", "--name", "../private_value");
        assertEquals(2, invalid.code());
        assertTrue(invalid.err().contains("INVALID_NAME"));
        assertFalse(invalid.err().contains("private_value"));
        assertFalse(invalid.err().contains(root.toString()));
        assertEquals(0, run("migrate", "create", "--name", "first").code());
        Result collision = run("migrate", "create", "--name", "second");
        assertEquals(2, collision.code());
        assertTrue(collision.err().contains("COLLISION"));
        assertEquals("", collision.out());
    }

    @Test
    void missingOrInvalidCatalogDoesNotAutoInitializeOrLeakFileContents() throws Exception {
        Result missing = run("migrate", "create", "--name", "initial");
        assertEquals(2, missing.code());
        assertFalse(Files.exists(root.resolve("tuprel")));
        Path migrations = Files.createDirectories(root.resolve("tuprel/migrations"));
        Files.writeString(migrations.resolve(MigrationDirectory.FORMAT_FILE), "private_value\n");
        Result invalid = run("migrate", "create", "--name", "initial");
        assertEquals(2, invalid.code());
        assertFalse(invalid.err().contains("private_value"));
        assertTrue(invalid.err().contains("tuprel init"));
    }

    private Result run(String... arguments) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int code = commands.run(arguments, new PrintWriter(out, true), new PrintWriter(err, true), root);
        return new Result(code, out.toString(), err.toString());
    }
    private static Result cli(Path directory, String... arguments) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int code = TuprelCli.run(arguments, new PrintWriter(out, true), new PrintWriter(err, true), directory);
        return new Result(code, out.toString(), err.toString());
    }
    private record Result(int code, String out, String err) {}
}
