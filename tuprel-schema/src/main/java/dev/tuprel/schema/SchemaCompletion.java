package dev.tuprel.schema;

import dev.tuprel.schema.Lexer.Token;
import dev.tuprel.schema.Lexer.TokenKind;
import dev.tuprel.schema.ast.SchemaDocument;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/** Contextual completion for one in-memory Tuprel schema. */
public final class SchemaCompletion {
    /** The language role of a completion label. */
    public enum Kind {
        /** A declaration keyword. */
        KEYWORD,
        /** A built-in scalar type. */
        SCALAR,
        /** A declared model type. */
        MODEL,
        /** A declared enum type. */
        ENUM,
        /** A field or model attribute. */
        ATTRIBUTE
    }

    /** Text to substitute for the current prefix and its language role. */
    public record Item(String label, Kind kind) {
        /** Validates the item components. */
        public Item {
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(kind, "kind");
        }
    }

    /** Shared replacement range and unique suggestions sorted by label. */
    public record Result(SourceSpan replacement, List<Item> items) {
        /** Validates the range and defensively copies the items. */
        public Result {
            Objects.requireNonNull(replacement, "replacement");
            items = List.copyOf(items);
        }
    }

    private record Cursor(int tokenCount, int start, int end) {}

    /**
     * Queries the official grammar at a UTF-16 offset. A syntactically or semantically
     * incomplete source can still yield suggestions. Returns empty for an unsupported or
     * ambiguous context, an invalid offset, or when no label matches the typed prefix.
     */
    public Optional<Result> complete(SourceText source, int offset) {
        Objects.requireNonNull(source, "source");
        if (offset < 0 || offset > source.length()
                || (offset > 0 && offset < source.length()
                        && Character.isHighSurrogate(source.text().charAt(offset - 1))
                        && Character.isLowSurrogate(source.text().charAt(offset)))) {
            return Optional.empty();
        }
        Lexer.Result lexed = Lexer.lex(source);
        Cursor cursor = locate(lexed, offset);
        if (cursor == null) {
            return Optional.empty();
        }

        List<Token> prefix = new ArrayList<>(lexed.tokens().subList(0, cursor.tokenCount()));
        int previousEnd = prefix.isEmpty() ? 0 : prefix.getLast().span().endOffset();
        if (lexed.diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.code().equals("TUPREL-SCHEMA-LEX-001")
                        && diagnostic.span().startOffset() >= previousEnd
                        && diagnostic.span().endOffset() <= offset)) {
            return Optional.empty();
        }
        Optional<Token> previous = prefix.reversed().stream()
                .filter(token -> token.kind() != TokenKind.COMMENT)
                .findFirst();
        if (previous.filter(token -> isMarker(token.kind()) && token.span().startOffset() < cursor.start())
                .isPresent()) {
            // A detached identifier must not cause a second marker to be inserted.
            return Optional.empty();
        }
        prefix.add(new Token(TokenKind.EOF, "", "", source.span(offset, offset)));
        Parser.CompletionContext context = Parser.completionContext(source, prefix);
        if (context == null || !matchesBraceDepth(prefix, context)) {
            return Optional.empty();
        }

