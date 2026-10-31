package dev.tuprel.migrate.postgresql.diff;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuprel.schema.SchemaCompiler;
import dev.tuprel.schema.SourceText;
import dev.tuprel.schema.model.ValidatedSchema;
import dev.tuprel.sql.SqlIdentifier;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class SchemaProjectorTest {
    private final PostgresqlSchemaProjector projector = new PostgresqlSchemaProjector();
    private static final SqlIdentifier APP = new SqlIdentifier("app");

    @Test void eightScalarsNullabilityPrimaryKeyAndIndexProduceCanonicalGolden() {
        SchemaProjection result = project("""
                model Thing {
                  id UUID @id
                  flag Boolean?
                  small Short
                  number Int
                  large Long
                  label String?
                  day LocalDate
                  time Instant
                  @@index([label, id])
                }
                """);
        assertTrue(result.diagnostics().isEmpty());
        String sql = diff(result).preview();
        assertEquals("""
                CREATE TABLE "app"."Thing" (
                    "day" "pg_catalog"."date" NOT NULL,
                    "flag" "pg_catalog"."bool",
                    "id" "pg_catalog"."uuid" NOT NULL PRIMARY KEY,
                    "label" "pg_catalog"."text",
                    "large" "pg_catalog"."int8" NOT NULL,
                    "number" "pg_catalog"."int4" NOT NULL,
                    "small" "pg_catalog"."int2" NOT NULL,
                    "time" "pg_catalog"."timestamptz" NOT NULL
                );
                CREATE INDEX "Thing_label_id_idx" ON "app"."Thing" ("label", "id");
                """, sql);
        assertFalse(sql.contains("DEFAULT"));
    }

    @Test void versionIsARequiredColumnWithoutAnInventedDefault() {
        String sql = diff(project("model Item { id UUID @id version Int @version }")).preview();
        assertTrue(sql.contains("\"version\" \"pg_catalog\".\"int4\" NOT NULL")); assertFalse(sql.contains("DEFAULT"));
    }

    @Test void unsupportedFeaturesRefuseEvenAlongsideValidModelsWithoutLeakingSource() {
        for (String field : List.of("value String @default(\"private_value\")", "value String @unique",
                "value String[]", "value Float", "value Double", "value Decimal", "value Bytes", "value Json",
                "value LocalDateTime", "value LocalTime")) {
            SchemaProjection result = project("model Good { id UUID @id } model Bad { id UUID @id " + field + " }");
            assertTrue(result.snapshot().isEmpty()); assertFalse(result.diagnostics().isEmpty());
            assertFalse(result.toString().contains("private_value")); assertFalse(result.diagnostics().toString().contains("private_value"));
            assertThrows(UnsupportedOperationException.class, () -> result.diagnostics().clear());
        }
        assertTrue(project("enum Color { RED } model Item { id UUID @id }").snapshot().isEmpty());
        assertTrue(project("model Item { id UUID @id value String @@unique([value]) }").snapshot().isEmpty());
        SchemaProjection relation = project("model Parent { id UUID @id children Child[] } model Child { id UUID @id parentId UUID parent Parent @relation(fields: [parentId], references: [id]) }");
        assertTrue(relation.snapshot().isEmpty());
        assertTrue(relation.diagnostics().stream().anyMatch(item -> item.kind() == SchemaProjection.Kind.RELATION));
    }

    @Test void configurationIsNeverEvaluatedAndDiagnosticsAreDetachedPositions() {
        SchemaProjection result = project("datasource db { provider = \"postgresql\" url = env(\"UNSET_SECRET\") } model Item { id UUID @id value String @default(\"private_value\") }");
        assertTrue(result.snapshot().isEmpty());
        assertTrue(result.diagnostics().getFirst().start().column() > 0);
        assertFalse(result.diagnostics().toString().contains("UNSET_SECRET"));
    }

    @Test void indexNamesAreBoundedExactAndCollisionsNeverReceiveAutomaticSuffixes() {
        String name = "T".repeat(56);
        assertTrue(project("model " + name + " { id UUID @id @@index([id]) }").snapshot().isPresent());
        assertTrue(project("model " + name + "X { id UUID @id @@index([id]) }").snapshot().isEmpty());
        assertTrue(project("model A { id UUID @id b_c String b String c String @@index([b_c]) @@index([b, c]) }").snapshot().isEmpty());
        assertTrue(project("model A { id UUID @id @@index([id]) } model A_id_idx { id UUID @id }").snapshot().isEmpty());
        assertTrue(project("model " + "N".repeat(64) + " { id UUID @id }").snapshot().isEmpty());
        assertTrue(project("model _tuprel_hidden { id UUID @id }").snapshot().isEmpty());
    }

    @Test void declarationOrderDoesNotChangeSqlButIndexKeyOrderDoes() {
        SchemaProjection a = project("model A { id UUID @id x String @@index([x, id]) } model B { id UUID @id }");
        SchemaProjection b = project("model B { id UUID @id } model A { x String id UUID @id @@index([x, id]) }");
        assertEquals(diff(a).preview(), diff(b).preview());
        SchemaProjection c = project("model A { id UUID @id x String @@index([id, x]) } model B { id UUID @id }");
        assertNotEquals(diff(a).preview(), diff(c).preview());
    }

    @Test void sharedProjectorIsDeterministicUnderConcurrency() throws Exception {
        ValidatedSchema source = compile("model Item { id UUID @id value String? @@index([value]) }");
        String expected = diff(projector.project(APP, source)).preview();
        try (java.util.concurrent.ExecutorService executor = Executors.newFixedThreadPool(4)) {
            List<Callable<String>> tasks = IntStream.range(0, 64).mapToObj(index -> (Callable<String>) () -> diff(projector.project(APP, source)).preview()).toList();
            for (java.util.concurrent.Future<String> future : executor.invokeAll(tasks)) { assertEquals(expected, future.get()); }
        }
    }

    @Test void targetAndSizeBudgetsRefuseWholeProjection() {
        ValidatedSchema source = compile("model Item { id UUID @id }");
        for (String target : List.of("pg_catalog", "information_schema", "_tuprel_private")) {
            assertTrue(projector.project(new SqlIdentifier(target), source).snapshot().isEmpty());
        }
        StringBuilder large = new StringBuilder("model Item { id UUID @id\n");
        for (int index = 0; index < 256; index++) { large.append("c").append(index).append(" String\n"); }
        assertTrue(project(large + "}").diagnostics().stream().anyMatch(item -> item.kind() == SchemaProjection.Kind.RESOURCE_LIMIT));
        assertThrows(IllegalArgumentException.class, () -> new SchemaProjection(java.util.Optional.empty(), List.of()));
    }

    private SchemaProjection project(String text) { return projector.project(APP, compile(text)); }
    private static ValidatedSchema compile(String text) { return new SchemaCompiler().compile(SourceText.of("schema.tuprel", text)).validatedSchema().orElseThrow(); }
    private static SchemaDiff diff(SchemaProjection result) { return SchemaDiff.compare(SchemaSnapshot.replay(APP, List.of()), result.snapshot().orElseThrow()); }
}
