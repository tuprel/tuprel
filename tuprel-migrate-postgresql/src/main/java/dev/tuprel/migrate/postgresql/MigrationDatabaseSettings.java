package dev.tuprel.migrate.postgresql;

import dev.tuprel.migrate.MigrationHistoryException;
import java.net.URI;
import java.net.URISyntaxException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.io.PrintWriter;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.logging.Logger;
import javax.sql.DataSource;

/** Explicit production environment configuration; never loads dotenv files. */
public final class MigrationDatabaseSettings {
    private final String url;
    private final String user;
    private final String password;
    private final String sslMode;

    private MigrationDatabaseSettings(String url, String user, String password, String sslMode) {
        this.url = url; this.user = user; this.password = password; this.sslMode = sslMode;
    }

    /**
     * Validates before any connection. Rejects credentials in URLs, driver options,
     * implicit targets and remote connections without verify-full TLS. Loopback
     * defaults to disabled TLS; remote defaults to verify-full. Only sslmode is
     * accepted in a URL query, never arbitrary driver settings.
     * @param environment explicit environment map
     * @return redacted immutable settings
     * @throws MigrationHistoryException if required variables or URL are invalid
     */
    public static MigrationDatabaseSettings fromEnvironment(Map<String, String> environment) throws MigrationHistoryException {
        Objects.requireNonNull(environment, "environment");
        String url = environment.get("TUPREL_DATABASE_URL");
        String user = environment.get("TUPREL_DATABASE_USER");
        String password = environment.get("TUPREL_DATABASE_PASSWORD");
        if (url == null || url.length() > 2048 || !url.startsWith("jdbc:postgresql://")
                || user == null || user.isBlank() || user.length() > 256
                || password == null || password.isEmpty() || password.length() > 4096) { throw invalid(); }
        try {
            URI uri = new URI(url.substring(5));
            if (uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                    || uri.getPort() < 1 || uri.getPort() > 65535 || uri.getRawPath() == null
                    || !uri.getRawPath().matches("/[A-Za-z0-9_][A-Za-z0-9_-]*")) { throw invalid(); }
            boolean local = Set.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost());
            String query = uri.getRawQuery();
            String mode = local ? "disable" : "verify-full";
            if (query != null) {
                if (query.equals("sslmode=verify-full")) { mode = "verify-full"; }
                else if (local && query.equals("sslmode=disable")) { mode = "disable"; }
                else { throw invalid(); }
            }
            String base = query == null ? url : url.substring(0, url.indexOf('?'));
            return new MigrationDatabaseSettings(base, user, password, mode);
        } catch (URISyntaxException exception) { throw invalid(); }
    }

    /**
     * Supplies fresh DriverManager connections with finite driver timeouts.
     * Requires a PostgreSQL JDBC driver on the consumer runtime classpath.
     * @return datasource that never logs credentials
     */
    public DataSource dataSource() { return new ConfiguredDataSource(); }

    /**
     * Produces a proposed offline target binding, without opening a connection.
     * Binds the validated URL text (query removed), schema, configured username
     * and effective TLS mode; excludes the password. Does not verify live server
     * identity/current_user. A future runner must verify/rederive those itself.
     * @param targetSchema explicit target schema
     * @return redacted configured target
     * @throws MigrationHistoryException for invalid schema or noncanonical text
     */
    public MigrationPlanTarget planTarget(String targetSchema) throws MigrationHistoryException {
        try {
            dev.tuprel.sql.SqlIdentifier schema = new dev.tuprel.sql.SqlIdentifier(targetSchema);
            return new MigrationPlanTarget(schema, PlanEncoding.digest(output -> {
                PlanEncoding.text(output, "TUPREL-PG-TARGET"); output.writeInt(1);
                PlanEncoding.text(output, url); PlanEncoding.text(output, schema.name());
                PlanEncoding.text(output, user); PlanEncoding.text(output, sslMode);
            }));
        } catch (IllegalArgumentException | NullPointerException exception) { throw invalid(); }
    }

    /** Omits URL, username and password. */
    @Override public String toString() { return "MigrationDatabaseSettings[redacted]"; }

    private static MigrationHistoryException invalid() {
        return new MigrationHistoryException(MigrationHistoryException.Reason.CONFIGURATION);
    }

    private final class ConfiguredDataSource implements DataSource {
        @Override public Connection getConnection() throws SQLException {
            Properties properties = new Properties();
            properties.setProperty("user", user);
            properties.setProperty("password", password);
            properties.setProperty("sslmode", sslMode);
            properties.setProperty("connectTimeout", "5");
            properties.setProperty("loginTimeout", "5");
            properties.setProperty("socketTimeout", "30");
            properties.setProperty("readOnlyMode", "transaction");
            return DriverManager.getConnection(url, properties);
        }
        @Override public Connection getConnection(String username, String secret) throws SQLException {
            throw new SQLFeatureNotSupportedException("Use configured credentials");
        }
        @Override public PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(PrintWriter writer) throws SQLException { throw new SQLFeatureNotSupportedException("Logging not supported"); }
        @Override public void setLoginTimeout(int seconds) throws SQLException { throw new SQLFeatureNotSupportedException("Timeout is fixed"); }
        @Override public int getLoginTimeout() { return 5; }
        @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { throw new SQLFeatureNotSupportedException("Logging not supported"); }
        @Override public boolean isWrapperFor(Class<?> type) { return type.isInstance(this); }
        @Override public <T> T unwrap(Class<T> type) throws SQLException {
            if (type.isInstance(this)) { return type.cast(this); }
            throw new SQLException("Not a wrapper");
        }
    }
}