        String typed = source.text().substring(cursor.start(), offset);
        if ((context == Parser.CompletionContext.FIELD_ATTRIBUTE || context == Parser.CompletionContext.MODEL_ATTRIBUTE)
                && cursor.start() != cursor.end() && !typed.startsWith("@")) {
            return Optional.empty();
        }
        Map<String, Kind> candidates = new TreeMap<>();
        switch (context) {
            case DECLARATION -> Lexer.declarationKeywords().forEach(name -> candidates.put(name, Kind.KEYWORD));
            case FIELD_TYPE -> addTypes(source, lexed.tokens(), candidates);
            case FIELD_ATTRIBUTE -> SemanticValidator.FIELD_ATTRIBUTES.forEach(
                    name -> candidates.put("@" + name, Kind.ATTRIBUTE));
            case MODEL_ATTRIBUTE -> SemanticValidator.MODEL_ATTRIBUTES.forEach(
                    name -> candidates.put("@@" + name, Kind.ATTRIBUTE));
        }
        List<Item> matches = candidates.entrySet().stream()
                .filter(candidate -> candidate.getKey().startsWith(typed))
                .map(candidate -> new Item(candidate.getKey(), candidate.getValue()))
                .toList();
        return matches.isEmpty() ? Optional.empty()
                : Optional.of(new Result(source.span(cursor.start(), cursor.end()), matches));
    }

    private static Cursor locate(Lexer.Result lexed, int offset) {
        List<Token> tokens = lexed.tokens();
        for (int index = 0; index < tokens.size(); index++) {
            Token token = tokens.get(index);
            int start = token.span().startOffset();
            int end = token.span().endOffset();
            if (token.kind() == TokenKind.EOF || offset < start) {
                return new Cursor(index, offset, offset);
            }
            if (offset > end) {
                continue;
            }
            switch (token.kind()) {
                case COMMENT, STRING -> {
                    if (offset < end || token.text().startsWith("//")
                            || lexed.diagnostics().stream().anyMatch(diagnostic ->
                                    (diagnostic.code().equals("TUPREL-SCHEMA-LEX-002")
                                            || diagnostic.code().equals("TUPREL-SCHEMA-LEX-004"))
                                            && diagnostic.span().startOffset() == start)) {
                        return null;
                    }
                }
                case IDENTIFIER, MODEL, ENUM, DATASOURCE, GENERATOR, TRUE, FALSE -> {
                    int replacementStart = start;
                    if (index > 0 && isMarker(tokens.get(index - 1).kind())
                            && tokens.get(index - 1).span().endOffset() == start) {
                        replacementStart = tokens.get(index - 1).span().startOffset();
                    }
                    return new Cursor(index, replacementStart, end);
                }
                case AT, AT_AT -> {
                    if (offset < end) {
                        return null;
                    }
                    Token next = tokens.get(index + 1);
                    int replacementEnd = isWord(next.kind()) && next.span().startOffset() == end
                            ? next.span().endOffset() : end;
                    return new Cursor(index + 1, start, replacementEnd);
                }
                default -> {
                    if (offset < end) {
                        return offset == start ? new Cursor(index, offset, offset) : null;
                    }
                }
            }
        }
        throw new IllegalStateException("Missing end-of-source token");
    }

    private static boolean isWord(TokenKind kind) {
        return switch (kind) {
            case IDENTIFIER, MODEL, ENUM, DATASOURCE, GENERATOR, TRUE, FALSE -> true;
            default -> false;
        };
    }

    private static boolean isMarker(TokenKind kind) {
        return kind == TokenKind.AT || kind == TokenKind.AT_AT;
    }

    private static boolean matchesBraceDepth(List<Token> prefix, Parser.CompletionContext context) {
        int depth = 0;
        for (Token token : prefix) {
            depth += braceDelta(token);
            if (depth < 0) {
                return false;
            }
        }
        return depth == (context == Parser.CompletionContext.DECLARATION ? 0 : 1);
    }

    private static int braceDelta(Token token) {
        return switch (token.kind()) {
            case LEFT_BRACE -> 1;
            case RIGHT_BRACE -> -1;
            default -> 0;
        };
    }

    private static void addTypes(SourceText source, List<Token> tokens, Map<String, Kind> candidates) {
        SemanticValidator.SCALARS.forEach(name -> candidates.put(name, Kind.SCALAR));
        // The parser can resynchronize on a keyword inside a malformed block.
        Set<Integer> topLevelStarts = new HashSet<>();
        int depth = 0;
        for (Token token : tokens) {
            if (depth == 0 && (token.kind() == TokenKind.MODEL || token.kind() == TokenKind.ENUM)) {
                topLevelStarts.add(token.span().startOffset());
            }
            depth = Math.max(0, depth + braceDelta(token));
        }
        for (SchemaDocument.Declaration declaration : Parser.parse(source, tokens).document().declarations()) {
            if (!topLevelStarts.contains(declaration.span().startOffset())) {
                continue;
            }
            switch (declaration) {
                case SchemaDocument.ModelDeclaration model -> addDeclaredType(candidates, model.name(), Kind.MODEL);
                case SchemaDocument.EnumDeclaration enumeration -> addDeclaredType(
                        candidates, enumeration.name(), Kind.ENUM);
                default -> { }
            }
        }
    }

    private static void addDeclaredType(Map<String, Kind> candidates, String name, Kind kind) {
        if (!name.equals("<missing>")) {
            candidates.putIfAbsent(name, kind);
        }
    }
}
