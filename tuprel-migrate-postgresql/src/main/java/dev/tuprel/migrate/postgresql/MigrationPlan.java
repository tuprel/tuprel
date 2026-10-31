package dev.tuprel.migrate.postgresql;

import dev.tuprel.migrate.MigrationChecksum;
import dev.tuprel.migrate.MigrationFile;
import dev.tuprel.migrate.MigrationId;
import dev.tuprel.migrate.postgresql.ddl.DdlParseResult;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Immutable offline proposal; no execution or approval operation exists. */
public final class MigrationPlan {
    /** Whole-plan disposition, never a SAFE classification. */
    public enum State {
        /** No pending artifacts; does not prove physical schema equivalence. */ NO_CHANGES,
        /** Known syntax requiring explicit review of risks and contract. */ REVIEW_REQUIRED,
        /** Unknown, destructive, inconsistent or out-of-contract work. */ BLOCKED
    }
    /** Conservative independent risks; review notices do not imply execution permission. */
    public enum Risk {
        /** Physical schema/catalog not compared. */ PHYSICAL_DRIFT_NOT_CHECKED,
        /** DDL acquires locks, potentially waiting on other work. */ DDL_LOCKS,
        /** Ordinary index builds can block application writes. */ INDEX_WRITE_BLOCKING,
        /** Index build resource/time costs have not been measured. */ INDEX_BUILD_COST,
        /** Unknown SQL or unsupported syntax. */ UNKNOWN_SQL,
        /** Known destructive prefix, not an exhaustive classifier. */ DESTRUCTIVE_SQL,
        /** Comment-only draft. */ EMPTY_DRAFT,
        /** Local and applied history do not form an unchanged ordered prefix. */ HISTORY_DIVERGENCE,
        /** SQL references a schema other than the explicit target. */ TARGET_MISMATCH,
        /** Reserved system target is outside this proposal. */ TARGET_UNSUPPORTED,
        /** A known object/reference conflicts within the pending plan. */ PLAN_CONFLICT,
        /** Whole-plan budget exceeded. */ RESOURCE_LIMIT
    }

    /** Safe metadata for one pending artifact; no raw SQL or configuration retained. */
    public static final class Script {
        private final MigrationId id;
        private final String name;
        private final MigrationChecksum checksum;
        private final int statements;
        private final Optional<MigrationChecksum> previewChecksum;
        private final Set<Risk> risks;
        private final Optional<DdlParseResult.Diagnostic> diagnostic;
        Script(MigrationFile file, int statements, Optional<MigrationChecksum> previewChecksum,
                Set<Risk> risks, Optional<DdlParseResult.Diagnostic> diagnostic) {
            id = file.id(); name = file.name(); checksum = file.checksum(); this.statements = statements;
            this.previewChecksum = previewChecksum; this.risks = Set.copyOf(risks); this.diagnostic = diagnostic;
        }
        /**
         * Returns the artifact identity.
         * @return canonical artifact identity
         */
        public MigrationId id() { return id; }
        /**
         * Returns the artifact name.
         * @return canonical artifact name
         */
        public String name() { return name; }
        /**
         * Returns the original bytes checksum.
         * @return exact original bytes checksum
         */
        public MigrationChecksum checksum() { return checksum; }
        /**
         * Returns the completely parsed statement count.
         * @return count of completely parsed statements
         */
        public int statements() { return statements; }
        /**
         * Returns the canonical SQL checksum when parsing succeeded.
         * @return canonical SQL checksum, absent for unparsed work
         */
        public Optional<MigrationChecksum> previewChecksum() { return previewChecksum; }
        /**
         * Returns the artifact risk flags.
         * @return immutable independent risks
         */
        public Set<Risk> risks() { return risks; }
        /**
         * Returns the safe parsing diagnostic when present.
         * @return safe syntax diagnostic if parsing failed
         */
        public Optional<DdlParseResult.Diagnostic> diagnostic() { return diagnostic; }
        /** Excludes SQL from incidental logging. */
        @Override public String toString() { return "MigrationPlan.Script[id=" + id.value() + ", statements=" + statements + "]"; }
    }

    private final State state;
    private final List<Script> scripts;
    private final Set<Risk> risks;
    private final MigrationPlanDigest digest;
    private final String preview;
    MigrationPlan(State state, List<Script> scripts, Set<Risk> risks, MigrationPlanDigest digest, String preview) {
        this.state = state; this.scripts = List.copyOf(scripts); this.risks = Set.copyOf(risks);
        this.digest = digest; this.preview = preview;
    }
    /**
     * Returns the proposal disposition without authorizing execution.
     * @return proposal disposition; never runtime authorization
     */
    public State state() { return state; }
    /**
     * Returns pending artifacts in ID order.
     * @return immutable pending artifact summaries in ID order
     */
    public List<Script> scripts() { return scripts; }
    /**
     * Returns risks including unverified physical drift.
     * @return immutable risks, including explicit unverified physical drift
     */
    public Set<Risk> risks() { return risks; }
    /**
     * Returns the proposal digest without attesting human review.
     * @return context-bound proposal digest; not proof of human review
     */
    public MigrationPlanDigest digest() { return digest; }
    /**
     * Explicit all-or-nothing preview. No partial SQL is returned for blocked plans.
     * @return canonical SQL, empty when no changes are pending
     * @throws IllegalStateException when any work is blocked
     */
    public String preview() {
        if (state == State.BLOCKED) { throw new IllegalStateException("No preview for a blocked migration plan"); }
        return preview;
    }
    /** Excludes SQL, endpoints, schema and credentials. */
    @Override public String toString() { return "MigrationPlan[state=" + state + ", scripts=" + scripts.size() + ", digest=" + digest.value() + "]"; }
}
