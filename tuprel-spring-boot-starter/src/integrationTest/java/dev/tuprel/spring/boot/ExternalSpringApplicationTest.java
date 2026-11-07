package dev.tuprel.spring.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuprel.testsupport.TestPostgresql;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;

class ExternalSpringApplicationTest {
    @Test void discoversStarterInAnExternalApplication(@TempDir Path project) throws Exception {
        execute(project, null, "Spring Boot consumer checks passed");
    }

    @Test void usesTheApplicationDataSourceOnPostgresql(@TempDir Path project) throws Exception {
        try (TestPostgresql fixture = TestPostgresql.open()) {
            PGSimpleDataSource source = fixture.dataSource();
            try (Connection connection = source.getConnection(); Statement statement = connection.createStatement()) {
                assertEquals(source.getCurrentSchema(), connection.getSchema());
                statement.execute("CREATE TABLE \"inventory\" (\"id\" INTEGER PRIMARY KEY)");
            }
            execute(project, source, "Spring Boot PostgreSQL transaction passed");
            try (Connection connection = source.getConnection(); Statement statement = connection.createStatement();
                    java.sql.ResultSet result = statement.executeQuery("SELECT id FROM inventory ORDER BY id")) {
                for (int expected : new int[] {7, 9, 10, 11}) {
                    assertTrue(result.next());
                    assertEquals(expected, result.getInt(1));
                }
                assertFalse(result.next());
            }
        }
    }

    private void execute(Path project, PGSimpleDataSource source, String expectedOutput) throws Exception {
        Path javaSource = project.resolve("ConsumerApplication.java");
        try (InputStream fixture = getClass().getResourceAsStream("/consumer/ConsumerApplication.java")) {
            assertNotNull(fixture);
            Files.copy(fixture, javaSource);
        }
        String artifacts = System.getProperty("tuprel.spring.fixture.classpath");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "--release", "21", "-cp", artifacts, "-d", project.toString(), javaSource.toString()));
        Path launcher = Path.of(System.getProperty("java.home"), "bin", "java");
        List<String> command = new ArrayList<>(List.of(launcher.toString(), "-cp",
                project + java.io.File.pathSeparator + artifacts, "consumer.ConsumerApplication"));
        if (source != null) command.add("--postgresql");
        Path output = project.resolve("application.log");
        Process app = new ProcessBuilder(command).directory(project.toFile())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            try (DataOutputStream pipe = new DataOutputStream(app.getOutputStream())) {
                if (source != null) {
                    pipe.writeUTF(source.getURL());
                    pipe.writeUTF(source.getUser());
                    pipe.writeUTF(source.getPassword());
                    pipe.writeUTF(source.getCurrentSchema());
                }
            }
            assertTrue(app.waitFor(60, TimeUnit.SECONDS), "External Spring application did not finish");
            String text = Files.readString(output);
            if (source != null) {
                assertFalse(text.contains(source.getPassword()), "Fixture credentials reached subprocess output");
            }
            assertEquals(0, app.exitValue(), "External Spring application failed");
            assertTrue(text.contains(expectedOutput));
        } finally {
            if (app.isAlive()) {
                app.destroyForcibly();
            }
        }
    }
}
