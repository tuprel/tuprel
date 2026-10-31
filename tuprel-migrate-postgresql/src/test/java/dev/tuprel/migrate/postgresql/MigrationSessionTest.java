package dev.tuprel.migrate.postgresql;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuprel.sql.SqlIdentifier;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class MigrationSessionTest {
    @Test void lockNamespaceMatchesIndependentBigEndianSha256Vector() {
        // Independently encoded with PowerShell/.NET using ADR-0016's S/I32 encoding.
        assertEquals(2312635700033408174L, MigrationSession.key(new SqlIdentifier("app")));
        assertNotEquals(MigrationSession.key(new SqlIdentifier("app")), MigrationSession.key(new SqlIdentifier("other")));
    }

    @Test void systemSchemasAndInvalidDeadlinesNeverConnect() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        DataSource source = failing(attempts);
        assertEquals(MigrationSessionException.Reason.TARGET,
                assertThrows(MigrationSessionException.class, () -> MigrationSession.open(source, settings(), "pg_catalog")).reason());
        for (Duration timeout : new Duration[]{Duration.ZERO, Duration.ofSeconds(-1), Duration.ofSeconds(31)}) {
            assertEquals(MigrationSessionException.Reason.TARGET,
                    assertThrows(MigrationSessionException.class, () -> MigrationSession.open(source, settings(), "app", timeout)).reason());
        }
        assertEquals(0, attempts.get());
    }

    @Test void connectionFailureIsRedactedAndNeverRetried() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        MigrationSessionException exception = assertThrows(MigrationSessionException.class,
                () -> MigrationSession.open(failing(attempts), settings(), "app"));
        assertEquals(1, attempts.get()); assertEquals(MigrationSessionException.Reason.CONNECTION, exception.reason());
        assertNull(exception.getCause()); assertEquals(0, exception.getSuppressed().length);
        assertFalse(exception.toString().contains("private_marker"));
    }

    private static MigrationDatabaseSettings settings() throws Exception {
        return MigrationDatabaseSettings.fromEnvironment(Map.of("TUPREL_DATABASE_URL", "jdbc:postgresql://localhost:5432/fixture",
                "TUPREL_DATABASE_USER", "fixture", "TUPREL_DATABASE_PASSWORD", "private_marker"));
    }
    private static DataSource failing(AtomicInteger attempts) {
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class},
                (object, method, arguments) -> { attempts.incrementAndGet(); throw new SQLException("private_marker"); });
    }
}
