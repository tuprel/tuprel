package consumer;

import dev.tuprel.postgresql.PostgresqlDatabase;
import dev.tuprel.runtime.TuprelDatabase;
import dev.tuprel.runtime.TuprelDatabaseException;
import dev.tuprel.sql.SqlCommand;
import dev.tuprel.sql.SqlIdentifier;
import dev.tuprel.sql.SqlValue;
import java.io.DataInputStream;
import java.io.IOException;
import java.sql.Connection;
import java.util.List;
import org.postgresql.ds.PGSimpleDataSource;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;

public final class ConsumerApplication {
    private ConsumerApplication() {}

    public static void main(String[] args) {
        if (args.length == 1 && args[0].equals("--postgresql")) {
            try {
                exercisePostgresql();
                System.out.println("Spring Boot PostgreSQL transaction passed");
            } catch (Exception | AssertionError failure) {
                System.err.println("Spring Boot PostgreSQL fixture failed");
                System.exit(1);
            }
            return;
        }
        CountingDataSource pool;
        try (ConfigurableApplicationContext context = start(PrimaryDataSources.class)) {
            pool = context.getBean("preferredPool", CountingDataSource.class);
            TuprelDatabase database = context.getBean(TuprelDatabase.class);
            require(pool.connections.get() == 0 && pool.closes.get() == 0, "Startup touched the application's pool");
            try {
                database.transaction(ignored -> null);
                throw new AssertionError("Expected the fixture connection failure");
            } catch (TuprelDatabaseException expected) {
                CountingDataSource other = context.getBean("anotherPool", CountingDataSource.class);
                require(pool.connections.get() == 1 && other.connections.get() == 0, "Tuprel used the wrong DataSource");
            }
        }
        require(pool.closes.get() == 1, "The application must close its own DataSource exactly once");
        try (ConfigurableApplicationContext context = start(ExplicitDatabase.class)) {
            require(context.getBean(TuprelDatabase.class) == context.getBean("applicationDatabase"),
                    "The starter replaced an explicit Tuprel database");
        }
        try (ConfigurableApplicationContext context = start(AmbiguousDataSources.class)) {
            require(context.getBeansOfType(TuprelDatabase.class).isEmpty(), "Ambiguous DataSources must not select a pool");
        }
        try (ConfigurableApplicationContext context = start(NoDataSource.class)) {
            require(context.getBeansOfType(TuprelDatabase.class).isEmpty(), "Missing DataSource must leave Tuprel unconfigured");
        }
        System.out.println("Spring Boot consumer checks passed");
    }

