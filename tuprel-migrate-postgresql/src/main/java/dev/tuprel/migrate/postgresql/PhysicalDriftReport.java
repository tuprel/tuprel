package dev.tuprel.migrate.postgresql;

import dev.tuprel.migrate.postgresql.diff.SchemaDiff;
import java.util.List;
import java.util.Optional;

/** Immutable drift assessment limited to the current catalog coverage profile. */
public final class PhysicalDriftReport {
    /** Point-in-time comparison outcome. */
    public enum State {
        /** Verified reference and complete observation agree within the profile. */ NO_DRIFT,
        /** Verified reference and complete observation differ within the profile. */ DRIFT_DETECTED,
        /** A comparable observation or trustworthy reference could not be established. */ UNKNOWN
    }

    /** Redacted reason an assessment was refused. */
    public enum Diagnostic {
        /** Catalog or session evidence is unavailable or incomplete. */ CATALOG,
        /** Required administrative provenance and DDL control were not confirmed. */ REFERENCE_CONTROL,
        /** Managed history is absent or the initial-target claim does not match the evidence. */ REFERENCE_MISSING,
        /** Namespace or history identity could not be verified before reading. */ HISTORY_IDENTITY,
        /** History row or canonical structure validation failed. */ HISTORY_INCOMPATIBLE,
        /** The v1 file catalog could not be read and validated. */ LOCAL_CATALOG,
        /** History and local artifacts disagree on the exact applied prefix. */ APPLIED_PREFIX,
        /** The verified applied scripts could not be replayed within the supported profile. */ REPLAY
    }

    private final State state;
    private final Diagnostic diagnostic;
    private final List<CatalogObservation.Diagnostic> catalogDiagnostics;
    private final List<SchemaDiff.Change> differences;

    private PhysicalDriftReport(State state, Diagnostic diagnostic,
            List<CatalogObservation.Diagnostic> catalogDiagnostics, List<SchemaDiff.Change> differences) {
        this.state = state;
        this.diagnostic = diagnostic;
        this.catalogDiagnostics = List.copyOf(catalogDiagnostics);
        this.differences = List.copyOf(differences);
    }

    static PhysicalDriftReport compared(List<SchemaDiff.Change> differences) {
        return new PhysicalDriftReport(differences.isEmpty() ? State.NO_DRIFT : State.DRIFT_DETECTED,
                null, List.of(), differences);
    }

    static PhysicalDriftReport unknown(Diagnostic diagnostic) {
        return new PhysicalDriftReport(State.UNKNOWN, diagnostic, List.of(), List.of());
    }

    static PhysicalDriftReport catalogUnknown(List<CatalogObservation.Diagnostic> diagnostics) {
        return new PhysicalDriftReport(State.UNKNOWN, Diagnostic.CATALOG, diagnostics, List.of());
    }

    /** {@return the point-in-time drift result} */
    public State state() { return state; }

    /** {@return the catalog coverage version for this result} */
    public String profile() { return CatalogObservation.PROFILE; }

    /** {@return the redacted reason for an unknown result, when present} */
    public Optional<Diagnostic> diagnostic() { return Optional.ofNullable(diagnostic); }

    /** {@return redacted catalog diagnostic categories, when present} */
    public List<CatalogObservation.Diagnostic> catalogDiagnostics() { return catalogDiagnostics; }

    /**
     * Returns exact-name differences from expected to observed for explicit inspection.
     * @return immutable differences without execution risks or SQL, empty for unknown or equal states
     */
    public List<SchemaDiff.Change> differences() { return differences; }

    /** Returns the profile, outcome and safe counts/categories. */
    @Override public String toString() {
        return "PhysicalDriftReport[profile=" + profile() + ", state=" + state + ", diagnostic=" + diagnostic
                + ", catalogDiagnostics=" + catalogDiagnostics + ", differences=" + differences.size() + "]";
    }
}
