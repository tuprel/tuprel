package dev.tuprel.migrate.postgresql;

import dev.tuprel.migrate.postgresql.diff.SchemaDiff;
import java.util.Optional;
import java.util.Set;

/** Point-in-time migration proposal for review. Contains no execution approval or reserved artifact ID. */
public final class MigrationDevPlan {
    /** Outcome of the read-only planning operation. */
    public enum State {
        /** The verified reference and desired schema agree. */ NO_CHANGES,
        /** A complete canonical SQL proposal is available for review. */ REVIEW_REQUIRED,
        /** Planning was refused and there is no SQL proposal. */ BLOCKED
    }

    /** Redacted reason planning could not return a complete proposal. */
    public enum Diagnostic {
        /** Target, migration name or configuration is invalid. */ INPUT,
        /** The desired schema is outside the projection profile. */ PROJECTION,
        /** The applied reference is unverified or physical drift was detected. */ REFERENCE,
        /** Every local artifact must already be applied before planning another. */ PENDING,
        /** The complete logical difference is outside the additive subset or over budget. */ DIFF,
        /** The proposal cannot fit within the format-v1 catalog and single-file limits. */ CAPACITY,
        /** The proposed SQL did not pass validation or reproduce the desired schema. */ REPLAY
    }

    private final State state;
    private final Diagnostic diagnostic;
    private final PhysicalDriftReport observation;
    private final Set<SchemaDiff.Risk> risks;
    private final String sql;

    private MigrationDevPlan(State state, Diagnostic diagnostic, PhysicalDriftReport observation,
            Set<SchemaDiff.Risk> risks, String sql) {
        this.state = state;
        this.diagnostic = diagnostic;
        this.observation = observation;
        this.risks = Set.copyOf(risks);
        this.sql = sql;
    }

    static MigrationDevPlan blocked(Diagnostic diagnostic, PhysicalDriftReport observation) {
        return new MigrationDevPlan(State.BLOCKED, diagnostic, observation, Set.of(), null);
    }

    static MigrationDevPlan unchanged(PhysicalDriftReport observation) {
        return new MigrationDevPlan(State.NO_CHANGES, null, observation, Set.of(), null);
    }

    static MigrationDevPlan preview(PhysicalDriftReport observation, Set<SchemaDiff.Risk> risks, String sql) {
        return new MigrationDevPlan(State.REVIEW_REQUIRED, null, observation, risks, sql);
    }

    /** {@return the planning outcome} */
    public State state() { return state; }

    /** {@return the reason for a blocked proposal, when present} */
    public Optional<Diagnostic> diagnostic() { return Optional.ofNullable(diagnostic); }

    /** {@return the drift observation, if planning reached the reference check} */
    public Optional<PhysicalDriftReport> observation() { return Optional.ofNullable(observation); }

    /** {@return the risks associated with the complete preview} */
    public Set<SchemaDiff.Risk> risks() { return risks; }

    /** {@return canonical SQL with LF endings when review is required, otherwise empty} */
    public Optional<String> sql() { return Optional.ofNullable(sql); }

    /** Returns a summary without SQL, identifiers or credentials. */
    @Override public String toString() { return "MigrationDevPlan[state=" + state + ", diagnostic=" + diagnostic + "]"; }
}
