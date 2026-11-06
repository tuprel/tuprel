package dev.tuprel.migrate.postgresql;

import dev.tuprel.migrate.postgresql.diff.SchemaSnapshot;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Immutable result for a single point-in-time observation of the supported catalog profile. */
public final class CatalogObservation {
    /** Version of the PostgreSQL 17 catalog coverage in ADR-0020. */
    public static final String PROFILE = "postgresql-17-catalog-v1";

    /** Coverage and reliability of the observation. */
    public enum State {
        /** All in-profile objects were validated and the session was closed. */ COMPLETE,
        /** A reliable reading identified objects outside the profile. */ INCOMPLETE,
        /** The identity, evidence, budget or lifecycle could not be verified. */ UNKNOWN
    }

    /** Redacted categories, independent of object names and JDBC diagnostics. */
    public enum Diagnostic {
        /** Configured endpoint, identity, or target namespace did not match. */ TARGET,
        /** The PostgreSQL major version or transaction mode did not match. */ SESSION,
        /** Insufficient permission to establish completeness. */ PERMISSION,
        /** A statement, lock or transaction deadline expired. */ TIMEOUT,
        /** A connection or catalog read failed. */ READ,
        /** A catalog reference or internal invariant could not be verified. */ METADATA,
        /** The evidence exceeded the profile budget. */ RESOURCE_LIMIT,
        /** The session could not be rolled back and closed reliably. */ CLEANUP,
        /** The inventory contains an out-of-profile relation or associated object. */ RELATION,
        /** A column property or type is outside the profile. */ COLUMN,
        /** A constraint is outside the profile. */ CONSTRAINT,
        /** An index or its key metadata is outside the profile. */ INDEX,
        /** The reserved history table or one of its associated objects is incompatible. */ HISTORY,
        /** The schema contains a type outside the profile. */ TYPE
    }

    private final State state;
    private final SchemaSnapshot snapshot;
    private final Map<String, Long> relationOids;
    private final List<Diagnostic> diagnostics;

    private CatalogObservation(State state, SchemaSnapshot snapshot, Map<String, Long> relationOids, List<Diagnostic> diagnostics) {
        this.state = state;
        this.snapshot = snapshot;
        this.relationOids = Map.copyOf(relationOids);
        this.diagnostics = List.copyOf(diagnostics);
    }

    static CatalogObservation complete(SchemaSnapshot snapshot, Map<String, Long> relationOids) {
        return new CatalogObservation(State.COMPLETE, snapshot, relationOids, List.of());
    }

    static CatalogObservation incomplete(List<Diagnostic> diagnostics) {
        return new CatalogObservation(State.INCOMPLETE, null, Map.of(), diagnostics);
    }

    static CatalogObservation unknown(Diagnostic diagnostic) {
        return new CatalogObservation(State.UNKNOWN, null, Map.of(), List.of(diagnostic));
    }

    /** {@return reliability and coverage of the point-in-time observation} */
    public State state() { return state; }

    /** {@return the PostgreSQL catalog coverage identifier} */
    public String profile() { return PROFILE; }

    /** {@return the comparable snapshot, available only for a complete observation} */
    public Optional<SchemaSnapshot> snapshot() { return Optional.ofNullable(snapshot); }

    // Identity evidence stays internal and is available only on a complete observation.
    Map<String, Long> relationOids() { return relationOids; }

    /** {@return immutable diagnostics in deterministic category order} */
    public List<Diagnostic> diagnostics() { return diagnostics; }

    /** Returns a summary without identifiers, configuration, source SQL or driver messages. */
    @Override public String toString() {
        return "CatalogObservation[profile=" + PROFILE + ", state=" + state + ", diagnostics=" + diagnostics + "]";
    }
}
