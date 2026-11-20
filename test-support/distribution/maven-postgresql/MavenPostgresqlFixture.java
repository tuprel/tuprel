package fixture.consumer;

import dev.tuprel.testsupport.LocalPostgresqlTarget;
import dev.tuprel.testsupport.LocalTestEnvironment;
import java.io.DataOutputStream;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.postgresql.ds.PGSimpleDataSource;

/** Runs the sales example on Maven-resolved JARs and an isolated PostgreSQL 17 schema. */
public final class MavenPostgresqlFixture {
    private MavenPostgresqlFixture() {}

    public static void main(String[] args) {
        try {
            exercise(LocalTestEnvironment.load(Path.of(args[0])));
        } catch (SQLException failure) {
            String state = failure.getSQLState();
            System.err.println("Maven PostgreSQL fixture failed. SQLSTATE="
                    + (state != null && state.matches("[A-Z0-9]{5}") ? state : "UNKNOWN"));
            System.exit(1);
        } catch (Exception failure) {
            System.err.println("Maven PostgreSQL fixture failed to configure or run the isolated consumer");
            System.exit(1);
        }
    }

    private static void exercise(LocalTestEnvironment configuration) throws Exception {
        String url = configuration.required("TUPREL_TEST_JDBC_URL");
        LocalPostgresqlTarget.validate(url);
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(url);
        source.setUser(configuration.required("TUPREL_TEST_DB_USER"));
        source.setPassword(configuration.required("TUPREL_TEST_DB_PASSWORD"));
        source.setConnectTimeout(5);
        source.setSocketTimeout(15);

        String schema = "tuprel_it_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = source.getConnection(); Statement statement = connection.createStatement()) {
            if (!"tuprel_devtest".equals(connection.getCatalog()) || connection.getMetaData().getDatabaseMajorVersion() != 17) {
                throw new IllegalStateException("Expected isolated PostgreSQL 17 test database");
            }
            statement.setQueryTimeout(10);
            statement.execute("CREATE SCHEMA " + schema);
            try {
                statement.execute("CREATE TABLE " + schema
                        + ".\"Product\" (\"id\" INTEGER PRIMARY KEY, \"name\" TEXT NOT NULL, \"stock\" INTEGER NOT NULL)");
                statement.execute("CREATE TABLE " + schema
                        + ".\"Purchase\" (\"id\" INTEGER PRIMARY KEY, \"productId\" INTEGER NOT NULL, \"quantity\" INTEGER NOT NULL)");
                run(source, schema, List.of());
                run(source, schema, List.of("--verify-upgrade"));
            } finally {
                statement.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
        System.out.println("Maven PostgreSQL 17 fixture passed with cleanup on Java " + Runtime.version().feature());
    }

    private static void run(PGSimpleDataSource source, String schema, List<String> args) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        List<String> command = new ArrayList<>(List.of(java, "-cp", System.getProperty("java.class.path"), "example.sales.Consumer"));
        command.addAll(args);
        ProcessBuilder builder = new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.INHERIT)
                .redirectError(ProcessBuilder.Redirect.INHERIT);
        builder.environment().keySet().removeIf(name -> name.regionMatches(true, 0, "TUPREL_TEST_", 0, 12));
        Process child = builder.start();
        try {
            try (DataOutputStream input = new DataOutputStream(child.getOutputStream())) {
                input.writeUTF(source.getURL());
                input.writeUTF(source.getUser());
                input.writeUTF(source.getPassword());
                input.writeUTF(schema);
            }
            if (!child.waitFor(45, TimeUnit.SECONDS) || child.exitValue() != 0) {
                throw new IllegalStateException("Independent PostgreSQL consumer failed");
            }
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly();
                if (!child.waitFor(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Independent PostgreSQL consumer did not terminate");
                }
            }
        }
    }
}
