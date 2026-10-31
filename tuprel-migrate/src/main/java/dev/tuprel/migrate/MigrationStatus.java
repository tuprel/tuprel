package dev.tuprel.migrate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Pure comparison; physical database drift is never checked here.
 * @param initialised history table presence
 * @param entries sorted union of local and applied identities
 */
public record MigrationStatus(boolean initialised, List<Entry> entries) {
    /** Copies entries to retain an immutable result. */
    public MigrationStatus { entries = List.copyOf(entries); }
    /** Independent states; a row can have several divergences. */
    public enum State {
        /** ID is recorded in history. */ APPLIED,
        /** ID only exists locally. */ PENDING,
        /** Applied ID is absent locally. */ REMOVED,
        /** Exact checksum differs. */ MODIFIED,
        /** Name differs for the same ID. */ RENAMED,
        /** Applied history is not a chronological prefix of the local catalog. */ OUT_OF_ORDER
    }
    /**
     * One identity with its independent state flags.
     * @param id canonical ID
     * @param name canonical local name, or historical name for a removed file
     * @param states immutable flags
     */
    public record Entry(MigrationId id, String name, Set<State> states) {
        /** Copies flags. */
        public Entry { states = Set.copyOf(states); }
    }
    /**
     * Compares snapshots without modifying either or interpreting SQL.
     * @param local validated catalog, with distinct IDs
     * @param history confirmed database snapshot
     * @return deterministic sorted report
     */
    public static MigrationStatus compare(List<MigrationFile> local, MigrationHistory history) {
        Map<MigrationId, MigrationFile> files = local.stream().collect(Collectors.toMap(
                MigrationFile::id, Function.identity()));
        Map<MigrationId, EnumSet<State>> states = new TreeMap<>();
        Map<MigrationId, String> names = new TreeMap<>();
        files.forEach((id, file) -> {
            states.put(id, EnumSet.of(State.PENDING));
            names.put(id, file.name());
        });
        List<AppliedMigration> applied = history.applied().stream().sorted(Comparator.comparing(AppliedMigration::id)).toList();
        AppliedMigration previous = null;
        for (AppliedMigration row : applied) {
            MigrationFile file = files.get(row.id());
            EnumSet<State> flags = EnumSet.of(State.APPLIED);
            if (file == null) {
                flags.add(State.REMOVED);
                names.put(row.id(), row.name());
            } else {
                if (!row.name().equals(file.name())) { flags.add(State.RENAMED); }
                if (!row.checksum().equals(file.checksum())) { flags.add(State.MODIFIED); }
            }
            if (previous != null && row.startedAt().isBefore(previous.finishedAt())) {
                flags.add(State.OUT_OF_ORDER);
                states.get(previous.id()).add(State.OUT_OF_ORDER);
            }
            states.put(row.id(), flags);
            previous = row;
        }
        if (!applied.isEmpty()) {
            MigrationId last = applied.getLast().id();
            boolean gap = states.entrySet().stream().anyMatch(entry ->
                    entry.getKey().compareTo(last) < 0 && entry.getValue().contains(State.PENDING));
            if (gap) {
                states.forEach((id, flags) -> {
                    if (id.compareTo(last) <= 0) { flags.add(State.OUT_OF_ORDER); }
                });
            }
        }
        List<Entry> entries = new ArrayList<>();
        states.forEach((id, flags) -> entries.add(new Entry(id, names.get(id), flags)));
        return new MigrationStatus(history.initialised(), entries);
    }
    /**
     * Indicates integrity/order divergences, excluding ordinary pending work.
     * @return true if manual inspection is needed
     */
    public boolean hasProblems() {
        return entries.stream().anyMatch(entry -> entry.states().stream()
                .anyMatch(state -> state != State.APPLIED && state != State.PENDING));
    }
}
