package dev.tuprel.migrate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MigrationDirectoryTest {
    @TempDir Path temporaryDirectory;

    @BeforeEach
    void useCanonicalFixtureRoot() throws IOException {
        temporaryDirectory = temporaryDirectory.toRealPath();
    }

    @Test
    void emptyVersionedCatalogIsValidAndDiscoveryDoesNotCreateAnything() throws Exception {
        Path root = root("empty");
        byte[] marker = Files.readAllBytes(root.resolve(MigrationDirectory.FORMAT_FILE));
        assertTrue(MigrationDirectory.discover(root).isEmpty());
        try (java.util.stream.Stream<Path> entries = Files.list(root)) {
            assertEquals(1L, entries.count());
        }
        assertEquals(new String(marker, StandardCharsets.UTF_8), Files.readString(root.resolve(MigrationDirectory.FORMAT_FILE)));
    }

    @Test
    void discoveryIsDeterministicAndSnapshotsSurviveFileChanges() throws Exception {
        Path root = root("ordered");
        migration(root, "20261030_120002_later", "SELECT 2;\n");
        Path first = migration(root, "20261030_120000_initial", "SELECT 1;\n");
        List<MigrationFile> before = MigrationDirectory.discover(root);
        assertEquals(List.of("initial", "later"), before.stream().map(MigrationFile::name).toList());
        assertThrows(UnsupportedOperationException.class, () -> before.clear());
        Files.writeString(first, "SELECT 9;\n");
        List<MigrationFile> after = MigrationDirectory.discover(root);
        assertNotEquals(before.get(0).checksum(), after.get(0).checksum());
        assertEquals("SELECT 1;\n", before.get(0).sql());
        Files.delete(first);
        assertReason(MigrationValidationException.Reason.INVALID_ENTRY, root);
    }

    @Test
    void duplicateTimestampIdsFailEvenWithDifferentNames() throws Exception {
        Path root = root("duplicates");
        migration(root, "20261030_120000_initial", "SELECT 1;\n");
        migration(root, "20261030_120000_other", "SELECT 2;\n");
        assertReason(MigrationValidationException.Reason.DUPLICATE_ID, root);
    }

    @Test
    void invalidRootAndTraversalAreRejected() throws Exception {
        Path root = root("catalog");
        assertReason(MigrationValidationException.Reason.INVALID_ROOT, root.resolve("..").resolve("catalog"));
        Path file = Files.writeString(temporaryDirectory.resolve("file"), "x");
        assertReason(MigrationValidationException.Reason.INVALID_ROOT, file);
        assertThrows(MigrationValidationException.class, () -> MigrationDirectory.discover(temporaryDirectory.resolve("missing")));
        assertFalse(Files.exists(temporaryDirectory.resolve("missing")));
    }

    @Test
    void rejectsNonlocalFilesystemProviders() throws Exception {
        java.net.URI archive = java.net.URI.create("jar:" + temporaryDirectory.resolve("catalog.zip").toUri());
        try (java.nio.file.FileSystem filesystem = java.nio.file.FileSystems.newFileSystem(
                archive, java.util.Map.of("create", "true"))) {
            assertReason(MigrationValidationException.Reason.INVALID_ROOT, filesystem.getPath("/"));
        }
    }

    @Test
    void markerIsRequiredAndUnknownVersionsAreRejected() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("unversioned"));
        assertReason(MigrationValidationException.Reason.UNSUPPORTED_FORMAT, root);
        Files.writeString(root.resolve(MigrationDirectory.FORMAT_FILE), "TUPREL-MIGRATIONS 2\n");
        assertReason(MigrationValidationException.Reason.UNSUPPORTED_FORMAT, root);
        Files.writeString(root.resolve(MigrationDirectory.FORMAT_FILE), "TUPREL-MIGRATIONS 1\r\n");
        assertReason(MigrationValidationException.Reason.UNSUPPORTED_FORMAT, root);
    }

    @Test
    void unknownEntriesAndCaseAliasesFailClosed() throws Exception {
        Path root = root("unexpected");
        Path sql = migration(root, "20261030_120000_initial", "SELECT 1;\n");
        Path extra = Files.writeString(sql.getParent().resolve("extra.sql"), "SELECT 2;\n");
        assertReason(MigrationValidationException.Reason.INVALID_ENTRY, root);
        Files.delete(extra);
        Path intermediate = Files.move(sql, sql.resolveSibling("rename.tmp"));
        Files.move(intermediate, sql.resolveSibling("MIGRATION.SQL"));
        assertReason(MigrationValidationException.Reason.INVALID_ENTRY, root);
    }

    @Test
    void invalidBytesProduceSafeDiagnostics() throws Exception {
        Path root = root("invalid");
        migration(root, "20261030_120000_initial", "sensitive_value\r\n");
        MigrationValidationException failure = assertThrows(MigrationValidationException.class,
                () -> MigrationDirectory.discover(root));
        assertEquals(MigrationValidationException.Reason.INVALID_SQL, failure.reason());
        assertFalse(failure.getMessage().contains("sensitive_value"));
        assertFalse(failure.getMessage().contains(root.toString()));
    }

    @Test
    void enforcesFileAndTotalByteBudgets() throws Exception {
        Path root = root("sizes");
        Path sql = migration(root, "20261030_120000_initial", "x\n");
        Files.write(sql, new byte[MigrationFile.MAX_SQL_BYTES + 1]);
        assertReason(MigrationValidationException.Reason.RESOURCE_LIMIT, root);
        Files.delete(sql);
        Files.delete(sql.getParent());
        String content = "x".repeat(MigrationFile.MAX_SQL_BYTES - 1) + "\n";
        for (int index = 0; index < 17; index++) {
            migration(root, "20261030_1200%02d_large".formatted(index), content);
        }
        assertReason(MigrationValidationException.Reason.RESOURCE_LIMIT, root);
    }

    @Test
    void enforcesMigrationCountBudget() throws Exception {
        Path root = root("count");
        LocalDateTime time = LocalDateTime.of(2026, 10, 30, 0, 0);
        DateTimeFormatter format = DateTimeFormatter.ofPattern("uuuuMMdd_HHmmss");
        for (int index = 0; index <= MigrationDirectory.MAX_MIGRATIONS; index++) {
            migration(root, time.plusSeconds(index).format(format) + "_initial", "x\n");
        }
        assertReason(MigrationValidationException.Reason.RESOURCE_LIMIT, root);
    }

    @Test
    void rejectsSymlinksThroughoutCatalog() throws Exception {
        Path root = root("links");
        Path external = Files.createDirectory(temporaryDirectory.resolve("external"));
        link(root.resolve("20261030_120000_initial"), external);
        assertReason(MigrationValidationException.Reason.INVALID_ENTRY, root);
        Files.delete(root.resolve("20261030_120000_initial"));
        Path sql = migration(root, "20261030_120000_initial", "x\n");
        Files.delete(sql);
        Path outside = Files.writeString(external.resolve("outside.sql"), "SELECT 'private';\n");
        Files.createSymbolicLink(sql, outside);
        assertReason(MigrationValidationException.Reason.INVALID_ENTRY, root);
        Path alias = temporaryDirectory.resolve("alias");
        Files.createSymbolicLink(alias, root);
        assertReason(MigrationValidationException.Reason.INVALID_ROOT, alias);
        assertReason(MigrationValidationException.Reason.INVALID_ROOT, alias.resolve("nested"));
        Path markerRoot = root("marker-link");
        Files.delete(markerRoot.resolve(MigrationDirectory.FORMAT_FILE));
        Files.createSymbolicLink(markerRoot.resolve(MigrationDirectory.FORMAT_FILE), outside);
        assertReason(MigrationValidationException.Reason.INVALID_ENTRY, markerRoot);
        assertEquals("SELECT 'private';\n", Files.readString(outside));
    }

    private Path root(String name) throws IOException {
        Path root = Files.createDirectory(temporaryDirectory.resolve(name));
        Files.writeString(root.resolve(MigrationDirectory.FORMAT_FILE), MigrationDirectory.FORMAT_HEADER);
        return root;
    }

    private static Path migration(Path root, String name, String sql) throws IOException {
        Path directory = Files.createDirectory(root.resolve(name));
        return Files.writeString(directory.resolve("migration.sql"), sql, StandardCharsets.UTF_8);
    }

    private static void assertReason(MigrationValidationException.Reason expected, Path root) {
        assertEquals(expected, assertThrows(MigrationValidationException.class,
                () -> MigrationDirectory.discover(root)).reason());
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
}
