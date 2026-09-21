package dev.tuprel.schema;

import java.util.Objects;

/** Renders structured schema diagnostics for terminals without changing their model. */
public final class DiagnosticRenderer {
    /** Renders a diagnostic as code, message, location, source line and caret. */
    public String render(SchemaDiagnostic diagnostic) {
        Objects.requireNonNull(diagnostic, "diagnostic");
        SourcePosition position = diagnostic.span().start();
        String line = diagnostic.span().source().lineText(position.line());
        int caretWidth = Math.max(1, diagnostic.span().endOffset() - diagnostic.span().startOffset());
        String caret = " ".repeat(Math.max(0, position.column() - 1)) + "^".repeat(caretWidth);
        return "%s: %s%n%s:%d:%d%n%s%n%s"
                .formatted(
                        diagnostic.code(),
                        diagnostic.message(),
                        diagnostic.span().source().name(),
                        position.line(),
                        position.column(),
                        line,
                        caret);
    }
}
