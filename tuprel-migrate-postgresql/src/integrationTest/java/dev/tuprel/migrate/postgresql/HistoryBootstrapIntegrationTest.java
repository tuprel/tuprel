package dev.tuprel.migrate.postgresql;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuprel.migrate.MigrationHistoryException;
import dev.tuprel.sql.SqlIdentifier;
import dev.tuprel.testsupport.TestPostgresql;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;

/** Executes only trusted internal bootstrap DDL in fresh fixture-owned schemas. */
class HistoryBootstrapIntegrationTest {
    @Test void committedCanonicalBootstrapIsRecognisedWithoutChangingTheReadOnlyObserver() throws Exception {
        try (TestPostgresql database = TestPostgresql.open()) {
            String schema = database.dataSource().getCurrentSchema();
            assertFalse(new PostgresqlMigrationHistory(database.dataSource()).read(schema).initialised());
            try (Connection connection = database.dataSource().getConnection()) {
                connection.setAutoCommit(false);
                create(connection, schema);
                connection.commit();
            }
            assertTrue(new PostgresqlMigrationHistory(database.dataSource()).read(schema).initialised());
            assertTrue(new PostgresqlMigrationHistory(database.dataSource()).read(schema).applied().isEmpty());
        }
    }

    @Test void rolledBackBootstrapLeavesHistoryUninitialised() throws Exception {
        try (TestPostgresql database = TestPostgresql.open()) {
            String schema = database.dataSource().getCurrentSchema();
            try (Connection connection = database.dataSource().getConnection()) {
                connection.setAutoCommit(false);
                create(connection, schema);
                connection.rollback();
            }
            assertFalse(new PostgresqlMigrationHistory(database.dataSource()).read(schema).initialised());
        }
    }

    @Test void collisionFailsWithoutAdoptingOrReplacingAnExistingObject() throws Exception {
        try (TestPostgresql database = TestPostgresql.open()) {
            String schema = database.dataSource().getCurrentSchema();
            try (Connection connection = database.dataSource().getConnection(); Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE _tuprel_migrations (sentinel integer)");
                statement.execute("INSERT INTO _tuprel_migrations VALUES (73)");
                connection.setAutoCommit(false);
                assertEquals("42P07", assertThrows(SQLException.class, () -> create(connection, schema)).getSQLState());
                connection.rollback();
                try (java.sql.ResultSet rows = statement.executeQuery("SELECT sentinel FROM _tuprel_migrations")) {
                    assertTrue(rows.next()); assertEquals(73, rows.getInt(1)); assertFalse(rows.next());
                }
            }
            assertEquals(MigrationHistoryException.Reason.INCOMPATIBLE,
                    assertThrows(MigrationHistoryException.class,
                            () -> new PostgresqlMigrationHistory(database.dataSource()).read(schema)).reason());
        }
    }

    @Test void explicitSchemaCannotBeRedirectedBySearchPath() throws Exception {
        try (TestPostgresql database = TestPostgresql.open()) {
            String target = database.dataSource().getCurrentSchema();
            String other = new SqlIdentifier(target + "_other").name();
            boolean created = false;
            try {
                // Both schemas must belong to this fixture's database: separate
                // TestPostgresql instances have separate databases in CI mode.
                try (Connection connection = database.dataSource().getConnection(); Statement statement = connection.createStatement()) {
                    statement.setQueryTimeout(10);
                    statement.execute("CREATE SCHEMA \"" + other + "\"");
                    created = true;
                    connection.setSchema(other);
                    connection.setAutoCommit(false);
                    create(connection, target);
                    connection.commit();
                }
                assertTrue(new PostgresqlMigrationHistory(database.dataSource()).read(target).initialised());
                assertFalse(new PostgresqlMigrationHistory(database.dataSource()).read(other).initialised());
            } finally {
                if (created) {
                    try (Connection connection = database.dataSource().getConnection(); Statement statement = connection.createStatement()) {
                        statement.setQueryTimeout(10);
                        statement.execute("DROP SCHEMA \"" + other + "\" CASCADE");
                    }
                }
            }
        }
    }

    private static void create(Connection connection, String schema) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(10);
            statement.execute(MigrationHistoryTable.createSql(new SqlIdentifier(schema)));
        }
    }
}
