package dev.tuprel.migrate.postgresql;

import static dev.tuprel.migrate.postgresql.MigrationPlan.Risk;
import static org.junit.jupiter.api.Assertions.*;

import dev.tuprel.migrate.AppliedMigration;
import dev.tuprel.migrate.MigrationChecksum;
import dev.tuprel.migrate.MigrationFile;
import dev.tuprel.migrate.MigrationHistory;
import dev.tuprel.migrate.MigrationId;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class PostgresqlMigrationPlanCompilerTest {
    private final PostgresqlMigrationPlanCompiler compiler = new PostgresqlMigrationPlanCompiler();
    private static final String CREATE = "CREATE TABLE \"app\".\"Thing\" (\"id\" UUID PRIMARY KEY);\n";
    private static final MigrationHistory EMPTY = new MigrationHistory(false, List.of());

    @Test void knownPlanRequiresReviewAndNeverClaimsPhysicalDriftChecked() throws Exception {
        MigrationPlan plan = compile(List.of(file(1, "first", CREATE), file(2, "index", "CREATE INDEX \"idx\" ON \"app\".\"Thing\" (\"id\");")), EMPTY);
        assertEquals(MigrationPlan.State.REVIEW_REQUIRED, plan.state());
        assertTrue(plan.risks().containsAll(Set.of(Risk.PHYSICAL_DRIFT_NOT_CHECKED, Risk.DDL_LOCKS, Risk.INDEX_WRITE_BLOCKING, Risk.INDEX_BUILD_COST)));
        assertEquals(2, plan.scripts().size());
        assertTrue(plan.preview().startsWith("-- Migration 20261031_120001_first\nCREATE TABLE"));
        assertTrue(plan.preview().endsWith("CREATE INDEX \"idx\" ON \"app\".\"Thing\" (\"id\");\n"));
        assertFalse(plan.toString().contains("CREATE")); assertFalse(plan.toString().contains("private_password"));
        assertFalse(target().toString().contains("reviewer"));
    }

    @Test void catalogAndHistoryIterationOrderNeverChangeDigestOrPreview() throws Exception {
        MigrationFile first = file(1, "first", CREATE);
        MigrationFile second = file(2, "second", "ALTER TABLE \"app\".\"Thing\" ADD COLUMN \"value\" TEXT;");
        MigrationFile third = file(3, "third", "CREATE INDEX \"idx\" ON \"app\".\"Thing\" (\"id\");");
        MigrationHistory a = new MigrationHistory(true, List.of(row(first, 1), row(second, 2)));
        MigrationHistory b = new MigrationHistory(true, List.of(row(second, 2), row(first, 1)));
        MigrationPlan left = compile(List.of(third, second, first), a);
        MigrationPlan right = compile(List.of(first, second, third), b);
        assertEquals(left.digest(), right.digest()); assertEquals(left.preview(), right.preview());
    }

    @Test void exactSourceBytesAndNamesAreBoundEvenWhenSqlPreviewIsEquivalent() throws Exception {
        MigrationFile first = file(1, "first", CREATE);
        MigrationFile comment = file(1, "first", "-- private_comment\n" + CREATE);
        MigrationFile renamed = file(1, "renamed", CREATE);
        MigrationPlan original = compile(List.of(first), EMPTY);
        MigrationPlan changed = compile(List.of(comment), EMPTY);
        assertEquals(original.preview(), changed.preview());
        assertNotEquals(original.digest(), changed.digest());
        assertNotEquals(original.digest(), compile(List.of(renamed), EMPTY).digest());
        assertFalse(changed.preview().contains("private_comment"));
    }

    @Test void endpointSchemaPrincipalAndTlsAreBoundButPasswordsAreExcluded() throws Exception {
        MigrationPlanTarget original = target();
        for (MigrationPlanTarget changed : List.of(
                settings("jdbc:postgresql://127.0.0.1:5432/demo", "reviewer", "private_password").planTarget("app"),
                settings("jdbc:postgresql://localhost:5433/demo", "reviewer", "private_password").planTarget("app"),
                settings("jdbc:postgresql://localhost:5432/other", "reviewer", "private_password").planTarget("app"),
                settings("jdbc:postgresql://localhost:5432/demo?sslmode=verify-full", "reviewer", "private_password").planTarget("app"),
                settings("jdbc:postgresql://localhost:5432/demo", "other_reviewer", "private_password").planTarget("app"),
                settings("jdbc:postgresql://localhost:5432/demo", "reviewer", "private_password").planTarget("other"))) {
            assertNotEquals(original.fingerprint(), changed.fingerprint());
            assertNotEquals(compiler.compile(original, List.of(file(1, "first", CREATE)), EMPTY).digest(),
                    compiler.compile(changed, List.of(file(1, "first", CREATE)), EMPTY).digest());
        }
        assertEquals(original.fingerprint(), settings("jdbc:postgresql://localhost:5432/demo", "reviewer", "rotated_secret").planTarget("app").fingerprint());
        assertNotEquals(settings("jdbc:postgresql://localhost:5432/demo", "bc", "p").planTarget("a").fingerprint(),
                settings("jdbc:postgresql://localhost:5432/demo", "c", "p").planTarget("ab").fingerprint());
    }

    @Test void everyObservedHistoryFieldInvalidatesTheDigest() throws Exception {
        MigrationFile first = file(1, "first", CREATE);
        MigrationFile pending = file(2, "pending", "ALTER TABLE \"app\".\"Thing\" ADD COLUMN \"value\" TEXT;");
        AppliedMigration row = row(first, 1);
        List<MigrationFile> files = List.of(first, pending);
        MigrationPlanDigest baseline = compile(files, new MigrationHistory(true, List.of(row))).digest();
        for (AppliedMigration changed : List.of(
                new AppliedMigration(new MigrationId("20261031_120003"), row.name(), row.checksum(), row.startedAt(), row.finishedAt(), row.executionMillis(), row.engineVersion()),
                new AppliedMigration(row.id(), "renamed", row.checksum(), row.startedAt(), row.finishedAt(), row.executionMillis(), row.engineVersion()),
                new AppliedMigration(row.id(), row.name(), new MigrationChecksum("0".repeat(64)), row.startedAt(), row.finishedAt(), row.executionMillis(), row.engineVersion()),
                new AppliedMigration(row.id(), row.name(), row.checksum(), row.startedAt().plusNanos(1), row.finishedAt(), row.executionMillis(), row.engineVersion()),
                new AppliedMigration(row.id(), row.name(), row.checksum(), row.startedAt(), row.finishedAt().plusNanos(1), row.executionMillis(), row.engineVersion()),
                new AppliedMigration(row.id(), row.name(), row.checksum(), row.startedAt(), row.finishedAt(), row.executionMillis() + 1, row.engineVersion()),
                new AppliedMigration(row.id(), row.name(), row.checksum(), row.startedAt(), row.finishedAt(), row.executionMillis(), "next_version"))) {
            assertNotEquals(baseline, compile(files, new MigrationHistory(true, List.of(changed))).digest());
        }
        assertNotEquals(compile(files, EMPTY).digest(), compile(files, new MigrationHistory(true, List.of())).digest());
    }

    @Test void unknownDestructiveAndCommentOnlyScriptsBlockTheWholePlan() throws Exception {
        for (String source : List.of("DROP TABLE \"app\".\"Thing\";", "-- draft\n", "DO $$ private_password $$;", CREATE + "COMMIT;")) {
            MigrationPlan plan = compile(List.of(file(1, "first", CREATE), file(2, "unknown", source)), EMPTY);
            assertEquals(MigrationPlan.State.BLOCKED, plan.state());
            assertThrows(IllegalStateException.class, plan::preview);
            assertFalse(plan.scripts().getLast().diagnostic().orElseThrow().toString().contains("private_password"));
        }
    }

    @Test void integrityAndTargetProblemsAreNeverAnExecutableSubset() throws Exception {
        MigrationFile file = file(1, "first", CREATE);
        MigrationFile changed = file(1, "first", CREATE.replace("UUID", "INTEGER"));
        MigrationPlan badHistory = compile(List.of(changed), new MigrationHistory(true, List.of(row(file, 1))));
        assertEquals(MigrationPlan.State.BLOCKED, badHistory.state()); assertTrue(badHistory.risks().contains(Risk.HISTORY_DIVERGENCE));
        MigrationPlan mismatch = compile(List.of(file(1, "first", CREATE.replace("\"app\"", "\"other\""))), EMPTY);
        assertTrue(mismatch.risks().contains(Risk.TARGET_MISMATCH)); assertEquals(MigrationPlan.State.BLOCKED, mismatch.state());
        MigrationPlan system = compiler.compile(settings("jdbc:postgresql://localhost:5432/demo", "reviewer", "p").planTarget("pg_catalog"), List.of(), EMPTY);
        assertTrue(system.risks().contains(Risk.TARGET_UNSUPPORTED)); assertEquals(MigrationPlan.State.BLOCKED, system.state());
    }

    @Test void knownCrossStatementConflictsAreRejectedWithoutInventingCatalogState() throws Exception {
        for (String tail : List.of(
                CREATE,
                "CREATE INDEX \"Thing\" ON \"app\".\"Thing\" (\"id\");",
                "ALTER TABLE \"app\".\"Thing\" ADD COLUMN \"id\" TEXT;",
                "CREATE INDEX \"idx\" ON \"app\".\"Thing\" (\"unknown_column\");")) {
            MigrationPlan plan = compile(List.of(file(1, "conflict", CREATE + tail)), EMPTY);
            assertEquals(MigrationPlan.State.BLOCKED, plan.state()); assertTrue(plan.risks().contains(Risk.PLAN_CONFLICT));
        }
        String add = "ALTER TABLE \"app\".\"existing\" ADD COLUMN \"new_column\" TEXT;";
        String index = "CREATE INDEX \"idx\" ON \"app\".\"existing\" (\"old_column\");";
        assertEquals(MigrationPlan.State.REVIEW_REQUIRED, compile(List.of(file(1, "known", add + index)), EMPTY).state());
        for (String prefix : List.of(add, index)) {
            MigrationPlan conflict = compile(List.of(file(1, "conflict", prefix + "CREATE TABLE \"app\".\"existing\" (\"id\" UUID);")), EMPTY);
            assertTrue(conflict.risks().contains(Risk.PLAN_CONFLICT));
        }
    }

    @Test void appliedSqlIsNotReparsedOrReappliedAndNoChangesStillDoesNotProveDrift() throws Exception {
        MigrationFile old = file(1, "old", "DROP TABLE \"app\".\"previous\";");
        MigrationPlan plan = compile(List.of(old), new MigrationHistory(true, List.of(row(old, 1))));
        assertEquals(MigrationPlan.State.NO_CHANGES, plan.state()); assertEquals("", plan.preview()); assertTrue(plan.scripts().isEmpty());
        assertTrue(plan.risks().contains(Risk.PHYSICAL_DRIFT_NOT_CHECKED));
    }

    @Test void boundedInputsAndWholePlanBudgetsCannotBeBypassed() throws Exception {
        MigrationFile first = file(1, "first", CREATE);
        assertThrows(IllegalArgumentException.class, () -> compile(List.of(first, first), EMPTY));
        List<MigrationFile> many = new ArrayList<>();
        for (int script = 1; script <= 17; script++) {
            StringBuilder sql = new StringBuilder();
            for (int column = 0; column < 256; column++) {
                sql.append("ALTER TABLE \"app\".\"existing\" ADD COLUMN \"c").append(script).append('_').append(column).append("\" TEXT;\n");
            }
            many.add(file(script, "many", sql.toString()));
        }
        assertTrue(compile(many, EMPTY).risks().contains(Risk.RESOURCE_LIMIT));
    }

    @Test void resultsAreImmutableAndSharedCompilerIsDeterministicAcrossThreads() throws Exception {
        MigrationPlanTarget target = target();
        List<MigrationFile> files = new ArrayList<>(List.of(file(1, "first", CREATE)));
        MigrationPlan result = compiler.compile(target, files, EMPTY); files.clear();
        assertEquals(1, result.scripts().size());
        assertThrows(UnsupportedOperationException.class, () -> result.scripts().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.risks().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.scripts().getFirst().risks().clear());
        assertEquals(1, IntStream.range(0, 32).parallel().mapToObj(ignored -> compiler.compile(target, List.of(file(1, "first", CREATE)), EMPTY).digest()).distinct().count());
    }

    @Test void independentPowerShellGoldenVectorFixesTheBinaryProtocol() throws Exception {
        assertEquals("da2917ea228fc91fe41e388e8213ef1d71d39a93e97017a76c39f4ac4e21a55c", target().fingerprint().value());
        MigrationPlan plan = compile(List.of(file(1, "first", CREATE)), EMPTY);
        assertEquals("96b368e8fe05a1ca823fd1fb38f95f8a9399667b75cc75493f538c34f2de8e08", plan.scripts().getFirst().previewChecksum().orElseThrow().value());
        assertEquals("d1af5be3dd5c6c329e75ca9e5e0e0e2c32be15456cfebf7f54b5b348849879fc", plan.digest().value());
    }

    @Test void malformedUtf16CannotCollapseDifferentTargetIdentities() throws Exception {
        String malformed = "reviewer" + (char) 0xD800;
        dev.tuprel.migrate.MigrationHistoryException failure = assertThrows(dev.tuprel.migrate.MigrationHistoryException.class,
                () -> settings("jdbc:postgresql://localhost:5432/demo", malformed, "p").planTarget("app"));
        assertEquals(dev.tuprel.migrate.MigrationHistoryException.Reason.CONFIGURATION, failure.reason());
        assertNull(failure.getCause());
    }

    private MigrationPlan compile(List<MigrationFile> files, MigrationHistory history) throws Exception { return compiler.compile(target(), files, history); }
    private static MigrationPlanTarget target() throws Exception { return settings("jdbc:postgresql://localhost:5432/demo", "reviewer", "private_password").planTarget("app"); }
    private static MigrationDatabaseSettings settings(String url, String user, String password) throws Exception {
        return MigrationDatabaseSettings.fromEnvironment(Map.of("TUPREL_DATABASE_URL", url, "TUPREL_DATABASE_USER", user, "TUPREL_DATABASE_PASSWORD", password));
    }
    private static MigrationFile file(int second, String name, String sql) {
        return new MigrationFile(new MigrationId("20261031_1200%02d".formatted(second)), name,
                (sql.endsWith("\n") ? sql : sql + "\n").getBytes(StandardCharsets.UTF_8));
    }
    private static AppliedMigration row(MigrationFile file, int sequence) {
        Instant start = Instant.EPOCH.plusSeconds(sequence * 2L);
        return new AppliedMigration(file.id(), file.name(), file.checksum(), start, start.plusSeconds(1), 1000, "development");
    }
}
