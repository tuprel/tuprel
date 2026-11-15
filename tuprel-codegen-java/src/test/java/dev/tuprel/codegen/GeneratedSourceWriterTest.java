package dev.tuprel.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;

class GeneratedSourceWriterTest {
    @TempDir Path temporaryDirectory;

    @Test
    void writesChecksAndCleansOnlyOwnedUnmodifiedFiles() throws IOException {
        Path root = temporaryDirectory.resolve("generated");
        GeneratedSourceWriter writer = new GeneratedSourceWriter();
        GeneratedJavaSources first = new GeneratedJavaSources(Map.of("p/A.java", "class A {}\n"));
        GeneratedJavaSources second = new GeneratedJavaSources(Map.of("p/B.java", "class B {}\n"));
        assertFalse(writer.isCurrent(root, first));
        writer.write(root, first);
        assertTrue(writer.isCurrent(root, first));
        Files.writeString(root.resolve("user.txt"), "handwritten", StandardCharsets.UTF_8);
        writer.write(root, second);
        assertFalse(Files.exists(root.resolve("p/A.java")));
        assertTrue(writer.isCurrent(root, second));
        assertEquals("handwritten", Files.readString(root.resolve("user.txt")));
    }

    @Test
    void refusesForeignFilesAndManualEdits() throws IOException {
        Path root = temporaryDirectory.resolve("generated");
        Path file = root.resolve("p/A.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "handwritten", StandardCharsets.UTF_8);
        GeneratedSourceWriter writer = new GeneratedSourceWriter();
        GeneratedJavaSources source = new GeneratedJavaSources(Map.of("p/A.java", "class A {}\n"));
        assertThrows(IOException.class, () -> writer.write(root, source));
        assertEquals("handwritten", Files.readString(file));
        Files.delete(file);
        writer.write(root, source);
        Files.writeString(file, "manually edited", StandardCharsets.UTF_8);
        assertFalse(writer.isCurrent(root, source));
        assertThrows(IOException.class, () -> writer.write(root, source));
        assertEquals("manually edited", Files.readString(file));
    }

    @Test
    void rejectsTraversalAbsoluteAndCaseCollidingPaths() {
        Path root = temporaryDirectory.resolve("generated");
        GeneratedSourceWriter writer = new GeneratedSourceWriter();
        assertThrows(IOException.class, () -> writer.write(root,
                new GeneratedJavaSources(Map.of("../Escape.java", "x"))));
        assertThrows(IOException.class, () -> writer.write(root,
                new GeneratedJavaSources(Map.of("C:\\Escape.java", "x"))));
        assertThrows(IOException.class, () -> writer.write(root,
                new GeneratedJavaSources(Map.of("p/A.java", "x", "p/a.java", "y"))));
        assertThrows(IOException.class, () -> writer.write(root,
                new GeneratedJavaSources(Map.of("p//A.java", "x"))));
        assertFalse(Files.exists(root));
    }

    @Test
    void rejectsSymlinkInsideOutputTree() throws IOException {
        Path root = temporaryDirectory.resolve("generated");
        Path external = temporaryDirectory.resolve("external");
        Files.createDirectories(root);
        Files.createDirectories(external);
        try {
            Files.createSymbolicLink(root.resolve("p"), external);
        } catch (UnsupportedOperationException | IOException | SecurityException exception) {
            Assumptions.abort("Symbolic links unavailable in this test environment");
        }
        GeneratedJavaSources source = new GeneratedJavaSources(Map.of("p/A.java", "class A {}\n"));
        assertThrows(IOException.class, () -> new GeneratedSourceWriter().write(root, source));
        assertFalse(Files.exists(external.resolve("A.java")));
    }

    @Test
    void dryRunReusesOwnershipRulesWithoutCreatingOrChangingFiles() throws IOException {
        Path root = temporaryDirectory.resolve("preview");
        GeneratedSourceWriter writer = new GeneratedSourceWriter();
        GeneratedJavaSources source = new GeneratedJavaSources(Map.of("p/A.java", "class A {}\n"));
        assertTrue(writer.wouldChange(root, source));
        assertFalse(Files.exists(root));
        writer.write(root, source);
        assertFalse(writer.wouldChange(root, source));
        Files.writeString(root.resolve("p/A.java"), "private edit\n");
        assertThrows(IOException.class, () -> writer.wouldChange(root, source));
        assertEquals("private edit\n", Files.readString(root.resolve("p/A.java")));
    }

    @Test
    void partialWriteDoesNotGrantOwnershipAndRequiresExplicitCleanupBeforeRetry() throws IOException {
        Path root = Files.createDirectory(temporaryDirectory.resolve("partial"));
        Path obstruction = Files.writeString(root.resolve("z"), "keep\n", StandardCharsets.UTF_8);
        Path firstSource = root.resolve("p/A.java");
        Path manifest = root.resolve(".tuprel-generated");
        GeneratedSourceWriter writer = new GeneratedSourceWriter();
        GeneratedJavaSources sources = new GeneratedJavaSources(
                Map.of("p/A.java", "class A {}\n", "z/B.java", "class B {}\n"));

        assertThrows(IOException.class, () -> writer.write(root, sources));
        assertEquals("class A {}\n", Files.readString(firstSource));
        assertEquals("keep\n", Files.readString(obstruction));
        assertFalse(Files.exists(manifest));

        Files.delete(obstruction);
        assertThrows(IOException.class, () -> writer.write(root, sources));
        assertEquals("class A {}\n", Files.readString(firstSource));
        assertFalse(Files.exists(manifest));

        Files.delete(firstSource);
        writer.write(root, sources);
        assertTrue(writer.isCurrent(root, sources));
        String ownership = Files.readString(manifest);
        writer.write(root, sources);
        assertEquals(ownership, Files.readString(manifest));
        assertTrue(writer.isCurrent(root, sources));
    }

    @Test
    void writeDenialCannotCreateAnOwnedFileAndRestoredPermissionsAllowRetry() throws IOException {
        Path root = temporaryDirectory.resolve("denied");
        Path directory = Files.createDirectories(root.resolve("p"));
        GeneratedSourceWriter writer = new GeneratedSourceWriter();
        GeneratedJavaSources sources = new GeneratedJavaSources(Map.of("p/A.java", "class A {}\n"));

        if (System.getProperty("os.name").startsWith("Windows")) {
            AclFileAttributeView view = Files.getFileAttributeView(directory, AclFileAttributeView.class);
            List<AclEntry> original = view.getAcl();
            List<AclEntry> denied = new ArrayList<>(original);
            denied.add(0, AclEntry.newBuilder().setType(AclEntryType.DENY).setPrincipal(Files.getOwner(directory))
                    .setPermissions(AclEntryPermission.WRITE_DATA, AclEntryPermission.APPEND_DATA).build());
            view.setAcl(denied);
            try {
                assertThrows(IOException.class, () -> writer.write(root, sources));
            } finally {
                view.setAcl(original);
            }
        } else {
            Set<PosixFilePermission> original = Files.getPosixFilePermissions(directory);
            Files.setPosixFilePermissions(directory, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE));
            try {
                assertThrows(IOException.class, () -> writer.write(root, sources));
            } finally {
                Files.setPosixFilePermissions(directory, original);
            }
        }

        assertFalse(Files.exists(root.resolve("p/A.java")));
        assertFalse(Files.exists(root.resolve(".tuprel-generated")));
        writer.write(root, sources);
        assertTrue(writer.isCurrent(root, sources));
    }
}
