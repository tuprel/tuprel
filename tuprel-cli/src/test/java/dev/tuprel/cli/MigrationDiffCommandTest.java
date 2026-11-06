package dev.tuprel.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MigrationDiffCommandTest {
    @TempDir Path project;
    private static final String MODEL = "model Item { id UUID @id value String? }\n";
    private static final String CREATE = "CREATE TABLE \"app\".\"Item\" (\"id\" UUID PRIMARY KEY, \"value\" TEXT);\n";

    @BeforeEach void init() throws Exception { assertEquals(0, run("init").code()); Files.writeString(project.resolve("tuprel/schema.tuprel"), MODEL); }

    @Test void emptySourcePreviewsAndEqualityReturnsZeroWithoutChangingAnyFiles() throws Exception {
        Map<String, String> original = files();
        Result preview = diff(); assertEquals(1, preview.code()); assertTrue(preview.out().contains("REVIEW_REQUIRED"));
        assertTrue(preview.out().contains("PHYSICAL_STATE_NOT_CHECKED")); assertTrue(preview.out().contains("CREATE TABLE"));
        assertTrue(preview.out().contains("all local migration files")); assertTrue(preview.out().contains("tuprel/schema.tuprel"));
        assertEquals(original, files());
        migration("20261031_120000_initial", CREATE);
        original = files(); assertEquals(0, diff().code()); assertEquals(original, files());
    }

    @Test void removalsTypesAndUnknownTargetsBlockTheEntirePreview() throws Exception {
        migration("20261031_120000_initial", CREATE);
        for (String target : List.of("model Item { id UUID @id }", "model Item { id UUID @id value Int? }",
                "model Item { id UUID @id value String @default(\"private_secret\") }")) {
            Files.writeString(project.resolve("tuprel/schema.tuprel"), target);
            Result result = diff(); assertEquals(2, result.code());
            assertFalse(result.out().contains("CREATE TABLE")); assertFalse(result.out().contains("ALTER TABLE"));
            assertFalse(result.err().contains("private_secret"));
        }
    }

    @Test void unknownOrDestructiveMigrationAndDraftNeverExposePartialSource() throws Exception {
        for (String source : List.of(CREATE + "DROP TABLE \"app\".\"Item\";\n", "-- private_secret\n", "SELECT 'private_secret';\n")) {
            Path sql = migration("20261031_120000_initial", source);
            Result result = diff(); assertEquals(2, result.code()); assertTrue(result.err().contains("TUPREL-DIFF-SOURCE"));
            assertFalse(result.out().contains("CREATE TABLE")); assertFalse(result.err().contains("private_secret"));
            Files.delete(sql); Files.delete(sql.getParent());
        }
    }

    @Test void alteredFilesAreRediscoveredAndExecutionOrderIsUsed() throws Exception {
        Path later = migration("20261031_120001_add", "ALTER TABLE \"app\".\"Item\" ADD COLUMN \"value\" TEXT;\n");
        assertEquals(2, diff().code());
        migration("20261031_120000_initial", "CREATE TABLE \"app\".\"Item\" (\"id\" UUID PRIMARY KEY);\n");
        assertEquals(0, diff().code());
        Files.writeString(later, "ALTER TABLE \"app\".\"Item\" ADD COLUMN \"other\" TEXT;\n");
        Result changed = diff(); assertEquals(2, changed.code()); assertTrue(changed.out().contains("COLUMN_REMOVED"));
    }

    @Test void customUtf8SchemaAndInvalidSourceHaveSafeDiagnosticsAndStableCodes() throws Exception {
        Files.writeString(project.resolve("custom espaço.tuprel"), "// unicode π\r\n" + MODEL);
        assertEquals(1, run("migrate", "diff", "--schema", "custom espaço.tuprel", "--target-schema", "app").code());
        Files.writeString(project.resolve("tuprel/schema.tuprel"), "private_secret invalid {{");
        Result invalid = diff(); assertEquals(1, invalid.code()); assertFalse(invalid.err().contains("private_secret"));
        Files.write(project.resolve("tuprel/schema.tuprel"), new byte[]{(byte) 0xFF}); assertEquals(2, diff().code());
    }

    @Test void invalidOptionsPathsAndBudgetsAreRefused() throws Exception {
        for (String[] args : List.of(new String[]{"migrate", "diff"},
                new String[]{"migrate", "diff", "--target-schema", "app", "--dry-run"},
                new String[]{"migrate", "diff", "--target-schema", "app", "--target-schema", "app"},
                new String[]{"migrate", "diff", "--target-schema", "bad.name"},
                new String[]{"migrate", "diff", "--target-schema", "app", "--schema", "../schema.tuprel"},
                new String[]{"migrate", "diff", "--target-schema", "app", "--schema", "missing"},
                new String[]{"migrate", "diff", "--target-schema", "pg_catalog"})) {
            assertEquals(2, run(args).code());
        }
        Files.writeString(project.resolve("tuprel/schema.tuprel"), " ".repeat(1024 * 1024 + 1)); assertEquals(2, diff().code());
    }

    @Test void helpNeedsNoProjectOrFilesAndFutureCommandsStayUnavailable() {
        StringWriter out = new StringWriter();
        assertEquals(0, TuprelCli.run(new String[]{"migrate", "diff", "--help"}, new PrintWriter(out), new PrintWriter(new StringWriter()), project.resolve("missing")));
        assertTrue(out.toString().contains("always read-only"));
        assertEquals(2, run("migrate", "dev", "--name", "anything").code());
    }

    private Result diff() { return run("migrate", "diff", "--target-schema", "app"); }
    private Result run(String... args) {
        StringWriter out = new StringWriter(); StringWriter err = new StringWriter();
        int code = TuprelCli.run(args, new PrintWriter(out, true), new PrintWriter(err, true), project);
        return new Result(code, out.toString(), err.toString());
    }
    private Path migration(String name, String sql) throws Exception {
        return Files.writeString(Files.createDirectory(project.resolve("tuprel/migrations").resolve(name)).resolve("migration.sql"), sql);
    }
    private Map<String, String> files() throws Exception {
        Map<String, String> snapshot = new TreeMap<>();
        try (java.util.stream.Stream<Path> paths = Files.walk(project)) {
            for (Path file : paths.filter(Files::isRegularFile).toList()) { snapshot.put(project.relativize(file).toString(), Files.readString(file)); }
        }
        return snapshot;
    }
    private record Result(int code, String out, String err) { }
}
