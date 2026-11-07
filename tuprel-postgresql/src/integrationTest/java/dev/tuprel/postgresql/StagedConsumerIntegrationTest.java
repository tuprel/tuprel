package dev.tuprel.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.tuprel.testsupport.StagedJavaConsumer;
import dev.tuprel.testsupport.TestPostgresql;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;

class StagedConsumerIntegrationTest {
    @Test void generatesCompilesAndRunsOutsideTheCheckout(@TempDir Path fixture) throws Exception {
        StagedJavaConsumer consumer = new StagedJavaConsumer(fixture);
        consumer.generateAndCompile();
        try (TestPostgresql fixtureDatabase = TestPostgresql.open()) {
            PGSimpleDataSource dataSource = fixtureDatabase.dataSource();
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                assertEquals(17, connection.getMetaData().getDatabaseMajorVersion());
                statement.execute("""
                        CREATE TABLE "Product" ("id" INTEGER PRIMARY KEY, "name" TEXT NOT NULL, "stock" INTEGER NOT NULL);
                        CREATE TABLE "Purchase" ("id" INTEGER PRIMARY KEY, "productId" INTEGER NOT NULL, "quantity" INTEGER NOT NULL);
                        """);
            }
            consumer.exercise(dataSource);
            consumer.exercise(dataSource, "--verify-upgrade");
        }
    }
}
