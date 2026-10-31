package dev.tuprel.migrate.postgresql;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuprel.sql.SqlIdentifier;
import org.junit.jupiter.api.Test;

class MigrationHistoryTableTest {
    @Test void canonicalDefinitionIsDeterministicQualifiedAndImmutable() {
        String ddl = MigrationHistoryTable.createSql(new SqlIdentifier("App"));
        assertEquals(ddl, MigrationHistoryTable.createSql(new SqlIdentifier("App")));
        assertTrue(ddl.startsWith("CREATE TABLE \"App\".\"_tuprel_migrations\" (\n"));
        assertTrue(ddl.endsWith(");\n"));
        assertFalse(ddl.contains("IF NOT EXISTS")); assertFalse(ddl.contains("\r"));
        assertEquals(9, MigrationHistoryTable.columns().size());
        assertEquals(5, MigrationHistoryTable.constraints().size());
        assertThrows(UnsupportedOperationException.class, () -> MigrationHistoryTable.columns().clear());
        assertThrows(UnsupportedOperationException.class, () -> MigrationHistoryTable.constraints().clear());
    }

    @Test void unsafeNamesNeverReachBootstrapSql() {
        for (String name : new String[]{"public.other", "x\"; DROP SCHEMA public;--", "../x", "a".repeat(64)}) {
            assertThrows(IllegalArgumentException.class, () -> MigrationHistoryTable.createSql(new SqlIdentifier(name)));
        }
    }
}
