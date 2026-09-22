package dev.tuprel.codegen;

import dev.tuprel.schema.SourceSpan;
import java.util.Objects;

/** A source-located reason why a valid Tuprel schema cannot be generated as Java. */
public record GenerationDiagnostic(String code, String message, SourceSpan span) {
    public GenerationDiagnostic {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(span, "span");
        if (!code.startsWith("TUPREL-CODEGEN-") || message.isBlank()) {
            throw new IllegalArgumentException("invalid generation diagnostic");
        }
    }
}