    private static void exercisePostgresql() {
        try (ConfigurableApplicationContext context = start(LivePostgresql.class)) {
            TuprelDatabase database = context.getBean(TuprelDatabase.class);
            SqlIdentifier table = new SqlIdentifier("inventory");
            SqlIdentifier column = new SqlIdentifier("id");
            SqlValue id = new SqlValue.Int32(7);
            int inserted = database.transaction(transaction -> transaction.create(
                    new SqlCommand.Insert(table, List.of(new SqlCommand.Assignment(column, id)))));
            require(inserted == 1, "Expected one inserted row");
            require(database.findById(new SqlCommand.FindById(table, column, id),
                    row -> row.int32("id")).orElseThrow() == 7, "Expected the committed row");

            RollbackProbe probe = context.getBean(RollbackProbe.class);
            try {
                probe.writeThenFail();
                throw new AssertionError("Expected the Spring rollback trigger");
            } catch (RollbackTrigger expected) {
                // The outer transaction must have rolled back before control returns here.
            }
            require(database.findById(new SqlCommand.FindById(table, column, new SqlValue.Int32(8)),
                    row -> row.int32("id")).isEmpty(), "Spring JDBC must roll back");
            require(database.findById(new SqlCommand.FindById(table, column, new SqlValue.Int32(9)),
                    row -> row.int32("id")).orElseThrow() == 9,
                    "The explicit Tuprel transaction currently commits independently");

            try {
                probe.tryImplicitParticipation();
                throw new AssertionError("Expected the runtime to refuse the Spring-owned connection");
            } catch (TuprelDatabaseException expected) {
                require(expected.phase() == TuprelDatabaseException.Phase.CONNECTION, "Expected a connection refusal");
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EnableTransactionManagement
    static class LivePostgresql {
        @Bean DataSourceTransactionManager transactionManager(DataSource source) {
            return new DataSourceTransactionManager(source);
        }

        @Bean RollbackProbe rollbackProbe(DataSource source, TuprelDatabase database) {
            TuprelDatabase statementAdapter = PostgresqlDatabase.using(new TransactionAwareDataSourceProxy(source));
            return new RollbackProbe(new JdbcTemplate(source), database, statementAdapter);
        }

        @Bean DataSource pool() throws IOException {
            PGSimpleDataSource source = new PGSimpleDataSource();
            try (DataInputStream pipe = new DataInputStream(System.in)) {
                source.setURL(pipe.readUTF());
                source.setUser(pipe.readUTF());
                source.setPassword(pipe.readUTF());
                source.setCurrentSchema(pipe.readUTF());
            }
            source.setConnectTimeout(5);
            return source;
        }
    }

    static class RollbackProbe {
        private final JdbcTemplate jdbc;
        private final TuprelDatabase database;
        private final TuprelDatabase statementAdapter;

        RollbackProbe(JdbcTemplate jdbc, TuprelDatabase database, TuprelDatabase statementAdapter) {
            this.jdbc = jdbc;
            this.database = database;
            this.statementAdapter = statementAdapter;
        }

        @Transactional
        public void writeThenFail() {
            jdbc.update("INSERT INTO inventory (id) VALUES (?)", 8);
            database.transaction(transaction -> transaction.create(new SqlCommand.Insert(
                    new SqlIdentifier("inventory"),
                    List.of(new SqlCommand.Assignment(new SqlIdentifier("id"), new SqlValue.Int32(9))))));
            throw new RollbackTrigger();
        }

        @Transactional
        public void tryImplicitParticipation() {
            jdbc.update("INSERT INTO inventory (id) VALUES (?)", 10);
            SqlIdentifier table = new SqlIdentifier("inventory");
            SqlIdentifier column = new SqlIdentifier("id");
            SqlValue id = new SqlValue.Int32(11);
            try {
                statementAdapter.create(new SqlCommand.Insert(table,
                        List.of(new SqlCommand.Assignment(column, id))));
            } catch (TuprelDatabaseException failure) {
                require(jdbc.queryForList("SELECT id FROM inventory WHERE id IN (?, ?) ORDER BY id",
                        Integer.class, 10, 11).equals(List.of(10)),
                        "Spring must retain its connection and only its own uncommitted write");
                throw failure;
            }
        }
    }

    private static final class RollbackTrigger extends RuntimeException {}

    private static ConfigurableApplicationContext start(Class<?> configuration) {
        return new SpringApplicationBuilder(configuration).web(WebApplicationType.NONE)
                .properties("spring.main.banner-mode=off", "logging.level.root=OFF").run();
    }

    private static void require(boolean condition, String problem) {
        if (!condition) throw new AssertionError(problem);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class NoDataSource {}

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class PrimaryDataSources {
        @Bean @Primary CountingDataSource preferredPool() { return new CountingDataSource(); }
        @Bean CountingDataSource anotherPool() { return new CountingDataSource(); }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class AmbiguousDataSources {
        @Bean CountingDataSource firstPool() { return new CountingDataSource(); }
        @Bean CountingDataSource secondPool() { return new CountingDataSource(); }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class ExplicitDatabase {
        @Bean CountingDataSource pool() { return new CountingDataSource(); }
        @Bean TuprelDatabase applicationDatabase(DataSource dataSource) {
            return PostgresqlDatabase.using(dataSource);
        }
    }

    static final class CountingDataSource implements DataSource, AutoCloseable {
        final AtomicInteger connections = new AtomicInteger();
        final AtomicInteger closes = new AtomicInteger();

        @Override public Connection getConnection() throws SQLException {
            connections.incrementAndGet();
            throw new SQLException("Fixture connection failure");
        }

        @Override public Connection getConnection(String username, String password) throws SQLException {
            return getConnection();
        }

        @Override public java.io.PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(java.io.PrintWriter writer) {}
        @Override public void setLoginTimeout(int seconds) {}
        @Override public int getLoginTimeout() { return 0; }
        @Override public java.util.logging.Logger getParentLogger() { return java.util.logging.Logger.getGlobal(); }
        @Override public <T> T unwrap(Class<T> type) throws java.sql.SQLException {
            if (type.isInstance(this)) return type.cast(this);
            throw new java.sql.SQLException("Unsupported unwrap");
        }
        @Override public boolean isWrapperFor(Class<?> type) { return type.isInstance(this); }
        @Override public void close() { closes.incrementAndGet(); }
    }
}
