package dev.tuprel.testsupport;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;
import java.util.UUID;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** A real database with a private schema per suite; local mode never instantiates a container. */
public final class TestPostgresql implements AutoCloseable {
    private final PGSimpleDataSource dataSource;
    private final Optional<PostgreSQLContainer> container;
    private final String schema = "tuprel_it_" + UUID.randomUUID().toString().replace("-", "");
    private final boolean local;
    private boolean created;

    private TestPostgresql(PGSimpleDataSource dataSource, Optional<PostgreSQLContainer> container) {
        this.dataSource = dataSource;
        this.container = container;
        this.local = container.isEmpty();
    }

    public static TestPostgresql open() throws SQLException {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        Optional<PostgreSQLContainer> container;
        String mode = System.getProperty("tuprel.test.database", "local");
        if (mode.equals("local")) {
            LocalTestEnvironment configuration = LocalTestEnvironment.load(
                    Path.of(System.getProperty("tuprel.test.env.file", "examples/tuprel-playground/.env")));
            String url = configuration.required("TUPREL_TEST_JDBC_URL");
            LocalPostgresqlTarget.validate(url);
            dataSource.setURL(url);
            dataSource.setUser(configuration.required("TUPREL_TEST_DB_USER"));
            dataSource.setPassword(configuration.required("TUPREL_TEST_DB_PASSWORD"));
            container = Optional.empty();
        } else if (mode.equals("container")) {
            PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.11-trixie");
            postgres.start();
            container = Optional.of(postgres);
            dataSource.setURL(postgres.getJdbcUrl());
            dataSource.setUser(postgres.getUsername());
            dataSource.setPassword(postgres.getPassword());
        } else {
            throw new IllegalArgumentException("tuprelTestDatabase must be local or container");
        }
        dataSource.setConnectTimeout(5);
        TestPostgresql database = new TestPostgresql(dataSource, container);
        try {
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                database.verifyCatalog(connection);
                statement.execute("CREATE SCHEMA \"" + database.schema + "\"");
                database.created = true;
            }
            dataSource.setCurrentSchema(database.schema);
            return database;
        } catch (SQLException | RuntimeException failure) {
            try {
                database.close();
            } catch (SQLException | RuntimeException closing) {
                failure.addSuppressed(closing);
            }
            throw failure;
        }
    }

    public PGSimpleDataSource dataSource() {
        return dataSource;
    }

    private void verifyCatalog(Connection connection) throws SQLException {
        if (local && !"tuprel_devtest".equals(connection.getCatalog())) {
            throw new SQLException("Refusing to access a database other than tuprel_devtest");
        }
    }

    @Override
    public void close() throws SQLException {
        try {
            if (created) {
                try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                    verifyCatalog(connection);
                    // The name is generated internally, never supplied by configuration.
                    statement.execute("DROP SCHEMA \"" + schema + "\" CASCADE");
                    created = false;
                }
            }
        } finally {
            container.ifPresent(PostgreSQLContainer::stop);
        }
    }
}
