package dev.tuprel.schema;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class Lexer {
    enum TokenKind {
        IDENTIFIER,
        MODEL,
        ENUM,
        DATASOURCE,
        GENERATOR,
        TRUE,
        FALSE,
        STRING,
        INTEGER,
        LEFT_BRACE,
        RIGHT_BRACE,
        LEFT_PAREN,
        RIGHT_PAREN,
        LEFT_BRACKET,
        RIGHT_BRACKET,
        QUESTION,
        COMMA,
        COLON,
        EQUALS,
        AT,
        AT_AT,
        COMMENT,
        EOF
    }

    record Token(TokenKind kind, String text, String value, SourceSpan span) {}

    record Result(List<Token> tokens, List<SchemaDiagnostic> diagnostics) {
        Result {
            tokens = List.copyOf(tokens);
            diagnostics = List.copyOf(diagnostics);
        }
    }

    private static final Map<String, TokenKind> KEYWORDS =
            Map.of(
                    "model", TokenKind.MODEL,
                    "enum", TokenKind.ENUM,
                    "datasource", TokenKind.DATASOURCE,
                    "generator", TokenKind.GENERATOR,
                    "true", TokenKind.TRUE,
                    "false", TokenKind.FALSE);

    private final SourceText source;
    private final List<Token> tokens = new ArrayList<>();
    private final List<SchemaDiagnostic> diagnostics = new ArrayList<>();
    private int offset;

    private Lexer(SourceText source) {
        this.source = source;
    }

    static Result lex(SourceText source) {
        Lexer lexer = new Lexer(source);
        lexer.scan();
        return new Result(lexer.tokens, lexer.diagnostics);
    }

    private void scan() {
        while (!isAtEnd()) {
            int start = offset;
            int codePoint = source.text().codePointAt(offset);

            if (Character.isWhitespace(codePoint)) {
                offset += Character.charCount(codePoint);
                continue;
            }
            if (isIdentifierStart(codePoint)) {
                scanIdentifier(start);
                continue;
            }
            if (Character.isDigit(codePoint)
                    || (codePoint == '-' && hasNextDigit())) {
                scanInteger(start);
                continue;
            }

            switch (codePoint) {
                case '"' -> scanString(start);
                case '{' -> single(TokenKind.LEFT_BRACE, start);
                case '}' -> single(TokenKind.RIGHT_BRACE, start);
                case '(' -> single(TokenKind.LEFT_PAREN, start);
                case ')' -> single(TokenKind.RIGHT_PAREN, start);
                case '[' -> single(TokenKind.LEFT_BRACKET, start);
                case ']' -> single(TokenKind.RIGHT_BRACKET, start);
                case '?' -> single(TokenKind.QUESTION, start);
                case ',' -> single(TokenKind.COMMA, start);
                case ':' -> single(TokenKind.COLON, start);
                case '=' -> single(TokenKind.EQUALS, start);
                case '@' -> scanAt(start);
                case '/' -> scanSlash(start);
                default -> unexpectedCharacter(start, codePoint);
            }
        }
        SourceSpan end = source.span(source.length(), source.length());
        tokens.add(new Token(TokenKind.EOF, "", "", end));
    }

    private void scanIdentifier(int start) {
        offset += Character.charCount(source.text().codePointAt(offset));
        while (!isAtEnd()) {
            int codePoint = source.text().codePointAt(offset);
            if (!isIdentifierPart(codePoint)) {
                break;
            }
            offset += Character.charCount(codePoint);
        }
        String text = source.text().substring(start, offset);
        TokenKind kind = KEYWORDS.getOrDefault(text, TokenKind.IDENTIFIER);
        tokens.add(new Token(kind, text, text, source.span(start, offset)));
    }

    private void scanInteger(int start) {
        if (source.text().charAt(offset) == '-') {
            offset++;
        }
        while (!isAtEnd() && Character.isDigit(source.text().codePointAt(offset))) {
            offset += Character.charCount(source.text().codePointAt(offset));
        }
        String text = source.text().substring(start, offset);
        tokens.add(new Token(TokenKind.INTEGER, text, text, source.span(start, offset)));
    }

    private void scanString(int start) {
        offset++;
        StringBuilder decoded = new StringBuilder();
        boolean terminated = false;

        while (!isAtEnd()) {
            char current = source.text().charAt(offset);
            if (current == '"') {
                offset++;
                terminated = true;
                break;
            }
            if (current == '\r' || current == '\n') {
                break;
            }
            if (current != '\\') {
                int codePoint = source.text().codePointAt(offset);
                decoded.appendCodePoint(codePoint);
                offset += Character.charCount(codePoint);
                continue;
            }

            int escapeStart = offset;
            offset++;
            if (isAtEnd()) {
                break;
            }
            char escaped = source.text().charAt(offset++);
            switch (escaped) {
                case '\\' -> decoded.append('\\');
                case '"' -> decoded.append('"');
                case 'n' -> decoded.append('\n');
                case 'r' -> decoded.append('\r');
                case 't' -> decoded.append('\t');
                case 'u' -> decodeUnicodeEscape(escapeStart, decoded);
                default -> {
                    diagnostics.add(
                            error(
                                    "TUPREL-SCHEMA-LEX-003",
                                    "Invalid string escape '\\%s'.".formatted(escaped),
                                    escapeStart,
                                    offset));
                    decoded.append(escaped);
                }
            }
        }

        if (!terminated) {
            diagnostics.add(
                    error(
                            "TUPREL-SCHEMA-LEX-002",
                            "Unterminated string literal.",
                            start,
                            offset));
        }
        tokens.add(
                new Token(
                        TokenKind.STRING,
                        source.text().substring(start, offset),
                        decoded.toString(),
                        source.span(start, offset)));
    }

    private void decodeUnicodeEscape(int escapeStart, StringBuilder decoded) {
        if (offset + 4 > source.length()) {
            diagnostics.add(
                    error(
                            "TUPREL-SCHEMA-LEX-003",
                            "Unicode escape must contain four hexadecimal digits.",
                            escapeStart,
                            offset));
            return;
        }

        String digits = source.text().substring(offset, offset + 4);
        for (int index = 0; index < digits.length(); index++) {
            if (Character.digit(digits.charAt(index), 16) < 0) {
                diagnostics.add(
                        error(
                                "TUPREL-SCHEMA-LEX-003",
                                "Unicode escape must contain four hexadecimal digits.",
                                escapeStart,
                                offset + 4));
                offset += 4;
                return;
            }
        }
        decoded.append((char) Integer.parseInt(digits, 16));
        offset += 4;
    }

    private void scanAt(int start) {
        offset++;
        if (!isAtEnd() && source.text().charAt(offset) == '@') {
            offset++;
            add(TokenKind.AT_AT, start, offset, "@@");
        } else {
            add(TokenKind.AT, start, offset, "@");
        }
    }

    private void scanSlash(int start) {
        if (offset + 1 >= source.length()) {
            unexpectedCharacter(start, '/');
            return;
        }
        char next = source.text().charAt(offset + 1);
        if (next == '/') {
            offset += 2;
            while (!isAtEnd()) {
                char current = source.text().charAt(offset);
                if (current == '\r' || current == '\n') {
                    break;
                }
                offset++;
            }
            add(TokenKind.COMMENT, start, offset, source.text().substring(start, offset));
            return;
        }
        if (next == '*') {
            offset += 2;
            boolean terminated = false;
            while (offset + 1 < source.length()) {
                if (source.text().charAt(offset) == '*'
                        && source.text().charAt(offset + 1) == '/') {
                    offset += 2;
                    terminated = true;
                    break;
                }
                int codePoint = source.text().codePointAt(offset);
                offset += Character.charCount(codePoint);
            }
            if (!terminated) {
                offset = source.length();
                diagnostics.add(
                        error(
                                "TUPREL-SCHEMA-LEX-004",
                                "Unterminated block comment.",
                                start,
                                offset));
            }
            add(TokenKind.COMMENT, start, offset, source.text().substring(start, offset));
            return;
        }
        unexpectedCharacter(start, '/');
    }

    private void single(TokenKind kind, int start) {
        int codePoint = source.text().codePointAt(offset);
        offset += Character.charCount(codePoint);
        add(kind, start, offset, source.text().substring(start, offset));
    }

    private void unexpectedCharacter(int start, int codePoint) {
        offset += Character.charCount(codePoint);
        diagnostics.add(
                error(
                        "TUPREL-SCHEMA-LEX-001",
                        "Unexpected character '%s'.".formatted(new String(Character.toChars(codePoint))),
                        start,
                        offset));
    }

    private void add(TokenKind kind, int start, int end, String value) {
        tokens.add(
                new Token(
                        kind,
                        source.text().substring(start, end),
                        value,
                        source.span(start, end)));
    }

    private SchemaDiagnostic error(String code, String message, int start, int end) {
        return new SchemaDiagnostic(
                code, DiagnosticSeverity.ERROR, message, source.span(start, end));
    }

    private boolean hasNextDigit() {
        return offset + 1 < source.length()
                && Character.isDigit(source.text().codePointAt(offset + 1));
    }

    private boolean isAtEnd() {
        return offset >= source.length();
    }

    private static boolean isIdentifierStart(int codePoint) {
        return codePoint == '_'
                || (codePoint >= 'A' && codePoint <= 'Z')
                || (codePoint >= 'a' && codePoint <= 'z');
    }

    private static boolean isIdentifierPart(int codePoint) {
        return isIdentifierStart(codePoint) || (codePoint >= '0' && codePoint <= '9');
    }
}
