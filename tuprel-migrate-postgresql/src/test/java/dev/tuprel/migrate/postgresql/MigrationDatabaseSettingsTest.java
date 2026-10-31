package dev.tuprel.migrate.postgresql;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuprel.migrate.MigrationHistoryException;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

class MigrationDatabaseSettingsTest {
    @Test void rejectsImplicitCredentialsAndArbitraryDriverOptionsSafely() {
        for (String url : new String[]{"jdbc:postgresql:db", "jdbc:postgresql://host/db", "jdbc:postgresql://host:0/db",
                "jdbc:postgresql://user:private_value@host:5432/db", "jdbc:postgresql://host:5432/db?password=private_value",
                "jdbc:postgresql://host:5432/db?sslmode=disable", "jdbc:postgresql://host:5432/db?sslmode=require",
                "jdbc:postgresql://host:5432/db?socketFactory=private_value", "jdbc:postgresql://host:5432/db#private_value"}) {
            MigrationHistoryException failure = assertThrows(MigrationHistoryException.class,
                    () -> MigrationDatabaseSettings.fromEnvironment(env(url)));
            assertFalse(failure.toString().contains("private_value"));
            assertNull(failure.getCause());
        }
        assertThrows(MigrationHistoryException.class, () -> MigrationDatabaseSettings.fromEnvironment(Map.of()));
    }
    @Test void configuresTlsAndFiniteTimeoutsWithoutLoggingSecrets() throws Exception {
        CapturingDriver driver = new CapturingDriver();
        DriverManager.registerDriver(driver);
        try {
            for (String host : new String[]{"localhost", "127.0.0.1", "[::1]", "database.example"}) {
                MigrationDatabaseSettings settings = MigrationDatabaseSettings.fromEnvironment(env("jdbc:postgresql://" + host + ":5432/test"));
                assertFalse(settings.toString().contains("private_value"));
                assertFalse(settings.toString().contains(host));
                try (Connection connection = settings.dataSource().getConnection()) { assertNotNull(connection); }
                assertEquals(host.equals("database.example") ? "verify-full" : "disable", driver.captured.getProperty("sslmode"));
                assertEquals("5", driver.captured.getProperty("connectTimeout"));
                assertEquals("30", driver.captured.getProperty("socketTimeout"));
                assertEquals("transaction", driver.captured.getProperty("readOnlyMode"));
                assertNull(settings.dataSource().getLogWriter());
            }
        } finally { DriverManager.deregisterDriver(driver); }
    }
    private static Map<String, String> env(String url) {
        return Map.of("TUPREL_DATABASE_URL", url, "TUPREL_DATABASE_USER", "test_user", "TUPREL_DATABASE_PASSWORD", "private_value");
    }
    private static final class CapturingDriver implements Driver {
        Properties captured;
        @Override public Connection connect(String url, Properties properties) {
            captured = new Properties(); captured.putAll(properties);
            return (Connection) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, args) -> { if (method.getName().equals("close")) { return null; } throw new UnsupportedOperationException(); });
        }
        @Override public boolean acceptsURL(String url) { return url.startsWith("jdbc:postgresql:"); }
        @Override public DriverPropertyInfo[] getPropertyInfo(String url, Properties properties) { return new DriverPropertyInfo[0]; }
        @Override public int getMajorVersion() { return 1; }
        @Override public int getMinorVersion() { return 0; }
        @Override public boolean jdbcCompliant() { return false; }
        @Override public Logger getParentLogger() { return Logger.getLogger("test"); }
    }
}
