package dev.tuprel.migrate.postgresql.ddl;

import dev.tuprel.sql.SqlIdentifier;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Whole-script result. Failure never exposes a partially supported prefix or source SQL.
 * @param statements immutable complete AST on success; empty on failure
 * @param diagnostic safe failure without input text
 */
public record DdlParseResult(List<DdlStatement> statements, Optional<Diagnostic> diagnostic) {
    /** Canonical renderer version to bind into any future approval digest. */
    public static final String PREVIEW_VERSION = "postgresql-ddl-preview-v1-proposal";
    /** Maximum statements, including their required semicolon terminators. */
    public static final int MAX_STATEMENTS = 256;

    /** Result categories; unrecognised SQL is never considered safe. */
    public enum Kind {
        /** Empty/comment-only draft is not a migration program. */ EMPTY_SCRIPT,
        /** SQL lies outside this deliberately small grammar. */ UNSUPPORTED_SQL,
        /** Incomplete/malformed statement, quote or comment. */ INVALID_SYNTAX,
        /** Identifier violates the existing portable ASCII contract. */ INVALID_IDENTIFIER,
        /** One of the explicit bounded parsing limits was exceeded. */ RESOURCE_LIMIT,
        /** Known destructive/DML prefix; not a comprehensive arbitrary-SQL classifier. */ DESTRUCTIVE_PREFIX
    }

    /**
     * Safe location-only diagnostic.
     * @param kind failure category
     * @param line one-based source line
     * @param column one-based UTF-16 source column
     */
    public record Diagnostic(Kind kind, int line, int column) {
        /** Validates location and kind without retaining source. */
        public Diagnostic {
            Objects.requireNonNull(kind, "kind");
            if (line < 1 || column < 1) { throw new IllegalArgumentException("Invalid source location"); }
        }
    }

    /** Enforces all-or-nothing immutable results. */
    public DdlParseResult {
        statements = List.copyOf(statements); Objects.requireNonNull(diagnostic, "diagnostic");
        if (diagnostic.isPresent() ? !statements.isEmpty() : statements.isEmpty() || statements.size() > MAX_STATEMENTS) {
            throw new IllegalArgumentException("Invalid DDL parse result");
        }
    }

    /**
     * Indicates complete syntax recognition, never execution approval.
     * @return true only for a complete supported AST
     */
    public boolean isSupported() { return diagnostic.isEmpty(); }

    /**
     * Produces canonical UTF-8-compatible SQL text with LF and explicit built-in
     * type qualification. Comments are omitted, but original bytes/checksums must
     * still be bound separately by any approval plan. No database is consulted.
     * @return deterministic SQL preview, never implicitly executed
     * @throws IllegalStateException if any part of the original script failed
     */
    public String preview() {
        if (!isSupported()) { throw new IllegalStateException("No preview for unsupported DDL"); }
        return statements.stream().map(DdlParseResult::render).collect(Collectors.joining(""));
    }

    /** Excludes identifiers and SQL from incidental logging. */
    @Override public String toString() { return "DdlParseResult[supported=" + isSupported() + ", statements=" + statements.size() + "]"; }

    private static String render(DdlStatement statement) {
        String table = quote(statement.table().schema()) + "." + quote(statement.table().name());
        return switch (statement) {
            case DdlStatement.CreateTable create -> "CREATE TABLE " + table + " (\n"
                    + create.columns().stream().map(column -> "    " + quote(column.name()) + " " + column.type().sql()
                            + (column.nullable() ? "" : " NOT NULL") + (column.primaryKey() ? " PRIMARY KEY" : ""))
                            .collect(Collectors.joining(",\n")) + "\n);\n";
            case DdlStatement.AddColumn add -> "ALTER TABLE " + table + " ADD COLUMN " + quote(add.name()) + " " + add.type().sql() + ";\n";
            case DdlStatement.CreateIndex index -> "CREATE INDEX " + quote(index.name()) + " ON " + table + " ("
                    + index.columns().stream().map(DdlParseResult::quote).collect(Collectors.joining(", ")) + ");\n";
        };
    }

    private static String quote(SqlIdentifier name) { return "\"" + name.name() + "\""; }
}
