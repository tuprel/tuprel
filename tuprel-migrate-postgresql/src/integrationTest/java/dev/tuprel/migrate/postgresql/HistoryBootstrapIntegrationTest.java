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
        try (TestPostgresql first = TestPostgresql.open(); TestPostgresql second = TestPostgresql.open()) {
            String target = first.dataSource().getCurrentSchema();
            try (Connection connection = second.dataSource().getConnection()) {
                connection.setAutoCommit(false);
                create(connection, target);
                connection.commit();
            }
            assertTrue(new PostgresqlMigrationHistory(first.dataSource()).read(target).initialised());
            assertFalse(new PostgresqlMigrationHistory(second.dataSource())
                    .read(second.dataSource().getCurrentSchema()).initialised());
        }
    }

    private static void create(Connection connection, String schema) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(10);
            statement.execute(MigrationHistoryTable.createSql(new SqlIdentifier(schema)));
        }
    }
}
