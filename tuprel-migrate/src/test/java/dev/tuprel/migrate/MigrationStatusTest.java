package dev.tuprel.migrate;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MigrationStatusTest {
    @Test void uninitialisedAndPendingAreNotIntegrityFailures() {
        MigrationStatus status = MigrationStatus.compare(List.of(file(1, "first"), file(2, "next")), new MigrationHistory(false, List.of()));
        assertFalse(status.initialised());
        assertFalse(status.hasProblems());
        assertEquals(Set.of(MigrationStatus.State.PENDING), status.entries().getFirst().states());
        assertThrows(UnsupportedOperationException.class, () -> status.entries().clear());
    }
    @Test void exactPrefixLeavesOnlyLaterMigrationsPending() {
        MigrationStatus status = compare(List.of(file(2, "next"), file(1, "first")), List.of(row(file(1, "first"), 1)));
        assertFalse(status.hasProblems());
        assertEquals(List.of("first", "next"), status.entries().stream().map(MigrationStatus.Entry::name).toList());
        assertEquals(Set.of(MigrationStatus.State.APPLIED), status.entries().getFirst().states());
    }
    @Test void independentChecksumNameAndRemovalDivergencesAreRetained() {
        MigrationFile edited = new MigrationFile(file(1, "renamed").id(), "renamed", "-- edited\n".getBytes(StandardCharsets.UTF_8));
        MigrationStatus status = compare(List.of(edited), List.of(row(file(1, "first"), 1), row(file(2, "removed"), 2)));
        assertTrue(status.hasProblems());
        assertTrue(status.entries().getFirst().states().containsAll(Set.of(MigrationStatus.State.MODIFIED, MigrationStatus.State.RENAMED)));
        assertTrue(status.entries().getLast().states().contains(MigrationStatus.State.REMOVED));
    }
    @Test void gapsRetroactiveFilesAndReversedExecutionTimesAreOutOfOrder() {
        MigrationStatus gap = compare(List.of(file(1, "first"), file(2, "second")), List.of(row(file(2, "second"), 2)));
        assertTrue(gap.entries().stream().allMatch(entry -> entry.states().contains(MigrationStatus.State.OUT_OF_ORDER)));
        MigrationStatus reversed = compare(List.of(file(1, "first"), file(2, "second")),
                List.of(row(file(1, "first"), 2), row(file(2, "second"), 1)));
        assertTrue(reversed.hasProblems());
        assertTrue(reversed.entries().stream().allMatch(entry -> entry.states().contains(MigrationStatus.State.OUT_OF_ORDER)));
    }
    @Test void invalidHistoryValuesAreRejectedWithoutEchoingThem() {
        AppliedMigration good = row(file(1, "first"), 1);
        assertThrows(IllegalArgumentException.class, () -> new MigrationHistory(false, List.of(good)));
        assertThrows(IllegalArgumentException.class, () -> new MigrationHistory(true, List.of(good, good)));
        assertThrows(IllegalArgumentException.class, () -> new AppliedMigration(good.id(), "../private", good.checksum(),
                good.startedAt(), good.finishedAt(), 0, "development"));
        assertThrows(IllegalArgumentException.class, () -> new AppliedMigration(good.id(), good.name(), good.checksum(),
                good.finishedAt(), good.startedAt(), -1, "private\nvalue"));
    }
    private static MigrationStatus compare(List<MigrationFile> files, List<AppliedMigration> rows) {
        return MigrationStatus.compare(files, new MigrationHistory(true, rows));
    }
    private static MigrationFile file(int second, String name) {
        return new MigrationFile(new MigrationId("20261030_1200%02d".formatted(second)), name, "-- draft\n".getBytes(StandardCharsets.UTF_8));
    }
    private static AppliedMigration row(MigrationFile file, int second) {
        Instant start = Instant.parse("2026-10-30T12:00:00Z").plusSeconds(second * 2L);
        return new AppliedMigration(file.id(), file.name(), file.checksum(), start, start.plusSeconds(1), 1000, "development");
    }
}
