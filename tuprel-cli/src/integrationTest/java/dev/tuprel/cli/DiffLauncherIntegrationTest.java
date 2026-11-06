package dev.tuprel.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real PowerShell/BAT on Windows and Bash on Linux; no database fixture. */
class DiffLauncherIntegrationTest {
    @TempDir Path temporary;

    @Test void realPlatformShellComparesOfflineAndPreservesEveryFile() throws Exception {
        Path project = Files.createDirectory(temporary.resolve("project with espaço")).toRealPath();
        Path catalog = Files.createDirectories(project.resolve("tuprel/migrations"));
        Files.writeString(catalog.resolve(".tuprel-migrations"), "TUPREL-MIGRATIONS 1\n");
        Files.writeString(project.resolve("custom espaço.tuprel"), "model Item { id UUID @id value String? }\n");
        Files.writeString(project.resolve(".env"), "TUPREL_DATABASE_PASSWORD=private_shell_secret\n");
        List<String> args = List.of("migrate", "diff", "--target-schema", "app", "--schema", "custom espaço.tuprel");
        Map<String, String> before = files(project);
        Result preview = launch(project, args);
        assertEquals(1, preview.code(), preview::output); assertTrue(preview.output().contains("CREATE TABLE"), preview::output);
        assertTrue(preview.output().contains("PHYSICAL_STATE_NOT_CHECKED")); assertEquals(before, files(project));
        Path sql = Files.writeString(Files.createDirectory(catalog.resolve("20261031_120000_initial")).resolve("migration.sql"),
                "CREATE TABLE \"app\".\"Item\" (\"id\" UUID PRIMARY KEY, \"value\" TEXT);\n");
        before = files(project); assertEquals(0, launch(project, args).code()); assertEquals(before, files(project));
        Files.writeString(sql, "SELECT 'private_shell_secret';\n");
        before = files(project); Result blocked = launch(project, args);
        assertEquals(2, blocked.code()); assertFalse(blocked.output().contains("CREATE TABLE")); assertEquals(before, files(project));
        assertEquals(0, launch(project, List.of("migrate", "diff", "--help")).code());
    }

    private static Result launch(Path project, List<String> arguments) throws Exception {
        boolean windows = System.getProperty("os.name").startsWith("Windows");
        String launcher = System.getProperty("tuprel.cli.launcher") + (windows ? ".bat" : "");
        List<String> command = new ArrayList<>();
        if (windows) {
            command.addAll(List.of("powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive", "-Command",
                    "$cliArguments = @($env:TUPREL_TEST_CLI_ARGS -split [char]10); & $env:TUPREL_TEST_CLI_LAUNCHER @cliArguments; exit $LASTEXITCODE"));
        } else { command.add("bash"); command.add(launcher); command.addAll(arguments); }
        ProcessBuilder builder = new ProcessBuilder(command).directory(project.toFile()).redirectErrorStream(true);
        builder.environment().put("JAVA_HOME", System.getProperty("java.home"));
        builder.environment().put("TUPREL_DATABASE_URL", "deliberately-invalid");
        builder.environment().put("TUPREL_DATABASE_USER", "offline");
        builder.environment().put("TUPREL_DATABASE_PASSWORD", "private_shell_secret");
        if (windows) {
            builder.environment().put("TUPREL_TEST_CLI_LAUNCHER", launcher);
            builder.environment().put("TUPREL_TEST_CLI_ARGS", String.join("\n", arguments));
        }
        Process process = builder.start();
        CompletableFuture<String> output = CompletableFuture.supplyAsync(() -> {
            try { return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8); }
            catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
        });
        try {
            assertTrue(process.waitFor(45, TimeUnit.SECONDS), "Offline launcher timeout");
            String text = output.get(5, TimeUnit.SECONDS);
            assertFalse(text.contains("private_shell_secret"), "Offline launcher exposed sensitive input");
            assertFalse(text.contains("\u001b"), "Offline launcher emitted ANSI");
            return new Result(process.exitValue(), text);
        } finally { process.destroyForcibly(); }
    }

    private static Map<String, String> files(Path project) throws Exception {
        Map<String, String> snapshot = new TreeMap<>();
        try (java.util.stream.Stream<Path> paths = Files.walk(project)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) { snapshot.put(project.relativize(path).toString(), Files.readString(path)); }
        }
        return snapshot;
    }
    private record Result(int code, String output) { }
}
