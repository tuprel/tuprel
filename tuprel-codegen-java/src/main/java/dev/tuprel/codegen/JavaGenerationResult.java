package dev.tuprel.codegen;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable outcome of Java source rendering. */
public record JavaGenerationResult(
        Optional<GeneratedJavaSources> sources, List<GenerationDiagnostic> diagnostics) {
    public JavaGenerationResult {
        Objects.requireNonNull(sources, "sources");
        diagnostics = List.copyOf(diagnostics);
        if (sources.isPresent() == !diagnostics.isEmpty()) {
            throw new IllegalArgumentException("a result must contain sources or diagnostics");
        }
    }

    public boolean isSuccess() {
        return sources.isPresent();
    }
}
