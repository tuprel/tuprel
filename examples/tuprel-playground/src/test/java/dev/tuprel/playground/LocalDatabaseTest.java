package dev.tuprel.playground;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class LocalDatabaseTest {
    @Test
    void bootstrapConfigurationCannotTargetAnotherDatabaseOrOverrideTheSearchPath() {
        for (String url : List.of("jdbc:postgresql://localhost:5432/personal_database",
                "jdbc:postgresql://remote:5432/tuprel_devtest",
                "jdbc:postgresql://localhost/tuprel_devtest",
                "jdbc:postgresql://localhost:5432/tuprel_devtest?currentSchema=public",
                "jdbc:postgresql://user:SECRET@localhost:5432/tuprel_devtest",
                "jdbc:postgresql://localhost:5432/tuprel_devtest?password=SECRET",
                "jdbc:postgresql://localhost:5432/SECRET with space")) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> LocalDatabase.validateUrl(url));
            assertFalse(failure.toString().contains("SECRET"));
            assertNull(failure.getCause());
        }
        assertThrows(IllegalArgumentException.class, () -> LocalDatabase.validateUrl(null));
    }

    @Test
    void acceptsExplicitLocalPortsWithoutAssumingTheServersConfiguration() {
        for (String host : List.of("localhost", "127.0.0.1", "[::1]")) {
            assertDoesNotThrow(() -> LocalDatabase.validateUrl("jdbc:postgresql://" + host + ":5544/tuprel_devtest"));
        }
    }
}
