package dev.tuprel.testsupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.postgresql.ds.PGSimpleDataSource;

/** Copies, builds and runs the standalone fixture using installed JARs only. */
public final class StagedJavaConsumer {
    private final Path installation;
    private final Path project;
    private final boolean windows = System.getProperty("os.name").startsWith("Windows");
    private final Path jdk = Path.of(System.getProperty("java.home"), "bin");

    public StagedJavaConsumer(Path fixture) throws IOException {
        Path example = requiredPath("example");
        assertFalse(fixture.toRealPath().startsWith(example.getParent().getParent()), "The consumer must run outside the checkout");
        installation = fixture.resolve("installed CLI");
        copyTree(requiredPath("cli"), installation);
        project = fixture.resolve("consumer project");
        copyTree(example, project);
        Path libraries = installation.resolve("lib");
        for (String artifact : List.of("runtime", "postgresql")) {
            Path jar = requiredPath(artifact);
            Files.copy(jar, libraries.resolve(jar.getFileName()));
        }
    }

    public Path project() { return project; }

    public void generateAndCompile() throws Exception {
        Path launcher = installation.resolve(windows ? "bin/tuprel.bat" : "bin/tuprel");
        List<String> command = new ArrayList<>(windows ? List.of("cmd.exe", "/d", "/c") : List.of());
        command.add(launcher.toString());
        command.add("validate");
        run(project, command, null);
        command.set(command.size() - 1, "generate");
        run(project, command, null);
        command.add("--check");
        run(project, command, null);

        Path classes = Files.createDirectory(project.resolve("classes"));
        List<String> compile = new ArrayList<>(List.of(jdk.resolve(windows ? "javac.exe" : "javac").toString(),
                "--release", "21", "-encoding", "UTF-8", "-cp", classpath(), "-d", classes.toString(),
                project.resolve("src/Consumer.java").toString()));
        try (Stream<Path> files = Files.walk(project.resolve("build/generated/sources/tuprel/main"))) {
            files.filter(path -> path.toString().endsWith(".java")).map(Path::toString).sorted().forEach(compile::add);
        }
        run(project, compile, null);
    }

    public void exercise(PGSimpleDataSource source, String... options) throws Exception {
        List<String> command = new ArrayList<>(List.of(jdk.resolve(windows ? "java.exe" : "java").toString(), "-cp",
                project.resolve("classes") + java.io.File.pathSeparator + classpath(), "example.sales.Consumer"));
        command.addAll(List.of(options));
        String output = run(project, command, source);
        assertEquals("Staged Java consumer passed", output.strip());
    }

    private String classpath() { return installation.resolve("lib") + java.io.File.separator + "*"; }

    private static Path requiredPath(String name) {
        return Path.of(System.getProperty("tuprel.test.staged." + name));
    }

    private static void copyTree(Path source, Path destination) throws IOException {
        try (Stream<Path> paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path target = destination.resolve(source.relativize(path));
                if (Files.isDirectory(path)) { Files.createDirectories(target); }
                else { Files.copy(path, target, StandardCopyOption.COPY_ATTRIBUTES); }
            }
        }
    }

    private static String run(Path project, List<String> command, PGSimpleDataSource database) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command).directory(project.toFile()).redirectErrorStream(true);
        builder.environment().keySet().removeIf(name -> name.regionMatches(true, 0, "TUPREL_", 0, 7));
        builder.environment().put("JAVA_HOME", System.getProperty("java.home"));
        Process child = builder.start();
        try {
            CompletableFuture<String> output = CompletableFuture.supplyAsync(() -> {
                try { return new String(child.getInputStream().readNBytes(8192), StandardCharsets.UTF_8); }
                catch (IOException failure) { throw new UncheckedIOException(failure); }
            });
            try (DataOutputStream input = new DataOutputStream(child.getOutputStream())) {
                if (database != null) {
                    input.writeUTF(database.getURL());
                    input.writeUTF(database.getUser());
                    input.writeUTF(database.getPassword());
                    input.writeUTF(database.getCurrentSchema());
                }
            }
            assertTrue(child.waitFor(60, TimeUnit.SECONDS), "Staged consumer subprocess did not exit");
            String text = output.get(5, TimeUnit.SECONDS);
            if (database != null) { assertFalse(text.contains(database.getPassword()), "Fixture credentials reached subprocess output"); }
            assertEquals(0, child.exitValue(), "A staged launcher, compiler or consumer failed");
            return text;
        } finally { if (child.isAlive()) { child.destroyForcibly(); } }
    }
}
