package dev.tuprel.migrate.postgresql.ddl;

import static dev.tuprel.migrate.postgresql.ddl.DdlParseResult.Kind;

import dev.tuprel.migrate.MigrationFile;
import dev.tuprel.sql.SqlIdentifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Whole-input parser for the documented small DDL subset, not a general
 * PostgreSQL parser or safety verdict. Never opens files/connections or executes SQL.
 */
public final class PostgresqlDdlParser {
    /** Normative grammar identifier; recognition does not authorize execution. */
    public static final String GRAMMAR_VERSION = "postgresql-ddl-v1-proposal";
    /** Token budget including EOF; comments do not produce tokens. */
    public static final int MAX_TOKENS = 32768;
    /** Maximum nested block-comment depth, independent of the Java call stack. */
    public static final int MAX_COMMENT_DEPTH = 32;

    /** Creates a stateless, reusable parser facade. */
    public PostgresqlDdlParser() { }

    /**
     * Parses exact already-validated format-v1 input. Rejects the complete script
     * if any statement, terminator, comment or identifier is unsupported/invalid.
     * @param migration immutable validated bytes/checksum snapshot
     * @return complete AST or one safe location-only diagnostic
     */
    public DdlParseResult parse(MigrationFile migration) {
        Objects.requireNonNull(migration, "migration");
        String source = migration.sql();
        try {
            return new DdlParseResult(new Parser(source).program(), Optional.empty());
        } catch (Failure failure) {
            int line = 1;
            int column = 1;
            for (int offset = 0; offset < failure.offset; offset++) {
                if (source.charAt(offset) == '\n') { line++; column = 1; } else { column++; }
            }
            return new DdlParseResult(List.of(), Optional.of(new DdlParseResult.Diagnostic(failure.kind, line, column)));
        }
    }

    private enum TokenKind { WORD, IDENTIFIER, SYMBOL, UNKNOWN, END }
    private record Token(TokenKind kind, String text, int offset) {
        boolean word(String value) { return kind == TokenKind.WORD && text.equalsIgnoreCase(value); }
        boolean symbol(String value) { return kind == TokenKind.SYMBOL && text.equals(value); }
    }

