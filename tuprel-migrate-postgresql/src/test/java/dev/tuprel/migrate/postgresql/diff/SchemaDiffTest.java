package dev.tuprel.migrate.postgresql.diff;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuprel.migrate.MigrationFile;
import dev.tuprel.migrate.MigrationId;
import dev.tuprel.migrate.postgresql.ddl.DdlParseResult;
import dev.tuprel.migrate.postgresql.ddl.DdlStatement;
import dev.tuprel.migrate.postgresql.ddl.PostgresqlDdlParser;
import dev.tuprel.sql.SqlIdentifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class SchemaDiffTest {
    private static final SqlIdentifier APP = new SqlIdentifier("app");
    private static final String TABLE = "CREATE TABLE \"app\".\"Thing\" (\"id\" UUID PRIMARY KEY);\n";

    @Test void emptyAndEqualStatesNeverClaimPhysicalDriftChecked() {
        SchemaDiff diff = SchemaDiff.compare(empty(), empty());
        assertEquals(SchemaDiff.State.NO_CHANGES, diff.state());
        assertEquals(Set.of(SchemaDiff.Risk.PHYSICAL_STATE_NOT_CHECKED), diff.risks());
        assertEquals("", diff.preview());
        assertEquals(SchemaDiff.State.NO_CHANGES, SchemaDiff.compare(snapshot(TABLE), snapshot(TABLE)).state());
    }

    @Test void tableColumnAndIndexAdditionsUseTheExistingCanonicalRenderer() {
        SchemaSnapshot before = snapshot(TABLE);
        SchemaSnapshot after = snapshot(TABLE + "ALTER TABLE \"app\".\"Thing\" ADD COLUMN \"value\" TEXT;\n"
                + "CREATE INDEX \"by_value\" ON \"app\".\"Thing\" (\"value\", \"id\");\n");
        SchemaDiff diff = SchemaDiff.compare(before, after);
        assertEquals(SchemaDiff.State.REVIEW_REQUIRED, diff.state());
        assertEquals(List.of(SchemaDiff.Kind.COLUMN_ADDED, SchemaDiff.Kind.INDEX_ADDED), kinds(diff));
        assertEquals("""
                ALTER TABLE "app"."Thing" ADD COLUMN "value" "pg_catalog"."text";
                CREATE INDEX "by_value" ON "app"."Thing" ("value", "id");
                """, diff.preview());
        assertTrue(diff.risks().containsAll(Set.of(SchemaDiff.Risk.ACCESS_EXCLUSIVE_LOCK,
                SchemaDiff.Risk.INDEX_WRITE_BLOCKING, SchemaDiff.Risk.INDEX_BUILD_COST)));
        List<DdlStatement> replay = new ArrayList<>(parse(TABLE).statements());
        replay.addAll(parse(diff.preview()).statements());
        assertEquals(SchemaDiff.State.NO_CHANGES, SchemaDiff.compare(SchemaSnapshot.replay(APP, replay), after).state());
        assertEquals(SchemaDiff.Kind.TABLE_ADDED, SchemaDiff.compare(empty(), before).changes().getFirst().kind());
    }

    @Test void independentTableAndColumnDeclarationOrderingDoesNotChangeLogicalPreview() {
        String a = "CREATE TABLE \"app\".\"Z\" (\"z\" TEXT, \"a\" INTEGER);\n";
        String b = "CREATE TABLE \"app\".\"A\" (\"id\" UUID);\n";
        String reversedColumns = "CREATE TABLE \"app\".\"Z\" (\"a\" INTEGER, \"z\" TEXT);\n";
        assertEquals(SchemaDiff.State.NO_CHANGES, SchemaDiff.compare(snapshot(a + b), snapshot(b + reversedColumns)).state());
        assertEquals(SchemaDiff.compare(empty(), snapshot(a + b)).preview(),
                SchemaDiff.compare(empty(), snapshot(b + reversedColumns)).preview());
        assertTrue(SchemaDiff.compare(empty(), snapshot(a + b)).preview().startsWith("CREATE TABLE \"app\".\"A\""));
    }

    @Test void tableAndColumnRenamesAreRemovalsAndAdditionsWithNoPartialPreview() {
        SchemaDiff tableRename = SchemaDiff.compare(snapshot(TABLE), snapshot(TABLE.replace("Thing", "Other")));
        assertTrue(kinds(tableRename).containsAll(List.of(SchemaDiff.Kind.TABLE_REMOVED, SchemaDiff.Kind.TABLE_ADDED)));
        assertBlocked(tableRename);
        SchemaDiff columnRename = SchemaDiff.compare(snapshot(TABLE), snapshot(TABLE.replace("id", "other_id")));
        assertTrue(kinds(columnRename).containsAll(List.of(SchemaDiff.Kind.COLUMN_REMOVED, SchemaDiff.Kind.COLUMN_ADDED)));
        assertTrue(columnRename.risks().contains(SchemaDiff.Risk.POTENTIAL_DATA_LOSS));
        assertBlocked(columnRename);
    }

    @Test void everyBuiltInTypeChangeIsReportedAndBlockedIncludingWidening() {
        for (DdlStatement.Type before : DdlStatement.Type.values()) {
            for (DdlStatement.Type after : DdlStatement.Type.values()) {
                SchemaDiff diff = SchemaDiff.compare(snapshot(column(before.name(), "")), snapshot(column(after.name(), "")));
                if (before == after) { assertEquals(SchemaDiff.State.NO_CHANGES, diff.state()); }
                else {
                    assertEquals(List.of(SchemaDiff.Kind.COLUMN_TYPE_CHANGED), kinds(diff));
                    assertTrue(diff.risks().contains(SchemaDiff.Risk.POTENTIAL_DATA_LOSS));
                    assertBlocked(diff);
                }
            }
        }
    }

    @Test void nullabilityPrimaryKeysAndRequiredAdditionsAreNotApproximated() {
        for (String changed : List.of(column("UUID", "NOT NULL"), column("UUID", "PRIMARY KEY"))) {
            SchemaDiff diff = SchemaDiff.compare(snapshot(column("UUID", "")), snapshot(changed));
            assertTrue(kinds(diff).contains(SchemaDiff.Kind.COLUMN_NULLABILITY_CHANGED));
            assertBlocked(diff);
        }
        SchemaDiff primary = SchemaDiff.compare(snapshot(column("UUID", "NOT NULL")), snapshot(column("UUID", "PRIMARY KEY")));
        assertEquals(List.of(SchemaDiff.Kind.COLUMN_PRIMARY_KEY_CHANGED), kinds(primary)); assertBlocked(primary);
        assertBlocked(SchemaDiff.compare(snapshot(TABLE), snapshot(TABLE.replace(");", ", \"required\" TEXT NOT NULL);"))));
    }

    @Test void indexOrderChangesRemovalsAndMovesAreExplicitUnsupportedDifferences() {
        String columns = "CREATE TABLE \"app\".\"Thing\" (\"a\" TEXT, \"b\" TEXT);\n";
        String index = "CREATE INDEX \"idx\" ON \"app\".\"Thing\" (\"a\", \"b\");\n";
        SchemaDiff reordered = SchemaDiff.compare(snapshot(columns + index), snapshot(columns + index.replace("(\"a\", \"b\")", "(\"b\", \"a\")")));
        assertEquals(List.of(SchemaDiff.Kind.INDEX_CHANGED), kinds(reordered)); assertBlocked(reordered);
        SchemaDiff removed = SchemaDiff.compare(snapshot(columns + index), snapshot(columns));
        assertEquals(List.of(SchemaDiff.Kind.INDEX_REMOVED), kinds(removed)); assertBlocked(removed);
        SchemaDiff renamed = SchemaDiff.compare(snapshot(columns + index), snapshot(columns + index.replace("idx", "other")));
        assertEquals(List.of(SchemaDiff.Kind.INDEX_REMOVED, SchemaDiff.Kind.INDEX_ADDED), kinds(renamed)); assertBlocked(renamed);
    }

    @Test void unknownSqlAndCommentOnlyDraftsNeverBecomeEmptyOrPartialSnapshots() {
        for (String unknown : List.of("-- draft\n", TABLE + "DROP TABLE \"app\".\"Thing\";\n",
                "SELECT 'private_value';\n", "CREATE TABLE \"app\".\"x\" (\"v\" JSONB);\n")) {
            DdlParseResult parsed = parse(unknown);
            assertFalse(parsed.isSupported());
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> SchemaSnapshot.fromParsed(APP, List.of(parse(TABLE), parsed)));
            assertTrue(error.getMessage().contains("TUPREL-DIFF-UNKNOWN"));
            assertFalse(error.toString().contains("private_value"));
        }
    }

    @Test void replayRejectsMissingPrerequisitesDuplicateObjectsCrossSchemaAndReservedNames() {
        for (String invalid : List.of(TABLE + TABLE,
                "ALTER TABLE \"app\".\"Thing\" ADD COLUMN \"value\" TEXT;\n",
                TABLE + "ALTER TABLE \"app\".\"Thing\" ADD COLUMN \"id\" UUID;\n",
                TABLE + "CREATE INDEX \"Thing\" ON \"app\".\"Thing\" (\"id\");\n",
                TABLE + "CREATE INDEX \"idx\" ON \"app\".\"Thing\" (\"missing\");\n",
                TABLE.replace("app", "other"), TABLE.replace("Thing", "_TuPrEl_history"),
                TABLE + "CREATE INDEX \"_tuprel_index\" ON \"app\".\"Thing\" (\"id\");\n")) {
            assertThrows(IllegalArgumentException.class, () -> snapshot(invalid));
        }
        SchemaSnapshot other = SchemaSnapshot.replay(new SqlIdentifier("other"), List.of());
        assertThrows(IllegalArgumentException.class, () -> SchemaDiff.compare(empty(), other));
    }

    @Test void identifiersRemainCaseSensitiveAndInputFormattingDoesNotChangeTheResult() {
        assertBlocked(SchemaDiff.compare(snapshot(TABLE), snapshot(TABLE.replace("Thing", "thing"))));
        assertEquals(SchemaDiff.State.NO_CHANGES, SchemaDiff.compare(snapshot(TABLE), snapshot("-- comment\n" + TABLE.toLowerCase(Locale.ROOT).replace("thing", "Thing"))).state());
    }

    @Test void snapshotAndResultsAreImmutableAndIncidentalLoggingIsRedacted() {
        List<DdlStatement> input = new ArrayList<>(parse(TABLE).statements());
        SchemaSnapshot state = SchemaSnapshot.replay(APP, input); input.clear();
        assertEquals(1, state.tables().size());
        assertThrows(UnsupportedOperationException.class, () -> state.tables().clear());
        assertThrows(UnsupportedOperationException.class, () -> state.tables().get("Thing").columns().clear());
        SchemaDiff diff = SchemaDiff.compare(empty(), state);
        assertThrows(UnsupportedOperationException.class, () -> diff.changes().clear());
        assertThrows(UnsupportedOperationException.class, () -> diff.risks().clear());
        assertThrows(UnsupportedOperationException.class, () -> diff.changes().getFirst().risks().clear());
        assertFalse(state.toString().contains("Thing")); assertFalse(state.tables().get("Thing").toString().contains("id"));
        assertFalse(diff.toString().contains("Thing")); assertFalse(diff.changes().getFirst().toString().contains("Thing"));
    }

    @Test void concurrentComparisonsOfSharedSnapshotsRemainDeterministic() throws Exception {
        SchemaSnapshot desired = snapshot(TABLE);
        String expected = SchemaDiff.compare(empty(), desired).preview();
        try (java.util.concurrent.ExecutorService executor = Executors.newFixedThreadPool(4)) {
            List<Callable<String>> tasks = IntStream.range(0, 64).mapToObj(index -> (Callable<String>) () -> SchemaDiff.compare(empty(), desired).preview()).toList();
            for (java.util.concurrent.Future<String> result : executor.invokeAll(tasks)) { assertEquals(expected, result.get()); }
        }
    }

    @Test void statementAndObjectBudgetsAreEnforcedBeforeReturningSnapshots() {
        DdlStatement create = parse(TABLE).statements().getFirst();
        assertThrows(IllegalArgumentException.class, () -> SchemaSnapshot.replay(APP, Collections.nCopies(4097, create)));
        List<DdlStatement> large = new ArrayList<>();
        for (int table = 0; table < 64; table++) {
            List<DdlStatement.Column> columns = IntStream.range(0, 256).mapToObj(index ->
                    new DdlStatement.Column(new SqlIdentifier("c" + index), DdlStatement.Type.TEXT, true, false)).toList();
            large.add(new DdlStatement.CreateTable(new DdlStatement.TableName(APP, new SqlIdentifier("t" + table)), columns));
        }
        assertThrows(IllegalArgumentException.class, () -> SchemaSnapshot.replay(APP, large));
        assertThrows(IllegalArgumentException.class, () -> SchemaSnapshot.fromParsed(APP, Collections.nCopies(17,
                new DdlParseResult(Collections.nCopies(256, create), java.util.Optional.empty()))));
    }

    @Test void validStatesCanExceedThePreviewStatementBudgetAndBlockTheWholeProposal() {
        List<DdlStatement> program = new ArrayList<>();
        for (int table = 0; table < 21; table++) {
            SqlIdentifier name = new SqlIdentifier("t" + table);
            DdlStatement.TableName target = new DdlStatement.TableName(APP, name);
            List<DdlStatement.Column> columns = IntStream.range(0, 200).mapToObj(index ->
                    new DdlStatement.Column(new SqlIdentifier("c" + index), DdlStatement.Type.TEXT, true, false)).toList();
            program.add(new DdlStatement.CreateTable(target, columns));
        }
        SchemaSnapshot desired = SchemaSnapshot.replay(APP, program);
        List<DdlStatement> initial = program.stream().map(statement -> {
            DdlStatement.CreateTable table = (DdlStatement.CreateTable) statement;
            return (DdlStatement) new DdlStatement.CreateTable(table.table(), List.of(table.columns().getFirst()));
        }).toList();
        SchemaDiff diff = SchemaDiff.compare(SchemaSnapshot.replay(APP, initial), desired);
        assertEquals(SchemaDiff.State.BLOCKED, diff.state());
        assertTrue(diff.risks().contains(SchemaDiff.Risk.RESOURCE_LIMIT));
        assertThrows(IllegalStateException.class, diff::preview);
    }

    private static void assertBlocked(SchemaDiff diff) {
        assertEquals(SchemaDiff.State.BLOCKED, diff.state());
        assertTrue(diff.risks().contains(SchemaDiff.Risk.UNSUPPORTED_CHANGE));
        assertTrue(diff.risks().contains(SchemaDiff.Risk.LOCK_IMPACT_NOT_MODELED));
        assertThrows(IllegalStateException.class, diff::preview);
    }

    private static List<SchemaDiff.Kind> kinds(SchemaDiff diff) { return diff.changes().stream().map(SchemaDiff.Change::kind).toList(); }
    private static SchemaSnapshot empty() { return SchemaSnapshot.replay(APP, List.of()); }
    private static String column(String type, String suffix) { return "CREATE TABLE \"app\".\"Thing\" (\"id\" " + type + " " + suffix + ");\n"; }
    private static SchemaSnapshot snapshot(String sql) { return SchemaSnapshot.fromParsed(APP, List.of(parse(sql))); }
    private static DdlParseResult parse(String sql) {
        return new PostgresqlDdlParser().parse(new MigrationFile(new MigrationId("20261031_120000"), "example", sql.getBytes(StandardCharsets.UTF_8)));
    }
}
