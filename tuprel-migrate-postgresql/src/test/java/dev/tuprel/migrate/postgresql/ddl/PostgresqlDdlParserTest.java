package dev.tuprel.migrate.postgresql.ddl;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuprel.migrate.MigrationFile;
import dev.tuprel.migrate.MigrationId;
import dev.tuprel.sql.SqlIdentifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PostgresqlDdlParserTest {
    private final PostgresqlDdlParser parser = new PostgresqlDdlParser();

    @Test void completeSubsetHasTypedAstAndCanonicalGoldenPreview() {
        DdlParseResult parsed = parse("""
                /* review; /* nested */ */ create table "app"."User" (
                  "id" UUID PRIMARY KEY, "email" TEXT NOT NULL, "created" TIMESTAMP WITH TIME ZONE
                );
                ALTER TABLE "app"."User" ADD COLUMN "active" BOOLEAN NULL;
                CREATE INDEX "User_email_idx" ON "app"."User" ("email"); -- end
                """);
        assertTrue(parsed.isSupported());
        assertEquals(3, parsed.statements().size());
        DdlStatement.CreateTable table = assertInstanceOf(DdlStatement.CreateTable.class, parsed.statements().getFirst());
        assertTrue(table.columns().getFirst().primaryKey()); assertFalse(table.columns().getFirst().nullable());
        assertEquals("""
                CREATE TABLE "app"."User" (
                    "id" "pg_catalog"."uuid" NOT NULL PRIMARY KEY,
                    "email" "pg_catalog"."text" NOT NULL,
                    "created" "pg_catalog"."timestamptz"
                );
                ALTER TABLE "app"."User" ADD COLUMN "active" "pg_catalog"."bool";
                CREATE INDEX "User_email_idx" ON "app"."User" ("email");
                """, parsed.preview());
        assertEquals(parsed.statements(), parse(parsed.preview()).statements());
        assertFalse(parsed.toString().contains("User"));
    }

    @Test void everyTypeRoundTripsIncludingCanonicalQualifiedNames() {
        for (DdlStatement.Type type : DdlStatement.Type.values()) {
            DdlParseResult result = parse("CREATE TABLE \"s\".\"t\" (\"c\" " + type.name() + ");");
            assertTrue(result.isSupported(), type::name);
            assertEquals(result.statements(), parse(result.preview()).statements());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "BEGIN; CREATE TABLE \"s\".\"t\" (\"c\" TEXT); COMMIT;",
        "CREATE UNLOGGED TABLE \"s\".\"t\" (\"c\" TEXT);",
        "CREATE TABLE IF NOT EXISTS \"s\".\"t\" (\"c\" TEXT);",
        "CREATE TABLE \"s\".\"t\" (\"c\" TEXT DEFAULT 'private_value');",
        "CREATE TABLE \"s\".\"t\" (\"c\" TEXT UNIQUE);",
        "CREATE TABLE \"s\".\"t\" (\"c\" TEXT REFERENCES \"s\".\"other\");",
        "CREATE TABLE \"s\".\"t\" (\"c\" TEXT CHECK (true));",
        "CREATE TABLE \"s\".\"t\" (\"c\" varchar(64));",
        "CREATE TABLE \"s\".\"t\" (\"c\" \"private_schema\".\"uuid\");",
        "CREATE TABLE \"s\".\"t\" (\"c\" TIMESTAMP WITHOUT TIME ZONE);",
        "ALTER TABLE \"s\".\"t\" ADD COLUMN \"c\" TEXT NOT NULL;",
        "ALTER TABLE \"s\".\"t\" ADD COLUMN \"c\" TEXT DEFAULT now();",
        "CREATE INDEX CONCURRENTLY \"idx\" ON \"s\".\"t\" (\"c\");",
        "CREATE UNIQUE INDEX \"idx\" ON \"s\".\"t\" (\"c\");",
        "CREATE INDEX \"idx\" ON \"s\".\"t\" (lower(\"c\"));",
        "CREATE INDEX \"idx\" ON \"s\".\"t\" (\"c\") WHERE true;",
        "CREATE INDEX \"s\".\"idx\" ON \"s\".\"t\" (\"c\");",
        "DO $$ BEGIN RAISE NOTICE 'private_value'; END $$;",
        "SET search_path = public;", "CALL private_value();", "COPY private_value FROM PROGRAM 'cmd';",
        "\\i private_value.sql", "CREATE TABLE app.users (id UUID);"
    })
    void unknownSqlIsNeverPartiallyAcceptedOrEchoed(String source) {
        DdlParseResult failure = parse(source);
        assertFalse(failure.isSupported()); assertTrue(failure.statements().isEmpty());
        assertFalse(failure.diagnostic().orElseThrow().toString().contains("private_value"));
        assertThrows(IllegalStateException.class, failure::preview);
    }

    @Test void destructiveSuffixInvalidatesTheWholeProgram() {
        DdlParseResult failure = parse("CREATE TABLE \"s\".\"t\" (\"c\" TEXT);\nDROP TABLE \"s\".\"t\";");
        assertEquals(DdlParseResult.Kind.DESTRUCTIVE_PREFIX, failure.diagnostic().orElseThrow().kind());
        assertEquals(2, failure.diagnostic().orElseThrow().line());
        assertEquals(1, failure.diagnostic().orElseThrow().column());
        assertTrue(failure.statements().isEmpty());
        for (String verb : List.of("TRUNCATE", "DELETE", "UPDATE")) {
            assertEquals(DdlParseResult.Kind.DESTRUCTIVE_PREFIX, parse(verb + " private_value;").diagnostic().orElseThrow().kind());
        }
    }

    @Test void commentsAreLexedAndNeverSplitAsStatements() {
        DdlParseResult first = parse("-- DROP TABLE private_value;\nCREATE/* ; /* inner */ */ TABLE \"s\".\"DROP\" (\"c\" TEXT);");
        assertTrue(first.isSupported());
        assertEquals(1, first.statements().size());
        assertFalse(first.preview().contains("private_value"));
        assertEquals(DdlParseResult.Kind.EMPTY_SCRIPT, parse("-- draft\n/* ; */").diagnostic().orElseThrow().kind());
        assertEquals(DdlParseResult.Kind.INVALID_SYNTAX, parse("/* unfinished").diagnostic().orElseThrow().kind());
        assertFalse(parse("CREATE TABLE \"s\".\"t\" (\"c\" TEXT); /* unterminated").isSupported());
    }

    @Test void malformedAndTrailingInputFailClosed() {
        for (String input : List.of("CREATE TABLE \"s\".\"t\" (\"c\" TEXT)",
                "CREATE TABLE \"s\".\"t\" ();", "CREATE TABLE \"s\".\"t\" (\"c\" TEXT,);",
                "CREATE TABLE \"s\".\"t\" (\"c\" TEXT);;", "CREATE TABLE \"s\".\"t\" (\"c\" TEXT); private_value",
                "CREATE TABLE \"s\".\"t\" (\"c\" TEXT NOT NULL NOT NULL);",
                "CREATE TABLE \"s\".\"t\" (\"c\" TEXT PRIMARY KEY PRIMARY KEY);", "CREATE TABLE \"s\".\"unfinished")) {
            assertFalse(parse(input).isSupported());
        }
    }

    @Test void identifiersReusePortableValidationWithoutTruncationOrAliases() {
        for (String name : List.of("", "x".repeat(64), "space name", "é", "x\"\"y", "a;b", "../x")) {
            DdlParseResult invalid = parse("CREATE TABLE \"s\".\"" + name + "\" (\"c\" TEXT);");
            assertFalse(invalid.isSupported());
        }
        assertTrue(parse("CREATE TABLE \"s\".\"" + "x".repeat(63) + "\" (\"c\" TEXT);").isSupported());
    }

    @Test void duplicateColumnsKeysAndIndexColumnsCannotReachAnAst() {
        for (String source : List.of("CREATE TABLE \"s\".\"t\" (\"c\" TEXT, \"c\" TEXT);",
                "CREATE TABLE \"s\".\"t\" (\"a\" UUID PRIMARY KEY, \"b\" UUID PRIMARY KEY);",
                "CREATE INDEX \"idx\" ON \"s\".\"t\" (\"c\", \"c\");")) { assertFalse(parse(source).isSupported()); }
    }

    @Test void parsingBudgetsBoundCommentsStatementsColumnsAndTokens() {
        assertLimit("/*".repeat(33) + "x" + "*/".repeat(33));
        assertLimit("ALTER TABLE \"s\".\"t\" ADD COLUMN \"c\" TEXT;".repeat(257));
        String columns = java.util.stream.IntStream.range(0, 257).mapToObj(index -> "\"c" + index + "\" TEXT")
                .collect(java.util.stream.Collectors.joining(","));
        assertLimit("CREATE TABLE \"s\".\"t\" (" + columns + ");");
        String indexColumns = java.util.stream.IntStream.range(0, 33).mapToObj(index -> "\"c" + index + "\"")
                .collect(java.util.stream.Collectors.joining(","));
        assertLimit("CREATE INDEX \"i\" ON \"s\".\"t\" (" + indexColumns + ");");
        String boundedTable = "CREATE TABLE \"s\".\"t\" (" + columns.substring(0, columns.lastIndexOf(',')) + ");";
        assertLimit(boundedTable.repeat(64));
        assertFalse(parse("CREATE TABLE " + "(".repeat(100000)).isSupported());
    }

    @Test void publicAstAndResultsAreImmutableAndEnforceTheirContracts() {
        DdlStatement.TableName table = new DdlStatement.TableName(new SqlIdentifier("s"), new SqlIdentifier("t"));
        List<DdlStatement.Column> columns = new ArrayList<>(List.of(new DdlStatement.Column(new SqlIdentifier("c"), DdlStatement.Type.TEXT, true, false)));
        DdlStatement.CreateTable create = new DdlStatement.CreateTable(table, columns);
        columns.clear(); assertEquals(1, create.columns().size());
        assertThrows(UnsupportedOperationException.class, () -> create.columns().clear());
        assertThrows(IllegalArgumentException.class, () -> new DdlStatement.Column(new SqlIdentifier("c"), DdlStatement.Type.UUID, true, true));
        assertThrows(IllegalArgumentException.class, () -> new DdlStatement.CreateIndex(table, new SqlIdentifier("idx"), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new DdlParseResult(List.of(), Optional.empty()));
        assertThrows(UnsupportedOperationException.class, () -> parse("CREATE TABLE \"s\".\"t\" (\"c\" TEXT);").statements().clear());
    }

    private void assertLimit(String source) { assertEquals(DdlParseResult.Kind.RESOURCE_LIMIT, parse(source).diagnostic().orElseThrow().kind()); }
    private DdlParseResult parse(String sql) {
        return parser.parse(new MigrationFile(new MigrationId("20261031_120000"), "example",
                (sql.endsWith("\n") ? sql : sql + "\n").getBytes(StandardCharsets.UTF_8)));
    }
}
