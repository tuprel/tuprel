package dev.tuprel.cli;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuprel.migrate.AppliedMigration;
import dev.tuprel.migrate.MigrationChecksum;
import dev.tuprel.migrate.MigrationFile;
import dev.tuprel.migrate.MigrationHistory;
import dev.tuprel.migrate.MigrationId;
import dev.tuprel.migrate.postgresql.MigrationDatabaseSettings;
import dev.tuprel.migrate.postgresql.MigrationExecutionResult;
import dev.tuprel.migrate.postgresql.MigrationInspection;
import dev.tuprel.migrate.postgresql.MigrationPlan;
import dev.tuprel.migrate.postgresql.PostgresqlMigrationPlanCompiler;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MigrationDeployCommandTest {
    @TempDir Path root;

    @Test void helpNeverReadsEnvironmentFilesOrDatabase() {
        MigrationDeployCommand command = new MigrationDeployCommand(() -> { throw new AssertionError("No environment access"); });
        for (String option : List.of("--help", "-h")) {
            Result result = run(command, option);
            assertEquals(0, result.code()); assertTrue(result.out().contains("--approve-digest"));
            assertTrue(result.out().contains("session advisory lock")); assertTrue(result.out().contains("OUTCOME_UNKNOWN"));
            assertFalse(result.out().contains("\u001b")); assertEquals("", result.err());
        }
        assertFalse(Files.exists(root.resolve("missing")));
    }

    @Test void ambiguousMissingOrUnsupportedArgumentsAreRejectedBeforeEnvironmentAccess() {
        AtomicInteger reads = new AtomicInteger();
        MigrationDeployCommand command = new MigrationDeployCommand(() -> { reads.incrementAndGet(); throw new AssertionError("No environment access"); });
        for (String[] options : List.of(new String[]{}, new String[]{"--dry-run"}, new String[]{"--target-schema", "app"},
                new String[]{"--target-schema", "app", "--dry-run", "--approve-digest", "0".repeat(64)},
                new String[]{"--target-schema", "app", "--dry-run", "--dry-run"},
                new String[]{"--target-schema", "app", "--target-schema", "other", "--dry-run"},
                new String[]{"--target-schema", "app", "--approve-digest", "private_marker"},
                new String[]{"--target-schema", "app", "--approve-digest", "A".repeat(64)},
                new String[]{"--target-schema", "app", "--approve-digest", "0".repeat(64), "--approve-digest", "1".repeat(64)},
                new String[]{"--target-schema", "app", "--yes"}, new String[]{"--schema", "private_marker", "--dry-run"},
                new String[]{"--help", "extra"}, new String[]{"--target-schema", "--dry-run"})) {
            Result result = run(command, options);
            assertEquals(2, result.code()); assertTrue(result.err().contains("TUPREL-CLI-USAGE"));
            assertFalse(result.err().contains("private_marker")); assertEquals("", result.out());
        }
        assertEquals(0, reads.get()); assertFalse(Files.exists(root.resolve("missing")));
    }

    @Test void missingConfigurationIsSafeAndDoesNotUseAProjectDotenv() throws Exception {
        Files.writeString(root.resolve(".env"), "TUPREL_DATABASE_PASSWORD=private_marker\n");
        Result result = run(new MigrationDeployCommand(Map::of), "--target-schema", "app", "--dry-run");
        assertEquals(2, result.code()); assertTrue(result.err().contains("CONFIGURATION"));
        assertTrue(result.err().contains("TUPREL_DATABASE_PASSWORD")); assertFalse(result.err().contains("private_marker"));
        assertEquals("", result.out()); assertFalse(Files.exists(root.resolve("tuprel")));
    }

    @Test void previewShowsCanonicalSqlRisksAndDigestButNeverRawCommentsOrAutomaticApproval() throws Exception {
        MigrationInspection inspection = inspection("-- private_marker\nCREATE TABLE \"app\".\"items\" (\"id\" INTEGER);\n");
        StringWriter out = new StringWriter(), err = new StringWriter();
        assertEquals(0, MigrationDeployCommand.preview(inspection, new PrintWriter(out), new PrintWriter(err)));
        assertTrue(out.toString().contains("REVIEW_REQUIRED")); assertTrue(out.toString().contains("DDL_LOCKS"));
        assertTrue(out.toString().contains("DRIFT_NOT_CHECKED")); assertTrue(out.toString().contains("\"pg_catalog\".\"int4\""));
        assertTrue(out.toString().contains("Approval digest: " + inspection.plan().digest().value()));
        assertTrue(out.toString().contains("does not approve execution")); assertFalse(out.toString().contains("private_marker"));
        assertFalse(out.toString().contains("\u001b")); assertEquals("", err.toString());
        assertFalse(out.toString().contains("index builds block writes"));
        StringWriter indexed = new StringWriter();
        assertEquals(0, MigrationDeployCommand.preview(inspection("CREATE TABLE \"app\".\"items\" (\"id\" INTEGER);\n"
                + "CREATE INDEX \"items_id\" ON \"app\".\"items\" (\"id\");\n"), new PrintWriter(indexed), new PrintWriter(new StringWriter())));
        assertTrue(indexed.toString().contains("index builds block writes"));
    }

    @Test void aBlockedWholePlanShowsNoSupportedPrefixSqlOrApprovalDigest() throws Exception {
        MigrationInspection inspection = inspection("CREATE TABLE \"app\".\"items\" (\"id\" INTEGER);\nSELECT 'private_marker';\n");
        assertEquals(MigrationPlan.State.BLOCKED, inspection.plan().state());
        StringWriter out = new StringWriter(), err = new StringWriter();
        assertEquals(2, MigrationDeployCommand.preview(inspection, new PrintWriter(out), new PrintWriter(err)));
        assertTrue(out.toString().contains("BLOCKED")); assertTrue(err.toString().contains("BLOCKED"));
        assertFalse(out.toString().contains("CREATE TABLE")); assertFalse(out.toString().contains("private_marker"));
        assertFalse(out.toString().contains("Approval digest:")); assertTrue(out.toString().contains("line"));
    }

    @Test void uncertainAndPartialResultsKeepTheConfirmedPrefixVisibleWithoutClaimingRollback() {
        AppliedMigration first = new AppliedMigration(new MigrationId("20261031_120000"), "first", new MigrationChecksum("0".repeat(64)),
                Instant.EPOCH, Instant.EPOCH, 0, "fixture");
        StringWriter out = new StringWriter(), err = new StringWriter();
        assertEquals(2, MigrationDeployCommand.result(new MigrationExecutionResult(MigrationExecutionResult.Outcome.OUTCOME_UNKNOWN,
                MigrationExecutionResult.Failure.COMMIT, List.of(first)), new PrintWriter(out), new PrintWriter(err)));
        assertTrue(out.toString().contains("Deployment: OUTCOME_UNKNOWN")); assertTrue(out.toString().contains("Confirmed this invocation: 1"));
        assertTrue(out.toString().contains("APPLIED 20261031_120000 first")); assertTrue(err.toString().contains("Do not assume rollback"));
        assertTrue(err.toString().contains("fresh session under lock")); assertFalse(err.toString().contains("private_marker"));
    }

    @Test void noChangesIsSuccessfulAndAllFailureCategoriesHaveSafeDiagnostics() {
        StringWriter out = new StringWriter(), err = new StringWriter();
        assertEquals(0, MigrationDeployCommand.result(new MigrationExecutionResult(MigrationExecutionResult.Outcome.NO_CHANGES,
                MigrationExecutionResult.Failure.NONE, List.of()), new PrintWriter(out), new PrintWriter(err)));
        assertEquals("", err.toString());
        for (MigrationExecutionResult.Failure failure : MigrationExecutionResult.Failure.values()) {
            if (failure == MigrationExecutionResult.Failure.NONE) { continue; }
            StringWriter errors = new StringWriter();
            assertEquals(2, MigrationDeployCommand.result(new MigrationExecutionResult(MigrationExecutionResult.Outcome.REFUSED,
                    failure, List.of()), new PrintWriter(new StringWriter()), new PrintWriter(errors)));
            assertTrue(errors.toString().contains("TUPREL-MIGRATE-DEPLOY-" + failure)); assertFalse(errors.toString().contains("private_marker"));
        }
    }

    private static MigrationInspection inspection(String sql) throws Exception {
        MigrationDatabaseSettings settings = MigrationDatabaseSettings.fromEnvironment(Map.of("TUPREL_DATABASE_URL", "jdbc:postgresql://localhost:5432/fixture",
                "TUPREL_DATABASE_USER", "fixture", "TUPREL_DATABASE_PASSWORD", "private_marker"));
        MigrationHistory history = new MigrationHistory(false, List.of());
        MigrationFile file = MigrationFile.fromDirectory("20261031_120000_initial", sql.getBytes(StandardCharsets.UTF_8));
        return new MigrationInspection(new PostgresqlMigrationPlanCompiler().compile(settings.planTarget("app"), List.of(file), history), history);
    }
    private Result run(MigrationDeployCommand command, String... options) {
        String[] arguments = new String[options.length + 2]; arguments[0] = "migrate"; arguments[1] = "deploy";
        System.arraycopy(options, 0, arguments, 2, options.length);
        StringWriter out = new StringWriter(), err = new StringWriter();
        int code = command.run(arguments, new PrintWriter(out), new PrintWriter(err), root.resolve("missing"));
        return new Result(code, out.toString(), err.toString());
    }
    private record Result(int code, String out, String err) { }
}
