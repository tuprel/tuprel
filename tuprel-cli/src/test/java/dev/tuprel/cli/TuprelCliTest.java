package dev.tuprel.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TuprelCliTest {
    @TempDir Path temporaryDirectory;

    @Test
    void validateReportsSuccessForValidSchema() throws Exception {
        Path schema = write("model User { id Int @id }\n");
        StringWriter output = new StringWriter();
        StringWriter errors = new StringWriter();

        int code = TuprelCli.run(new String[] {"validate", schema.toString()}, writer(output), writer(errors));

        assertEquals(0, code);
        assertTrue(output.toString().contains("Schema is valid"));
        assertTrue(errors.toString().isEmpty());
    }

    @Test
    void validateReportsStructuredErrors() throws Exception {
        Path schema = write("model User { id Strng @id }\n");
        StringWriter errors = new StringWriter();

        int code = TuprelCli.run(new String[] {"validate", schema.toString()}, writer(new StringWriter()), writer(errors));

        assertEquals(1, code);
        assertTrue(errors.toString().contains("TUPREL-SCHEMA-SEM-004"));
    }

    @Test
    void formatCheckAndWriteFollowExitContract() throws Exception {
        Path schema = write("model User{ id Int @id }\n");
        StringWriter checkErrors = new StringWriter();

        int checkCode =
                TuprelCli.run(
                        new String[] {"format", "--check", schema.toString()},
                        writer(new StringWriter()),
                        writer(checkErrors));
        int writeCode =
                TuprelCli.run(
                        new String[] {"format", schema.toString()},
                        writer(new StringWriter()),
                        writer(new StringWriter()));

        assertEquals(1, checkCode);
        assertTrue(checkErrors.toString().contains("not formatted"));
        assertEquals(0, writeCode);
        assertEquals("model User {\n    id Int @id\n}\n", Files.readString(schema));
    }

    @Test
    void invalidArgumentsReturnUsageExitCode() {
        StringWriter errors = new StringWriter();

        int code = TuprelCli.run(new String[] {"unknown"}, writer(new StringWriter()), writer(errors));

        assertEquals(2, code);
        assertTrue(errors.toString().contains("Usage:"));
    }

    @Test
    void duplicateFormatOptionsReturnUsageExitCode() throws Exception {
        Path schema = write("model User { id Int @id }\n");
        StringWriter errors = new StringWriter();

        int code =
                TuprelCli.run(
                        new String[] {"format", "--check", "--check", schema.toString()},
                        writer(new StringWriter()),
                        writer(errors));

        assertEquals(2, code);
        assertTrue(errors.toString().contains("Usage:"));
    }

    @Test
    void formatCheckPassesAfterFormatting() throws Exception {
        Path schema = write("model User {\n id Int @id\n}\n");
        TuprelCli.run(new String[] {"format", schema.toString()}, writer(new StringWriter()), writer(new StringWriter()));

        int code =
                TuprelCli.run(
                        new String[] {"format", "--check", schema.toString()},
                        writer(new StringWriter()),
                        writer(new StringWriter()));

        assertEquals(0, code);
    }

    private Path write(String text) throws Exception {
        Path path = temporaryDirectory.resolve("schema.tuprel");
        Files.writeString(path, text, StandardCharsets.UTF_8);
        return path;
    }

    private static PrintWriter writer(StringWriter output) {
        return new PrintWriter(output, true);
    }
}
