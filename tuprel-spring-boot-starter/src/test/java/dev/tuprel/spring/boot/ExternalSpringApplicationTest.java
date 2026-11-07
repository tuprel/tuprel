package dev.tuprel.spring.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExternalSpringApplicationTest {
    @Test void discoversStarterInAnExternalApplication(@TempDir Path project) throws Exception {
        Path source = project.resolve("ConsumerApplication.java");
        try (InputStream fixture = getClass().getResourceAsStream("/consumer/ConsumerApplication.java")) {
            assertNotNull(fixture);
            Files.copy(fixture, source);
        }
        String artifacts = System.getProperty("tuprel.spring.fixture.classpath");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "--release", "21", "-cp", artifacts, "-d", project.toString(), source.toString()));
        Path launcher = Path.of(System.getProperty("java.home"), "bin", "java");
        Path output = project.resolve("application.log");
        Process app = new ProcessBuilder(launcher.toString(), "-cp",
                project + java.io.File.pathSeparator + artifacts, "consumer.ConsumerApplication")
                .directory(project.toFile()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(app.waitFor(60, TimeUnit.SECONDS), "External Spring application did not finish");
            assertEquals(0, app.exitValue(), () -> "External Spring application failed\n" + readOutput(output));
            assertTrue(readOutput(output).contains("Spring Boot consumer checks passed"));
        } finally {
            if (app.isAlive()) {
                app.destroyForcibly();
            }
        }
    }

    private static String readOutput(Path output) {
        try {
            return Files.readString(output);
        } catch (java.io.IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
    }
}
