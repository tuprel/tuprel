package dev.tuprel.schema;

import dev.tuprel.schema.ast.SchemaDocument;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Entry point for the complete Tuprel schema front-end pipeline. */
public final class SchemaCompiler {
    /** Lexes, parses and, when syntax is sound, semantically validates a schema. */
    public SchemaCompilation compile(SourceText source) {
        Objects.requireNonNull(source, "source");
        Lexer.Result lexed = Lexer.lex(source);
        Parser.Result parsed = Parser.parse(source, lexed.tokens());

        List<SchemaDiagnostic> diagnostics = new ArrayList<>(lexed.diagnostics());
        diagnostics.addAll(parsed.diagnostics());
        Optional<dev.tuprel.schema.model.ValidatedSchema> validated = Optional.empty();
        if (diagnostics.isEmpty()) {
            SemanticValidator.Result result = SemanticValidator.validate(parsed.document());
            diagnostics.addAll(result.diagnostics());
            validated = result.schema();
        }

        diagnostics.sort(
                Comparator.comparingInt(
                                (SchemaDiagnostic diagnostic) ->
                                        diagnostic.span().startOffset())
                        .thenComparing(SchemaDiagnostic::code));
        SchemaDocument syntax = parsed.document();
        return new SchemaCompilation(Optional.of(syntax), validated, diagnostics);
    }
}
