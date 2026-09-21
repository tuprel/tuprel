package dev.tuprel.schema;

import dev.tuprel.schema.ast.SchemaDocument;
import dev.tuprel.schema.model.ValidatedSchema;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Result of lexing, parsing and conditionally validating one schema source. */
public record SchemaCompilation(
        Optional<SchemaDocument> syntax,
        Optional<ValidatedSchema> validatedSchema,
        List<SchemaDiagnostic> diagnostics) {
    /** Defensively copies the result. */
    public SchemaCompilation {
        Objects.requireNonNull(syntax, "syntax");
        Objects.requireNonNull(validatedSchema, "validatedSchema");
        diagnostics = List.copyOf(diagnostics);
    }

    /** Returns true only when a validated schema exists and there are no errors. */
    public boolean isValid() {
        return validatedSchema.isPresent()
                && diagnostics.stream()
                        .noneMatch(diagnostic -> diagnostic.severity() == DiagnosticSeverity.ERROR);
    }
}
