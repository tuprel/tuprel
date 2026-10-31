package dev.tuprel.migrate.postgresql;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuprel.migrate.AppliedMigration;
import dev.tuprel.migrate.MigrationChecksum;
import dev.tuprel.migrate.MigrationId;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class MigrationRunnerApiTest {
    @Test void confirmedEvidenceIsImmutableOrderedAndIndependentOfWholeInvocationFailure() {
        List<AppliedMigration> prefix = new ArrayList<>(List.of(applied("20261031_120000")));
        MigrationExecutionResult result = new MigrationExecutionResult(MigrationExecutionResult.Outcome.OUTCOME_UNKNOWN,
                MigrationExecutionResult.Failure.COMMIT, prefix);
        prefix.clear(); assertEquals(1, result.confirmed().size());
        assertThrows(UnsupportedOperationException.class, () -> result.confirmed().clear());
        assertEquals(MigrationExecutionResult.Outcome.OUTCOME_UNKNOWN, result.outcome());
        assertFalse(result.toString().contains("private_marker"));
        assertThrows(IllegalArgumentException.class, () -> new MigrationExecutionResult(MigrationExecutionResult.Outcome.FAILED,
                MigrationExecutionResult.Failure.CLEANUP, List.of(applied("20261031_120001"), applied("20261031_120000"))));
        assertThrows(IllegalArgumentException.class, () -> new MigrationExecutionResult(MigrationExecutionResult.Outcome.FAILED,
                MigrationExecutionResult.Failure.CLEANUP, List.of(applied("20261031_120000"), applied("20261031_120000"))));
    }

    @Test void contradictorySuccessAndFailureLabelsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new MigrationExecutionResult(MigrationExecutionResult.Outcome.APPLIED,
                MigrationExecutionResult.Failure.NONE, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new MigrationExecutionResult(MigrationExecutionResult.Outcome.NO_CHANGES,
                MigrationExecutionResult.Failure.NONE, List.of(applied("20261031_120000"))));
        assertThrows(IllegalArgumentException.class, () -> new MigrationExecutionResult(MigrationExecutionResult.Outcome.OUTCOME_UNKNOWN,
                MigrationExecutionResult.Failure.NONE, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new MigrationExecutionResult(MigrationExecutionResult.Outcome.APPLIED,
                MigrationExecutionResult.Failure.COMMIT, List.of(applied("20261031_120000"))));
    }

    @Test void invalidArgumentsAndSystemTargetsDoNotBorrowConnections() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        PostgresqlMigrationRunner runner = new PostgresqlMigrationRunner(failing(attempts), settings());
        assertThrows(NullPointerException.class, () -> runner.inspect(null, Path.of("migrations")));
        assertThrows(NullPointerException.class, () -> runner.deploy("app", Path.of("migrations"), null));
        assertEquals(MigrationExecutionResult.Failure.CONFIGURATION,
                runner.deploy("pg_catalog", Path.of("migrations"), digest()).failure());
        assertEquals(MigrationExecutionResult.Failure.CONFIGURATION,
                assertThrows(MigrationInspectionException.class, () -> runner.inspect("information_schema", Path.of("migrations"))).reason());
        assertEquals(0, attempts.get()); assertFalse(runner.toString().contains("private_marker"));
    }

    @Test void inspectionAndDeploymentNeverExposeOrRetryAConnectionFailure() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        PostgresqlMigrationRunner runner = new PostgresqlMigrationRunner(failing(attempts), settings());
        MigrationInspectionException error = assertThrows(MigrationInspectionException.class, () -> runner.inspect("app", Path.of("migrations")));
        assertEquals(MigrationExecutionResult.Failure.CONNECTION, error.reason());
        assertNull(error.getCause()); assertEquals(0, error.getSuppressed().length); assertFalse(error.toString().contains("private_marker"));
        assertEquals(1, attempts.get());
        MigrationExecutionResult result = runner.deploy("app", Path.of("migrations"), digest());
        assertEquals(MigrationExecutionResult.Outcome.REFUSED, result.outcome());
        assertEquals(MigrationExecutionResult.Failure.CONNECTION, result.failure()); assertEquals(2, attempts.get());
        assertTrue(result.confirmed().isEmpty()); assertFalse(result.toString().contains("private_marker"));
    }

    private static AppliedMigration applied(String id) {
        return new AppliedMigration(new MigrationId(id), "private_marker", new MigrationChecksum("0".repeat(64)),
                Instant.EPOCH, Instant.EPOCH, 0, "fixture");
    }
    private static MigrationPlanDigest digest() { return new MigrationPlanDigest("0".repeat(64)); }
    private static MigrationDatabaseSettings settings() throws Exception {
        return MigrationDatabaseSettings.fromEnvironment(Map.of("TUPREL_DATABASE_URL", "jdbc:postgresql://localhost:5432/fixture",
                "TUPREL_DATABASE_USER", "fixture", "TUPREL_DATABASE_PASSWORD", "private_marker"));
    }
    private static DataSource failing(AtomicInteger attempts) {
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class},
                (object, method, arguments) -> { attempts.incrementAndGet(); throw new SQLException("private_marker", "08006"); });
    }
}
