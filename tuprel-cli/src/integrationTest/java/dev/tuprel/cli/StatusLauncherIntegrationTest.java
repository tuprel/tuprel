package dev.tuprel.cli;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuprel.testsupport.TestPostgresql;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;

class StatusLauncherIntegrationTest {
    private static TestPostgresql database;
    @TempDir Path temporary;
    @BeforeAll static void open() throws Exception { database = TestPostgresql.open(); }
    @AfterAll static void close() throws Exception { if (database != null) { database.close(); } }

    @Test void realLauncherUsesExplicitEnvironmentAndNeverBootstrapsHistory() throws Exception {
        Path project = Files.createDirectory(temporary.resolve("project with espaço"));
        assertEquals(0, launch(project, false, "migrate", "status", "--help").exit());
        assertEquals(0, launch(project, false, "init").exit());
        assertEquals(0, launch(project, false, "migrate", "create", "--name", "initial").exit());
        Files.writeString(project.resolve(".env"), "TUPREL_DATABASE_URL=private_value\nTUPREL_DATABASE_PASSWORD=private_value\n");
        String schema = database.dataSource().getCurrentSchema();
        Result missingEnv = launch(project, false, "migrate", "status", "--target-schema", schema);
        assertEquals(2, missingEnv.exit()); assertTrue(missingEnv.output().contains("CONFIGURATION"));
        Result status = launch(project, true, "migrate", "status", "--target-schema", schema);
        assertEquals(0, status.exit(), status::output);
        assertTrue(status.output().contains("UNINITIALISED")); assertTrue(status.output().contains("PENDING"));
        assertTrue(status.output().contains("DRIFT_NOT_CHECKED"));
        assertFalse(status.output().contains("private_value")); assertFalse(status.output().contains("\u001b"));
        try (java.sql.Connection connection = database.dataSource().getConnection();
                java.sql.PreparedStatement query = connection.prepareStatement("SELECT count(*) FROM pg_catalog.pg_class WHERE relnamespace = ?::regnamespace")) {
            query.setString(1, schema);
            try (java.sql.ResultSet rows = query.executeQuery()) { assertTrue(rows.next()); assertEquals(0, rows.getLong(1)); }
        }
        assertEquals(2, launch(project, true, "migrate", "status", "--target-schema", "../private_value").exit());
        assertEquals(2, launch(project, true, "migrate", "deploy").exit());
        assertEquals(0, launch(project, false, "validate").exit());
    }

    private static Result launch(Path project, boolean configured, String... arguments) throws Exception {
        boolean windows = System.getProperty("os.name").startsWith("Windows");
        String launcher = System.getProperty("tuprel.cli.launcher") + (windows ? ".bat" : "");
        List<String> command = new ArrayList<>();
        if (windows) { command.add("cmd.exe"); command.add("/d"); command.add("/c"); }
        command.add(launcher); command.addAll(List.of(arguments));
        ProcessBuilder builder = new ProcessBuilder(command).directory(project.toFile()).redirectErrorStream(true);
        Map<String, String> environment = builder.environment();
        environment.remove("TUPREL_DATABASE_URL"); environment.remove("TUPREL_DATABASE_USER"); environment.remove("TUPREL_DATABASE_PASSWORD");
        if (configured) {
            // The test harness intentionally maps fixture credentials into process
            // environment. The production CLI never loads a test dotenv file.
            PGSimpleDataSource source = database.dataSource();
            environment.put("TUPREL_DATABASE_URL", "jdbc:postgresql://" + source.getServerNames()[0] + ":"
                    + source.getPortNumbers()[0] + "/" + source.getDatabaseName());
            environment.put("TUPREL_DATABASE_USER", source.getUser());
            environment.put("TUPREL_DATABASE_PASSWORD", source.getPassword());
        }
        environment.put("JAVA_HOME", System.getProperty("java.home"));
        Process process = builder.start();
        CompletableFuture<String> output = CompletableFuture.supplyAsync(() -> {
            try { return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8); }
            catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
        });
        try {
            assertTrue(process.waitFor(40, TimeUnit.SECONDS), "Launcher timeout");
            return new Result(process.exitValue(), output.get(5, TimeUnit.SECONDS));
        } finally { process.destroyForcibly(); }
    }
    private record Result(int exit, String output) {}
}
