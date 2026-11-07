package consumer;

import dev.tuprel.postgresql.PostgresqlDatabase;
import dev.tuprel.runtime.TuprelDatabase;
import dev.tuprel.runtime.TuprelDatabaseException;
import java.sql.Connection;
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

public final class ConsumerApplication {
    private ConsumerApplication() {}

    public static void main(String[] args) {
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
