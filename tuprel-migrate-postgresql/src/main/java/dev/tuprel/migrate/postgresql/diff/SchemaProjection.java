package dev.tuprel.migrate.postgresql.diff;

import dev.tuprel.schema.SourcePosition;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Complete projection or safe detached diagnostics; never a partial snapshot.
 * @param snapshot complete state only when no diagnostics exist
 * @param diagnostics immutable detached refusal categories and positions
 */
public record SchemaProjection(Optional<SchemaSnapshot> snapshot, List<Diagnostic> diagnostics) {
    /** Refusal categories, without identifiers, expressions or configuration values. */
    public enum Kind {
        /** Unsupported or reserved target/name. */ INVALID_IDENTIFIER,
        /** Scalar is outside the accepted DDL grammar. */ UNSUPPORTED_TYPE,
        /** Defaults are not modeled. */ DEFAULT,
        /** Unique constraints are not modeled. */ UNIQUE,
        /** Relations and their constraints are not modeled. */ RELATION,
        /** Enum declarations/fields are not modeled. */ ENUM,
        /** List columns are not modeled. */ LIST,
        /** Index shape is unsupported. */ INDEX,
        /** Duplicate object or inconsistent model. */ CONFLICT,
        /** Bounded projection budget exceeded. */ RESOURCE_LIMIT
    }

    /**
     * Detached source positions, deliberately not SourceSpan (which retains source).
     * @param kind safe category
     * @param start start position without source/path
     * @param end end position without source/path
     */
    public record Diagnostic(Kind kind, SourcePosition start, SourcePosition end) {
        /** Requires complete detached metadata. */
        public Diagnostic { Objects.requireNonNull(kind, "kind"); Objects.requireNonNull(start, "start"); Objects.requireNonNull(end, "end"); }
    }

    /** Enforces all-or-nothing results and immutable diagnostics. */
    public SchemaProjection {
        Objects.requireNonNull(snapshot, "snapshot"); diagnostics = List.copyOf(diagnostics);
        if (snapshot.isPresent() != diagnostics.isEmpty()) { throw new IllegalArgumentException("Projection must be complete or refused"); }
    }

    /** Omits metadata and source from incidental logging. */
    @Override public String toString() { return "SchemaProjection[supported=" + snapshot.isPresent() + ", diagnostics=" + diagnostics.size() + "]"; }
}
