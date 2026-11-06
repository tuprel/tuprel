package dev.tuprel.migrate.postgresql;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuprel.migrate.MigrationChecksum;
import dev.tuprel.migrate.postgresql.ddl.DdlStatement;
import dev.tuprel.migrate.postgresql.diff.SchemaSnapshot;
import dev.tuprel.sql.SqlIdentifier;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PhysicalDeployDigestTest {
    private static final SqlIdentifier SCHEMA = new SqlIdentifier("fixture");
    private static final SqlIdentifier ID = new SqlIdentifier("Id");
    private static final SqlIdentifier LABEL = new SqlIdentifier("label");
    private static final SqlIdentifier RANK = new SqlIdentifier("rank");
    private static final DdlStatement.TableName TABLE = new DdlStatement.TableName(SCHEMA, new SqlIdentifier("Example"));
    private static final DdlStatement.Column KEY = new DdlStatement.Column(ID, DdlStatement.Type.INTEGER, false, true);
    private static final DdlStatement.Column TEXT = new DdlStatement.Column(LABEL, DdlStatement.Type.TEXT, true, false);
    private static final DdlStatement.Column NUMBER = new DdlStatement.Column(RANK, DdlStatement.Type.BIGINT, false, false);
    private static final MigrationPlanTarget TARGET = new MigrationPlanTarget(SCHEMA, new MigrationChecksum("a".repeat(64)));
    private static final PostgresqlCatalogObserver.Target LIVE = new PostgresqlCatalogObserver.Target(4294967294L, 987654321L, false);
    private static final Map<String, Long> RELATIONS = Map.of("Example", 710L, "Example_pkey", 711L,
            "_tuprel_migrations", 712L, "_tuprel_migrations_pkey", 713L, "lookup", 714L);
    private static final MigrationPlan LEGACY = plan(MigrationPlan.State.REVIEW_REQUIRED, "b");

    @Test void matchesIndependentBigEndianGoldenVectors() {
        SchemaSnapshot snapshot = snapshot(List.of(NUMBER, KEY, TEXT), List.of(RANK, LABEL));
        assertEquals("b6476ea16cd36a522f8b30e647406dc315a2ba604d024e3340da4f05db70b108",
                PhysicalDeployDigest.structural(CatalogObservation.PROFILE, snapshot).value());
        assertEquals("caa108979207ebe9b039cc32a095dd2845f1d624e9fc732f44fbb46c8f8fb7db",
                bind(LIVE, LEGACY, snapshot, RELATIONS).value());
    }

    @Test void usesExactCatalogNamesAndIgnoresEnumerationOrderButNotIndexKeyOrder() {
        SchemaSnapshot snapshot = snapshot(List.of(KEY, TEXT, NUMBER), List.of(RANK, LABEL));
        SchemaSnapshot reordered = snapshot(List.of(NUMBER, KEY, TEXT), List.of(RANK, LABEL));
        Map<String, Long> reversed = new LinkedHashMap<>();
        RELATIONS.keySet().stream().sorted(java.util.Comparator.reverseOrder())
                .forEach(name -> reversed.put(name, RELATIONS.get(name)));
        assertEquals(bind(LIVE, LEGACY, snapshot, RELATIONS), bind(LIVE, LEGACY, reordered, reversed));
        assertNotEquals(PhysicalDeployDigest.structural(CatalogObservation.PROFILE, snapshot),
                PhysicalDeployDigest.structural(CatalogObservation.PROFILE, snapshot(List.of(KEY, TEXT, NUMBER), List.of(LABEL, RANK))));
        assertNotEquals(PhysicalDeployDigest.structural(CatalogObservation.PROFILE, snapshot),
                PhysicalDeployDigest.structural("another-catalog-profile", snapshot));
    }

    @Test void targetHistoryAndRelationIdentityInvalidateOldApproval() {
        SchemaSnapshot snapshot = snapshot(List.of(KEY, TEXT, NUMBER), List.of(RANK, LABEL));
        PhysicalDeployDigest original = bind(LIVE, LEGACY, snapshot, RELATIONS);
        assertNotEquals(LEGACY.digest().value(), original.value());
        assertNotEquals(original, bind(LIVE, plan(MigrationPlan.State.REVIEW_REQUIRED, "c"), snapshot, RELATIONS));
        assertNotEquals(original, bind(new PostgresqlCatalogObserver.Target(LIVE.database(), 987654322L, false),
                LEGACY, snapshot, RELATIONS));
        for (String name : RELATIONS.keySet()) {
            Map<String, Long> replaced = new LinkedHashMap<>(RELATIONS);
            replaced.put(name, 1000L);
            assertNotEquals(original, bind(LIVE, LEGACY, snapshot, replaced));
        }
    }

    @Test void refusesBlockedPlansDriftAndMissingOrIncompleteEvidence() {
        SchemaSnapshot snapshot = snapshot(List.of(KEY, TEXT, NUMBER), List.of(RANK, LABEL));
        SchemaSnapshot drifted = snapshot(List.of(KEY, TEXT, NUMBER), List.of(LABEL, RANK));
        assertThrows(IllegalArgumentException.class, () -> PhysicalDeployDigest.bind(TARGET, LIVE, LEGACY,
                snapshot, CatalogObservation.complete(drifted, RELATIONS)));
        assertThrows(IllegalArgumentException.class, () -> PhysicalDeployDigest.bind(TARGET, LIVE, LEGACY,
                snapshot, CatalogObservation.incomplete(List.of(CatalogObservation.Diagnostic.RELATION))));
        assertThrows(IllegalArgumentException.class, () -> PhysicalDeployDigest.bind(TARGET, LIVE, LEGACY,
                snapshot, CatalogObservation.unknown(CatalogObservation.Diagnostic.READ)));
        assertThrows(IllegalArgumentException.class, () -> bind(LIVE, LEGACY, snapshot, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> bind(LIVE, plan(MigrationPlan.State.BLOCKED, "b"), snapshot, RELATIONS));
    }

    private static MigrationPlan plan(MigrationPlan.State state, String digit) {
        return new MigrationPlan(state, List.of(), Set.of(), new MigrationPlanDigest(digit.repeat(64)), "");
    }

    private static SchemaSnapshot snapshot(List<DdlStatement.Column> columns, List<SqlIdentifier> keys) {
        return SchemaSnapshot.replay(SCHEMA, List.of(new DdlStatement.CreateTable(TABLE, columns),
                new DdlStatement.CreateIndex(TABLE, new SqlIdentifier("lookup"), keys)));
    }

    private static PhysicalDeployDigest bind(PostgresqlCatalogObserver.Target target, MigrationPlan plan,
            SchemaSnapshot snapshot, Map<String, Long> relations) {
        return PhysicalDeployDigest.bind(TARGET, target, plan, snapshot, CatalogObservation.complete(snapshot, relations));
    }
}
