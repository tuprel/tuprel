package dev.tuprel.playground;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;
import org.postgresql.ds.PGSimpleDataSource;

/** Local-only bootstrap for a dedicated test database; no credentials are printed. */
final class LocalDatabase {
    private LocalDatabase() { }

    static PGSimpleDataSource connect() {
        String url = required("TUPREL_TEST_JDBC_URL");
        validateUrl(url);
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(url);
        source.setUser(required("TUPREL_TEST_DB_USER"));
        source.setPassword(required("TUPREL_TEST_DB_PASSWORD"));
        source.setCurrentSchema("tuprel_playground");
        source.setConnectTimeout(5);
        return source;
    }

    static void validateUrl(String url) {
        String message = "Use jdbc:postgresql://<loopback>:<port>/tuprel_devtest without URL credentials or parameters";
        if (url == null || !url.startsWith("jdbc:postgresql://")) {
            throw new IllegalArgumentException(message);
        }
        try {
            URI target = new URI(url.substring(5));
            if (target.getHost() == null || !Set.of("localhost", "127.0.0.1", "[::1]").contains(target.getHost())
                    || target.getPort() < 1 || target.getPort() > 65535
                    || !"/tuprel_devtest".equals(target.getRawPath())
                    || target.getRawUserInfo() != null || target.getRawQuery() != null || target.getRawFragment() != null) {
                throw new IllegalArgumentException(message);
            }
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException(message);
        }
    }

    static void bootstrap(PGSimpleDataSource source) throws SQLException, IOException {
        String sql;
        try (InputStream resource = LocalDatabase.class.getResourceAsStream("/bootstrap.sql")) {
            if (resource == null) {
                throw new IOException("Missing bootstrap.sql");
            }
            sql = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (Connection connection = source.getConnection()) {
            if (!"tuprel_devtest".equals(connection.getCatalog())) {
                throw new SQLException("Refusing to bootstrap a database other than tuprel_devtest");
            }
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                // Static DDL only. Never concatenate values from application input.
                statement.execute("CREATE SCHEMA IF NOT EXISTS tuprel_playground");
                statement.execute("SET LOCAL search_path TO tuprel_playground");
                statement.execute(sql);
                connection.commit();
            } catch (SQLException failure) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
                throw failure;
            }
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Set " + name + " in the application environment");
        }
        return value;
    }
}
