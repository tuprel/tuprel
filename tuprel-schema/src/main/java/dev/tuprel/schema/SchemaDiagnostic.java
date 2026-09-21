package dev.tuprel.schema;

import java.util.Objects;

/** A stable, structured diagnostic produced by the schema front end. */
public record SchemaDiagnostic(
        String code, DiagnosticSeverity severity, String message, SourceSpan span) {
    /** Validates the public diagnostic contract. */
    public SchemaDiagnostic {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(span, "span");
        if (!code.startsWith("TUPREL-SCHEMA-")) {
            throw new IllegalArgumentException("schema diagnostic code has an invalid prefix");
        }
        if (message.isBlank()) {
            throw new IllegalArgumentException("diagnostic message must not be blank");
        }
    }
}
