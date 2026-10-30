package dev.tuprel.migrate;

import static dev.tuprel.migrate.MigrationCreationException.Reason;
import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MigrationCreatorTest {
    static final Instant NOW = Instant.parse("2026-10-30T23:59:58Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneId.of("Pacific/Auckland"));
    @TempDir Path temporary;
    Path root;

    @BeforeEach
    void catalog() throws IOException {
        temporary = temporary.toRealPath();
        root = Files.createDirectory(temporary.resolve("migrations"));
        Files.writeString(root.resolve(MigrationDirectory.FORMAT_FILE), MigrationDirectory.FORMAT_HEADER);
    }

    @Test
    void createsCanonicalUtcCommentOnlyDraftAndLeavesMarkerUntouched() throws Exception {
        Path marker = root.resolve(MigrationDirectory.FORMAT_FILE);
        byte[] before = Files.readAllBytes(marker);
        java.nio.file.attribute.FileTime modified = Files.getLastModifiedTime(marker);
        MigrationFile file = creator().create(root, "add_products-v2");
        assertEquals("20261030_235958_add_products-v2", file.directoryName());
        byte[] bytes = Files.readAllBytes(root.resolve(file.directoryName()).resolve("migration.sql"));
        assertEquals(file.sql(), new String(bytes, StandardCharsets.UTF_8));
        assertTrue(file.sql().lines().allMatch(line -> line.startsWith("-- ")));
        assertTrue(file.sql().endsWith("\n"));
        assertFalse(file.sql().contains("\r"));
        assertEquals(MigrationChecksum.of(bytes), file.checksum());
        assertEquals(List.of(file.directoryName()), MigrationDirectory.discover(root).stream().map(MigrationFile::directoryName).toList());
        assertArrayEquals(before, Files.readAllBytes(marker));
        assertEquals(modified, Files.getLastModifiedTime(marker));
        assertEquals(0, Files.size(lock()));
    }

    @Test
    void dryRunDoesNotWriteOrReserveAnything() throws Exception {
        MigrationFile preview = creator().preview(root, "initial");
        assertEquals(1, entries(root));
        assertFalse(Files.exists(lock()));
        assertEquals(preview.directoryName(), creator().create(root, "initial").directoryName());
    }

    @Test
    void rejectsInvalidNamesWithoutNormalizingOrEchoingThem() throws Exception {
        for (String name : List.of("", "Add_products", "add products", "a__b", "a--b", "-a", "a_", "1st",
                "../private_value", "a/b", "a\\b", "a:b", "é", "a".repeat(65))) {
            MigrationCreationException failure = assertThrows(MigrationCreationException.class, () -> creator().create(root, name));
            assertEquals(Reason.INVALID_NAME, failure.reason());
            assertFalse(failure.getMessage().contains("private_value"));
        }
        assertFalse(Files.exists(lock()));
        assertEquals(1, entries(root));
    }

    @Test
    void refusesDuplicateIdsRegardlessOfNameAndNeverOverwritesEdits() throws Exception {
        MigrationFile first = creator().create(root, "initial");
        Path sql = root.resolve(first.directoryName()).resolve("migration.sql");
        Files.writeString(sql, "-- manually reviewed contents\n");
        assertReason(Reason.COLLISION, () -> creator().create(root, "different"));
        assertReason(Reason.COLLISION, () -> creator().preview(root, "initial"));
        assertEquals("-- manually reviewed contents\n", Files.readString(sql));
        MigrationFile next = new MigrationCreator(Clock.fixed(NOW.plusSeconds(1), ZoneOffset.UTC)).create(root, "next");
        assertEquals("20261030_235959", next.id().value());
    }

    @Test
    void rejectsClockRegressionAndOutOfFormatYears() throws Exception {
        creator().create(root, "initial");
        assertReason(Reason.CLOCK_ORDER,
                () -> new MigrationCreator(Clock.fixed(NOW.minusSeconds(1), ZoneOffset.UTC)).create(root, "earlier"));
        assertReason(Reason.CLOCK_ORDER,
                () -> new MigrationCreator(Clock.fixed(Instant.parse("+10000-01-01T00:00:00Z"), ZoneOffset.UTC)).preview(root, "future"));
    }

    @Test
    void concurrentCreatorsInOneJvmCannotProduceDuplicateIds() throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        CompletableFuture<String> first = contender(barrier, "first");
        CompletableFuture<String> second = contender(barrier, "second");
        List<String> results = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        assertEquals(1, results.stream().filter("created"::equals).count());
        assertTrue(results.stream().anyMatch(value -> value.equals("BUSY") || value.equals("COLLISION")));
        assertEquals(1, MigrationDirectory.discover(root).size());
    }

    @Test
    void processLockExcludesAnotherJvmAndReleasesAfterClose() throws Exception {
        String separator = System.getProperty("path.separator");
        String classpath = Path.of(MigrationCreator.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                + separator + Path.of(MigrationCreatorProcess.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", classpath, MigrationCreatorProcess.class.getName(), root.toString()).redirectErrorStream(true).start();
        BufferedReader output = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        try {
            CompletableFuture<String> ready = CompletableFuture.supplyAsync(() -> {
                try { return output.readLine(); } catch (IOException exception) { throw new java.io.UncheckedIOException(exception); }
            });
            assertEquals("READY", ready.get(15, TimeUnit.SECONDS));
            assertReason(Reason.BUSY, () -> creator().create(root, "contender"));
            process.getOutputStream().write('\n');
            process.getOutputStream().flush();
            assertTrue(process.waitFor(15, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue(), () -> output.lines().reduce("", (a, b) -> a + b));
        } finally {
            process.destroyForcibly();
            process.waitFor(15, TimeUnit.SECONDS);
            output.close();
        }
        assertEquals(1, MigrationDirectory.discover(root).size());
        new MigrationCreator(Clock.fixed(NOW.plusSeconds(1), ZoneOffset.UTC)).create(root, "after_unlock");
        assertEquals(2, MigrationDirectory.discover(root).size());
    }

    @Test
    void partialWriteIsPreservedAndBlocksFurtherCreationUntilInspected() throws Exception {
        MigrationCreator failing = new MigrationCreator(CLOCK, (path, bytes) -> {
            Files.writeString(path, "-- partial", StandardOpenOption.CREATE_NEW);
            throw new IOException("private_value");
        });
        MigrationCreationException failure = assertThrows(MigrationCreationException.class, () -> failing.create(root, "partial"));
        assertEquals(Reason.PARTIAL_CREATION, failure.reason());
        assertFalse(failure.getMessage().contains("private_value"));
        assertEquals("-- partial", Files.readString(root.resolve("20261030_235958_partial/migration.sql")));
        assertThrows(MigrationValidationException.class, () -> creator().create(root, "retry"));
    }

    @Test
    void failureBeforeFileWriteLeavesDirectoryAndFailsClosed() throws Exception {
        MigrationCreator failing = new MigrationCreator(CLOCK, (path, bytes) -> { throw new IOException("denied"); });
        assertReason(Reason.PARTIAL_CREATION, () -> failing.create(root, "partial"));
        assertTrue(Files.isDirectory(root.resolve("20261030_235958_partial")));
        assertThrows(MigrationValidationException.class, () -> creator().create(root, "retry"));
    }

    @Test
    void fullyWrittenFileFollowedByIoFailureIsReportedForInspection() throws Exception {
        MigrationCreator failing = new MigrationCreator(CLOCK, (path, bytes) -> {
            Files.write(path, bytes, StandardOpenOption.CREATE_NEW);
            throw new IOException("close failed");
        });
        assertReason(Reason.PARTIAL_CREATION, () -> failing.create(root, "inspect"));
        // Format validity cannot establish whether an earlier process confirmed success.
        assertEquals(1, MigrationDirectory.discover(root).size());
        assertReason(Reason.COLLISION, () -> creator().create(root, "retry"));
    }

    @Test
    void exclusiveFileCreationPreservesAFileIntroducedAfterDirectoryAllocation() throws Exception {
        MigrationCreator competingWriter = new MigrationCreator(CLOCK, (path, bytes) -> {
            Files.writeString(path, "-- existing editor contents\n", StandardOpenOption.CREATE_NEW);
            Files.write(path, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        });
        assertReason(Reason.PARTIAL_CREATION, () -> competingWriter.create(root, "inspect"));
        assertEquals("-- existing editor contents\n", Files.readString(root.resolve("20261030_235958_inspect/migration.sql")));
    }

    @Test
    void refusesUnversionedTraversalAndMalformedCatalogsWithoutRepair() throws Exception {
        assertThrows(MigrationValidationException.class, () -> creator().create(root.resolve("..").resolve("migrations"), "initial"));
        Files.writeString(root.resolve(MigrationDirectory.FORMAT_FILE), "TUPREL-MIGRATIONS 2\n");
        assertThrows(MigrationValidationException.class, () -> creator().create(root, "initial"));
        assertFalse(Files.exists(lock()));
        Files.delete(root.resolve(MigrationDirectory.FORMAT_FILE));
        assertThrows(MigrationValidationException.class, () -> creator().create(root, "initial"));
        assertEquals(0, entries(root));
    }

    @Test
    void refusesNonemptyOrDirectoryCoordinationPathsWithoutChangingThem() throws Exception {
        Files.writeString(lock(), "private_value");
        assertReason(Reason.UNSAFE_LOCK, () -> creator().create(root, "initial"));
        assertReason(Reason.UNSAFE_LOCK, () -> creator().preview(root, "initial"));
        assertEquals("private_value", Files.readString(lock()));
        Files.delete(lock());
        Files.createDirectory(lock());
        assertReason(Reason.UNSAFE_LOCK, () -> creator().create(root, "initial"));
        assertEquals(1, entries(root));
    }

    @Test
    void refusesReadOnlyCoordinationFile() throws Exception {
        Files.createFile(lock());
        if (System.getProperty("os.name").startsWith("Windows")) {
            DosFileAttributeView view = Files.getFileAttributeView(lock(), DosFileAttributeView.class);
            view.setReadOnly(true);
            try {
                assertThrows(IOException.class, () -> creator().create(root, "initial"));
            } finally { view.setReadOnly(false); }
        } else {
            Set<PosixFilePermission> original = Files.getPosixFilePermissions(lock());
            Files.setPosixFilePermissions(lock(), Set.of(PosixFilePermission.OWNER_READ));
            try {
                assertThrows(IOException.class, () -> creator().create(root, "initial"));
            } finally { Files.setPosixFilePermissions(lock(), original); }
        }
        assertEquals(1, entries(root));
    }

    @Test
    void directoryPermissionFailureDoesNotAllocateAMigration() throws Exception {
        if (System.getProperty("os.name").startsWith("Windows")) {
            AclFileAttributeView view = Files.getFileAttributeView(root, AclFileAttributeView.class);
            List<AclEntry> original = view.getAcl();
            List<AclEntry> denied = new ArrayList<>(original);
            denied.addFirst(AclEntry.newBuilder().setType(AclEntryType.DENY).setPrincipal(Files.getOwner(root))
                    .setPermissions(AclEntryPermission.WRITE_DATA, AclEntryPermission.APPEND_DATA).build());
            view.setAcl(denied);
            try { assertThrows(IOException.class, () -> creator().create(root, "initial")); }
            finally { view.setAcl(original); }
        } else {
            Set<PosixFilePermission> original = Files.getPosixFilePermissions(root);
            Files.setPosixFilePermissions(root, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE));
            try { assertThrows(IOException.class, () -> creator().create(root, "initial")); }
            finally { Files.setPosixFilePermissions(root, original); }
        }
        assertEquals(1, entries(root));
    }

    @Test
    void rejectsSymlinksForCreationAndCoordination() throws Exception {
        Path external = Files.createDirectory(temporary.resolve("external"));
        Path alias = temporary.resolve("alias");
        try {
            Files.createSymbolicLink(alias, root);
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            if (System.getProperty("os.name").startsWith("Windows")) {
                Assumptions.abort("Windows symlink privilege unavailable; Linux CI requires this test");
            }
            throw exception;
        }
        assertThrows(MigrationValidationException.class, () -> creator().create(alias, "initial"));
        Path target = Files.writeString(external.resolve("private.txt"), "private_value");
        Files.createSymbolicLink(lock(), target);
        assertReason(Reason.UNSAFE_LOCK, () -> creator().create(root, "initial"));
        assertReason(Reason.UNSAFE_LOCK, () -> creator().preview(root, "initial"));
        assertEquals("private_value", Files.readString(target));
        Files.delete(lock());
        Files.createSymbolicLink(root.resolve("20261030_235958_initial"), external);
        assertThrows(MigrationValidationException.class, () -> creator().create(root, "initial"));
        assertEquals(1, entries(external));
    }

    @Test
    void creationRespectsCountAndTotalByteBudgets() throws Exception {
        for (int index = 0; index < MigrationDirectory.MAX_MIGRATIONS; index++) {
            String id = java.time.LocalDateTime.of(2026, 1, 1, 0, 0).plusSeconds(index)
                    .format(java.time.format.DateTimeFormatter.ofPattern("uuuuMMdd_HHmmss"));
            Path directory = Files.createDirectory(root.resolve(id + "_old"));
            Files.writeString(directory.resolve("migration.sql"), "-- draft\n");
        }
        assertReason(Reason.CAPACITY, () -> creator().preview(root, "initial"));
        Path large = Files.createDirectory(temporary.resolve("large"));
        Files.writeString(large.resolve(MigrationDirectory.FORMAT_FILE), MigrationDirectory.FORMAT_HEADER);
        String content = "x".repeat(MigrationFile.MAX_SQL_BYTES - 1) + "\n";
        for (int index = 0; index < 16; index++) {
            Path directory = Files.createDirectory(large.resolve("20260101_0000%02d_old".formatted(index)));
            Files.writeString(directory.resolve("migration.sql"), content);
        }
        assertReason(Reason.CAPACITY, () -> creator().create(large, "initial"));
        assertEquals(17, entries(large));
    }

    private CompletableFuture<String> contender(CyclicBarrier barrier, String name) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                barrier.await(10, TimeUnit.SECONDS);
                creator().create(root, name);
                return "created";
            } catch (MigrationCreationException exception) {
                return exception.reason().name();
            } catch (Exception exception) {
                throw new java.util.concurrent.CompletionException(exception);
            }
        });
    }

    private static MigrationCreator creator() { return new MigrationCreator(CLOCK); }
    private Path lock() { return root.resolveSibling(".migrations.create.lock"); }
    private static long entries(Path path) throws IOException {
        try (java.util.stream.Stream<Path> files = Files.list(path)) { return files.count(); }
    }
    private static void assertReason(Reason expected, IoAction action) {
        assertEquals(expected, assertThrows(MigrationCreationException.class, action::run).reason());
    }
    @FunctionalInterface private interface IoAction { void run() throws IOException; }
}
