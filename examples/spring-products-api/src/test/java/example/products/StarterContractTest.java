package example.products;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StarterContractTest {
    @Test
    void preservesAutoConfigurationAndPoolOwnership(@TempDir Path scratch) throws Exception {
        execute(scratch, null, "Spring Boot consumer checks passed");
    }

    @Test
    void preservesExplicitAndSpringTransactionBoundaries(@TempDir Path scratch) throws Exception {
        String url = System.getenv("TUPREL_TEST_JDBC_URL");
        if (url == null || !url.matches("jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost):[0-9]{1,5}/tuprel_devtest")) {
            throw new IllegalStateException("Expected the loopback tuprel_devtest database without JDBC options");
        }
        String user = System.getenv("TUPREL_TEST_DB_USER");
        String password = System.getenv("TUPREL_TEST_DB_PASSWORD");
        String schema = "tuprel_spring_lts_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = DriverManager.getConnection(url, user, password);
                Statement ddl = connection.createStatement()) {
            assertEquals(17, connection.getMetaData().getDatabaseMajorVersion());
            ddl.execute("CREATE SCHEMA " + schema);
            try {
                ddl.execute("SET search_path TO " + schema);
                ddl.execute("CREATE TABLE inventory (id INTEGER PRIMARY KEY)");
                execute(scratch, new String[]{url, user, password, schema}, "Spring Boot PostgreSQL transaction passed");
                try (ResultSet result = ddl.executeQuery("SELECT id FROM inventory ORDER BY id")) {
                    for (int expected : new int[]{7, 9, 13}) {
                        assertTrue(result.next());
                        assertEquals(expected, result.getInt(1));
                    }
                    assertFalse(result.next());
                }
            } finally {
                ddl.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    private static void execute(Path scratch, String[] credentials, String expected) throws Exception {
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        List<String> command = new java.util.ArrayList<>(List.of(
                ProcessHandle.current().info().command().orElseThrow(), "-cp", classpath, "consumer.ConsumerApplication"));
        if (credentials != null) command.add("--postgresql");
        Path log = scratch.resolve("ownership.log");
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().keySet().removeIf(name -> name.startsWith("TUPREL_TEST_"));
        Process child = builder.start();
        try {
            try (DataOutputStream pipe = new DataOutputStream(child.getOutputStream())) {
                if (credentials != null) {
                    for (String value : credentials) pipe.writeUTF(value);
                }
            }
            assertTrue(child.waitFor(60, TimeUnit.SECONDS), "Ownership subprocess exceeded its deadline");
            String output = Files.readString(log);
            if (credentials != null) assertFalse(output.contains(credentials[2]), "Credentials reached subprocess logs");
            assertEquals(0, child.exitValue(), "Ownership subprocess failed; raw output is withheld");
            assertTrue(output.contains(expected), "Ownership subprocess did not confirm its proof");
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly();
                assertTrue(child.waitFor(10, TimeUnit.SECONDS), "Ownership subprocess did not stop");
            }
        }
    }
}
