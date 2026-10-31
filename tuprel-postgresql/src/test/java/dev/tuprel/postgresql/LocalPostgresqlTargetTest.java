package dev.tuprel.postgresql;

import dev.tuprel.testsupport.LocalPostgresqlTarget;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class LocalPostgresqlTargetTest {
    @Test
    void acceptsOnlyExplicitLoopbackTargetsInTheDedicatedDatabase() {
        for (String host : List.of("localhost", "127.0.0.1", "[::1]")) {
            assertDoesNotThrow(() -> LocalPostgresqlTarget.validate(
                    "jdbc:postgresql://" + host + ":5544/tuprel_devtest"));
        }
    }

    @Test
    void rejectsOtherDatabasesHostsImplicitPortsAndConnectionOverridesWithoutEchoingSecrets() {
        for (String url : List.of("jdbc:postgresql://localhost:5432/postgres",
                "jdbc:postgresql://remote:5432/tuprel_devtest", "jdbc:postgresql:tuprel_devtest",
                "jdbc:postgresql://localhost/tuprel_devtest", "jdbc:postgresql://localhost:0/tuprel_devtest",
                "jdbc:postgresql://localhost:65536/tuprel_devtest",
                "jdbc:postgresql://localhost:5432/tuprel_devtest?password=SECRET",
                "jdbc:postgresql://user:SECRET@localhost:5432/tuprel_devtest",
                "jdbc:postgresql://localhost:5432/tuprel_devtest#SECRET",
                "jdbc:postgresql://localhost:5432/tuprel_devtest%2fother",
                "jdbc:postgresql://localhost:5432/SECRET with space")) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> LocalPostgresqlTarget.validate(url));
            assertFalse(failure.toString().contains("SECRET"));
            assertFalse(failure.getCause() != null);
        }
        assertThrows(IllegalArgumentException.class, () -> LocalPostgresqlTarget.validate(null));
    }
}
