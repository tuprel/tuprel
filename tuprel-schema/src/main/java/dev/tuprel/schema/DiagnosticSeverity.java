package dev.tuprel.schema;

/** Severity of a structured schema diagnostic. */
public enum DiagnosticSeverity {
    /** The schema cannot be accepted. */
    ERROR,
    /** The schema is accepted but deserves attention. */
    WARNING
}
