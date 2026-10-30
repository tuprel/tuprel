package dev.tuprel.playground;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Read-only JDBC check using the playground .env; never creates or changes database objects. */
public final class CheckConnection {
    private CheckConnection() { }

    public static void main(String[] arguments) {
        try (Connection connection = LocalDatabase.connect().getConnection()) {
            if (!"tuprel_devtest".equals(connection.getCatalog())) {
                throw new SQLException("Unexpected database", "08004");
            }
            connection.setReadOnly(true);
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT current_database(), current_user, current_setting('server_version'), inet_server_port()")) {
                statement.setQueryTimeout(5);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next() || !"tuprel_devtest".equals(result.getString(1))) {
                        throw new SQLException("Unexpected database", "08004");
                    }
                    System.out.println("JDBC OK: database=tuprel_devtest, user=" + result.getString(2)
                            + ", PostgreSQL=" + result.getString(3) + ", port=" + result.getInt(4));
                }
            }
        } catch (Exception failure) {
            String state = failure instanceof SQLException sqlFailure ? sqlFailure.getSQLState() : "n/a";
            System.err.println("JDBC check failed (" + failure.getClass().getSimpleName() + ", SQLState=" + state
                    + "). Fill the three TUPREL_TEST_* entries in the playground .env and check the local service.");
            System.exit(1);
        }
    }
}
