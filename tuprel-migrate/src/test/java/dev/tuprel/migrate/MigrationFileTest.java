package dev.tuprel.migrate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MigrationFileTest {
    @ParameterizedTest
    @ValueSource(strings = {"20260230_120000", "20261030_240000", "00001030_120000", "20261030120000",
            "20261030_120060", "2026103_120000", "20261030_120000Z", "２０２６１０３０_120000", "../20261030_120000"})
    void rejectsNoncanonicalIds(String value) {
        assertThrows(IllegalArgumentException.class, () -> new MigrationId(value));
    }

    @Test
    void ordersIdsInUtcCalendarOrderIncludingLeapDay() {
        List<MigrationId> ids = List.of(new MigrationId("20280301_000000"),
                new MigrationId("20280229_235959"), new MigrationId("20261030_120000"));
        assertEquals(List.of("20261030_120000", "20280229_235959", "20280301_000000"),
                ids.stream().sorted().map(MigrationId::value).toList());
    }

    @ParameterizedTest
    @ValueSource(strings = {"../escape", "C:\\escape", "Initial", "a/b", "a\\b", "a..b", "a:", "a ", "a.",
            "á", "_initial", "add__users", "add--users", "add\nusers"})
    void rejectsNonportableNamesAndTraversal(String name) {
        assertThrows(IllegalArgumentException.class,
                () -> new MigrationFile(new MigrationId("20261030_120000"), name, bytes("SELECT 1;\n")));
    }

    @Test
    void rejectsOversizedNamesAndMalformedDirectoryNames() {
        assertThrows(IllegalArgumentException.class, () -> MigrationFile.fromDirectory("20261030_120000_" + "a".repeat(65), bytes("x\n")));
        assertThrows(IllegalArgumentException.class, () -> MigrationFile.fromDirectory("20261030_120000", bytes("x\n")));
        assertThrows(IllegalArgumentException.class, () -> MigrationFile.fromDirectory("20261030_120000/initial", bytes("x\n")));
    }

    @Test
    void sha256MatchesIndependentKnownVectorAndNeverNormalizesBytes() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                MigrationChecksum.of(bytes("abc")).value());
        assertNotEquals(MigrationChecksum.of(bytes("SELECT 1;\n")), MigrationChecksum.of(bytes("SELECT 1;\r\n")));
        assertNotEquals(MigrationChecksum.of(bytes("é\n")), MigrationChecksum.of(bytes("e\u0301\n")));
        assertThrows(IllegalArgumentException.class, () -> new MigrationChecksum("A".repeat(64)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " \n", "SELECT 1;", "SELECT 1;\r\n", "\uFEFFSELECT 1;\n", "SELECT\0;\n"})
    void rejectsNoncanonicalSqlBytesWithoutRewriting(String sql) {
        assertThrows(IllegalArgumentException.class,
                () -> MigrationFile.fromDirectory("20261030_120000_initial", bytes(sql)));
    }

    @Test
    void rejectsMalformedUtf8AndOversizedFiles() {
        assertThrows(IllegalArgumentException.class, () -> MigrationFile.fromDirectory("20261030_120000_initial",
                new byte[] {(byte) 0xC3, (byte) 0x28, 10}));
        assertThrows(IllegalArgumentException.class, () -> MigrationFile.fromDirectory("20261030_120000_initial",
                new byte[MigrationFile.MAX_SQL_BYTES + 1]));
    }

    @Test
    void snapshotIsImmutableAndDoesNotClaimSqlSafetyOrLeakSqlInToString() {
        byte[] input = bytes("BEGIN; DROP TABLE private_value; COMMIT;\n");
        MigrationFile migration = MigrationFile.fromDirectory("20261030_120000_add-users", input);
        MigrationChecksum original = migration.checksum();
        input[0] = 'x';
        assertEquals(original, migration.checksum());
        assertTrue(migration.sql().startsWith("BEGIN; DROP"));
        assertEquals("20261030_120000_add-users", migration.directoryName());
        assertFalse(migration.toString().contains("private_value"));
    }

    private static byte[] bytes(String sql) { return sql.getBytes(StandardCharsets.UTF_8); }
}