    private static final class Failure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final Kind kind;
        private final int offset;
        Failure(Kind kind, int offset) { super(null, null, false, false); this.kind = kind; this.offset = offset; }
    }

    private static final class Lexer {
        private final String source;
        private int offset;
        private int tokens;
        Lexer(String source) { this.source = source; }

        Token next() {
            skipTrivia();
            if (++tokens > MAX_TOKENS) { throw new Failure(Kind.RESOURCE_LIMIT, offset); }
            int start = offset;
            if (offset == source.length()) { return new Token(TokenKind.END, "", offset); }
            char character = source.charAt(offset++);
            if (character == '"') {
                StringBuilder name = new StringBuilder();
                while (offset < source.length()) {
                    char next = source.charAt(offset++);
                    if (next == '"') {
                        if (offset < source.length() && source.charAt(offset) == '"') { offset++; name.append('"'); }
                        else { return new Token(TokenKind.IDENTIFIER, name.toString(), start); }
                    } else { name.append(next); }
                    if (name.length() > 63) { throw new Failure(Kind.INVALID_IDENTIFIER, start); }
                }
                throw new Failure(Kind.INVALID_SYNTAX, start);
            }
            if (asciiLetter(character) || character == '_') {
                while (offset < source.length() && (asciiLetter(source.charAt(offset))
                        || source.charAt(offset) == '_' || (source.charAt(offset) >= '0' && source.charAt(offset) <= '9'))) { offset++; }
                if (offset - start > 32) { throw new Failure(Kind.RESOURCE_LIMIT, start); }
                return new Token(TokenKind.WORD, source.substring(start, offset), start);
            }
            if (".(),;".indexOf(character) >= 0) { return new Token(TokenKind.SYMBOL, String.valueOf(character), start); }
            return new Token(TokenKind.UNKNOWN, "", start);
        }

        private void skipTrivia() {
            while (offset < source.length()) {
                char character = source.charAt(offset);
                if (character == ' ' || (character >= '\t' && character <= '\r')) { offset++; continue; }
                if (source.startsWith("--", offset)) {
                    offset += 2;
                    while (offset < source.length() && source.charAt(offset) != '\n') { offset++; }
                    continue;
                }
                if (source.startsWith("/*", offset)) {
                    int start = offset;
                    int depth = 1;
                    offset += 2;
                    while (depth != 0) {
                        if (offset >= source.length()) { throw new Failure(Kind.INVALID_SYNTAX, start); }
                        if (source.startsWith("/*", offset)) {
                            if (++depth > MAX_COMMENT_DEPTH) { throw new Failure(Kind.RESOURCE_LIMIT, offset); }
                            offset += 2;
                        } else if (source.startsWith("*/", offset)) { depth--; offset += 2; }
                        else { offset++; }
                    }
                    continue;
                }
                return;
            }
        }

        private static boolean asciiLetter(char value) { return (value >= 'a' && value <= 'z') || (value >= 'A' && value <= 'Z'); }
    }

    private static final class Parser {
        private final Lexer lexer;
        private Token look;
        Parser(String source) { lexer = new Lexer(source); look = lexer.next(); }

        List<DdlStatement> program() {
            if (look.kind() == TokenKind.END) { fail(Kind.EMPTY_SCRIPT); }
            List<DdlStatement> statements = new ArrayList<>();
            while (look.kind() != TokenKind.END) {
                if (statements.size() >= DdlParseResult.MAX_STATEMENTS) { fail(Kind.RESOURCE_LIMIT); }
                try { statements.add(statement()); }
                catch (IllegalArgumentException exception) { fail(Kind.INVALID_SYNTAX); }
                symbol(";");
            }
            return List.copyOf(statements);
        }

        private DdlStatement statement() {
            if (acceptWord("CREATE")) {
                if (acceptWord("TABLE")) { return createTable(); }
                if (acceptWord("INDEX")) { return createIndex(); }
                fail(Kind.UNSUPPORTED_SQL);
            }
            if (acceptWord("ALTER")) {
                word("TABLE"); DdlStatement.TableName table = table(); word("ADD"); word("COLUMN");
                SqlIdentifier name = identifier(); DdlStatement.Type type = type(); acceptWord("NULL");
                return new DdlStatement.AddColumn(table, name, type);
            }
            fail(Kind.UNSUPPORTED_SQL);
            throw new AssertionError("Unreachable");
        }

        private DdlStatement.CreateTable createTable() {
            DdlStatement.TableName table = table(); symbol("(");
            List<DdlStatement.Column> columns = new ArrayList<>();
            do {
                if (columns.size() >= DdlStatement.MAX_COLUMNS) { fail(Kind.RESOURCE_LIMIT); }
                SqlIdentifier name = identifier(); DdlStatement.Type type = type();
                boolean nonNull = false;
                boolean primaryKey = false;
                while (look.word("NOT") || look.word("PRIMARY")) {
                    if (acceptWord("NOT")) {
                        if (nonNull) { fail(Kind.INVALID_SYNTAX); }
                        word("NULL"); nonNull = true;
                    } else {
                        if (primaryKey) { fail(Kind.INVALID_SYNTAX); }
                        word("PRIMARY"); word("KEY"); primaryKey = true;
                    }
                }
                columns.add(new DdlStatement.Column(name, type, !nonNull && !primaryKey, primaryKey));
            } while (acceptSymbol(","));
            symbol(")");
            return new DdlStatement.CreateTable(table, columns);
        }

        private DdlStatement.CreateIndex createIndex() {
            SqlIdentifier name = identifier(); word("ON"); DdlStatement.TableName table = table(); symbol("(");
            List<SqlIdentifier> columns = new ArrayList<>();
            do {
                if (columns.size() >= DdlStatement.MAX_INDEX_COLUMNS) { fail(Kind.RESOURCE_LIMIT); }
                columns.add(identifier());
            } while (acceptSymbol(","));
            symbol(")");
            return new DdlStatement.CreateIndex(table, name, columns);
        }

        private DdlStatement.Type type() {
            if (look.kind() == TokenKind.IDENTIFIER) {
                SqlIdentifier namespace = identifier(); symbol("."); SqlIdentifier name = identifier();
                if (!namespace.name().equals("pg_catalog")) { fail(Kind.UNSUPPORTED_SQL); }
                try { return DdlStatement.Type.fromCatalogName(name.name()); }
                catch (IllegalArgumentException exception) { fail(Kind.UNSUPPORTED_SQL); }
            }
            if (acceptWord("TIMESTAMP")) { word("WITH"); word("TIME"); word("ZONE"); return DdlStatement.Type.TIMESTAMPTZ; }
            for (DdlStatement.Type type : DdlStatement.Type.values()) {
                if (acceptWord(type.name())) { return type; }
            }
            fail(Kind.UNSUPPORTED_SQL);
            throw new AssertionError("Unreachable");
        }

        private DdlStatement.TableName table() { SqlIdentifier schema = identifier(); symbol("."); return new DdlStatement.TableName(schema, identifier()); }
        private SqlIdentifier identifier() {
            if (look.kind() != TokenKind.IDENTIFIER) { fail(Kind.UNSUPPORTED_SQL); }
            Token token = look;
            SqlIdentifier name;
            try { name = new SqlIdentifier(token.text()); }
            catch (IllegalArgumentException exception) { throw new Failure(Kind.INVALID_IDENTIFIER, token.offset()); }
            advance(); return name;
        }
        private boolean acceptWord(String word) { if (!look.word(word)) { return false; } advance(); return true; }
        private boolean acceptSymbol(String symbol) { if (!look.symbol(symbol)) { return false; } advance(); return true; }
        private void word(String word) { if (!acceptWord(word)) { fail(Kind.INVALID_SYNTAX); } }
        private void symbol(String symbol) { if (!acceptSymbol(symbol)) { fail(Kind.INVALID_SYNTAX); } }
        private void advance() { look = lexer.next(); }
        private void fail(Kind kind) {
            if (look.word("DROP") || look.word("TRUNCATE") || look.word("DELETE") || look.word("UPDATE")) { kind = Kind.DESTRUCTIVE_PREFIX; }
            throw new Failure(kind, look.offset());
        }
    }
}
