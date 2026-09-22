package dev.tuprel.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
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
}
