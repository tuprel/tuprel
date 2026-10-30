package dev.tuprel.testsupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalTestEnvironmentTest {
    @TempDir
    Path directory;

    @Test
    void preservesLiteralSpecialCharactersAndQuotedWhitespaceWithoutEvaluatingAnything() throws IOException {
        Path file = directory.resolve("fixture.env");
        Files.writeString(file, """
                \uFEFF# configuration

                TUPREL_TEST_DB_USER = 'test user'
                TUPREL_TEST_DB_PASSWORD="  fixture#=$value\\path  "
                """);
        LocalTestEnvironment configuration = LocalTestEnvironment.load(file, Map.of());
        assertEquals("test user", configuration.required("TUPREL_TEST_DB_USER"));
        assertEquals("  fixture#=$value\\path  ", configuration.required("TUPREL_TEST_DB_PASSWORD"));
        assertFalse(configuration.toString().contains("fixture"));
    }

    @Test
    void environmentTakesPrecedenceAndMissingFilesAllowEnvironmentOnlyConfiguration() throws IOException {
        Path file = directory.resolve("fixture.env");
        Files.writeString(file, "TUPREL_TEST_DB_USER=file-user\n");
        assertEquals("process-user", LocalTestEnvironment.load(file, Map.of("TUPREL_TEST_DB_USER", "process-user"))
                .required("TUPREL_TEST_DB_USER"));
        assertEquals("process-user", LocalTestEnvironment.load(directory.resolve("absent.env"),
                Map.of("TUPREL_TEST_DB_USER", "process-user")).required("TUPREL_TEST_DB_USER"));
    }

    @Test
    void rejectsMalformedUnknownAndDuplicateEntriesWithoutEchoingValuesOrCauses() throws IOException {
        Path file = directory.resolve("fixture.env");
        for (String content : List.of("SECRET", "SECRET=value", "TUPREL_TEST_DB_PASSWORD='SECRET",
                "TUPREL_TEST_DB_PASSWORD=SECRET\nTUPREL_TEST_DB_PASSWORD=other")) {
            Files.writeString(file, content);
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> LocalTestEnvironment.load(file, Map.of()));
            assertFalse(failure.toString().contains("SECRET"));
            assertNull(failure.getCause());
        }
    }

    @Test
    void reportsBlankSettingsAndUnreadableOrOversizedFilesWithoutValues() throws IOException {
        Path file = directory.resolve("fixture.env");
        Files.writeString(file, "TUPREL_TEST_DB_PASSWORD=\n");
        LocalTestEnvironment configuration = LocalTestEnvironment.load(file, Map.of());
        assertThrows(IllegalStateException.class, () -> configuration.required("TUPREL_TEST_DB_PASSWORD"));
        assertThrows(IllegalArgumentException.class, () -> configuration.required("UNKNOWN"));
        assertNull(assertThrows(IllegalStateException.class,
                () -> LocalTestEnvironment.load(directory, Map.of())).getCause());
        Files.writeString(file, "#".repeat(16_385));
        assertThrows(IllegalArgumentException.class, () -> LocalTestEnvironment.load(file, Map.of()));
    }
}
