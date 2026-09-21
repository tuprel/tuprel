package dev.tuprel.schema;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Result of validating and formatting one schema source. */
public record SchemaFormatResult(
        Optional<String> formattedText, List<SchemaDiagnostic> diagnostics) {
    /** Defensively copies formatter output. */
    public SchemaFormatResult {
        Objects.requireNonNull(formattedText, "formattedText");
        diagnostics = List.copyOf(diagnostics);
    }

    /** Returns true when formatted text is available. */
    public boolean isSuccess() {
        return formattedText.isPresent() && diagnostics.isEmpty();
    }
}
