package dev.tuprel.schema;

import dev.tuprel.schema.Lexer.Token;
import dev.tuprel.schema.Lexer.TokenKind;
import dev.tuprel.schema.ast.SchemaDocument.Cardinality;
import dev.tuprel.schema.ast.SchemaDocument.Declaration;
import dev.tuprel.schema.ast.SchemaDocument.EnumDeclaration;
import dev.tuprel.schema.ast.SchemaDocument.EnumValue;
import dev.tuprel.schema.ast.SchemaDocument.FieldDeclaration;
import dev.tuprel.schema.ast.SchemaDocument.ModelDeclaration;
import dev.tuprel.schema.model.ValidatedSchema.ResolvedType;
import dev.tuprel.schema.model.ValidatedSchema.TypeKind;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;

/** Resolves symbols in one immutable, in-memory Tuprel schema. */
public final class SchemaSymbols {
    /** A symbol supported by the schema query. */
    public sealed interface Symbol permits Model, Enumeration, Field, Scalar {
        /** Returns the schema identifier. */
        String name();
    }

    /** A model and its unambiguous fields with known types, in source order. */
    public record Model(String name, List<Field> fields) implements Symbol {
        /** Defensively copies the fields. */
        public Model {
            fields = List.copyOf(fields);
        }
    }

    /** An enum and its unambiguous values, in source order. */
    public record Enumeration(String name, List<String> values) implements Symbol {
        /** Defensively copies the values. */
        public Enumeration {
            values = List.copyOf(values);
        }
    }

    /** A field declaration, its owning model, named type and cardinality. */
    public record Field(String name, String model, ResolvedType type, Cardinality cardinality) implements Symbol {}

    /** An official built-in scalar type. */
    public record Scalar(String name) implements Symbol {}

    /** The symbol, its exact identifier occurrence, and the target for a local type reference. */
    public record Match(Symbol symbol, SourceSpan occurrence, Optional<SourceSpan> definition) {
        /** Validates match components. */
        public Match {
            Objects.requireNonNull(symbol, "symbol");
            Objects.requireNonNull(occurrence, "occurrence");
            Objects.requireNonNull(definition, "definition");
        }
    }

    /**
     * Finds a supported identifier at a UTF-16 offset within its half-open span. A locally
     * resolvable symbol can be returned from an incomplete schema. A duplicate, unknown,
     * unsupported, or recovery-dependent occurrence yields an empty result. A local model or
     * enum type reference provides a definition span in the same source.
     */
    public Optional<Match> lookup(SourceText source, int offset) {
        Objects.requireNonNull(source, "source");
        if (offset < 0 || offset >= source.length()) {
            return Optional.empty();
        }
        Lexer.Result lexed = Lexer.lex(source);
        if (lexed.tokens().stream().noneMatch(token ->
                token.kind() == TokenKind.IDENTIFIER && contains(token.span(), offset))) {
            return Optional.empty();
        }
        List<Declaration> declarations = Parser.parse(source, lexed.tokens()).document().declarations();
        return new Query(lexed, declarations).lookup(offset);
    }

    private record TypeDeclaration(Declaration syntax, SourceSpan name, boolean hasBody) {
        TypeKind kind() {
            return syntax instanceof ModelDeclaration ? TypeKind.MODEL : TypeKind.ENUM;
        }
    }

    private record Member(FieldDeclaration syntax, SourceSpan name, SourceSpan type) {}

    private static final class Query {
        private final TokenLayout layout;
        private final Map<String, List<TypeDeclaration>> types = new LinkedHashMap<>();

        Query(Lexer.Result lexed, List<Declaration> declarations) {
            layout = new TokenLayout(lexed);
            for (Declaration declaration : declarations) {
                if (declaration instanceof ModelDeclaration model) {
                    addType(model, model.name(), TokenKind.MODEL);
                } else if (declaration instanceof EnumDeclaration enumeration) {
                    addType(enumeration, enumeration.name(), TokenKind.ENUM);
                }
            }
        }

        private void addType(Declaration declaration, String name, TokenKind kind) {
            int start = layout.indexAt(declaration.span().startOffset());
            if (!layout.isAtDepth(start, 0) || layout.token(start).kind() != kind
                    || !layout.isIdentifier(start + 1, name)) {
                return;
            }
            SourceSpan identifier = layout.token(start + 1).span();
            Token body = layout.token(start + 2);
            boolean hasBody = body.kind() == TokenKind.LEFT_BRACE
                    && layout.isClean(declaration.span().startOffset(), body.span().endOffset());
            types.computeIfAbsent(name, ignored -> new ArrayList<>())
                    .add(new TypeDeclaration(declaration, identifier, hasBody));
        }

        Optional<Match> lookup(int offset) {
            for (List<TypeDeclaration> group : types.values()) {
                if (group.size() != 1 || !usable(group.get(0))) {
                    continue;
                }
                TypeDeclaration declaration = group.get(0);
                if (contains(declaration.name(), offset)) {
                    return Optional.of(new Match(describe(declaration), declaration.name(), Optional.empty()));
                }
                if (declaration.syntax() instanceof ModelDeclaration model && contains(model.span(), offset)) {
                    Optional<Match> field = lookupField(model, offset);
                    if (field.isPresent()) {
                        return field;
                    }
                }
            }
            return Optional.empty();
        }

        private Optional<Match> lookupField(ModelDeclaration model, int offset) {
            Map<String, List<Member>> members = members(model);
            for (List<Member> group : members.values()) {
                if (group.size() != 1) {
                    continue;
                }
                Member member = group.get(0);
                if (contains(member.name(), offset)) {
                    return field(model.name(), member).map(field -> new Match(field, member.name(), Optional.empty()));
                }
                if (member.type() != null && contains(member.type(), offset)) {
                    return resolve(member.syntax().type().name()).map(type -> {
                        if (type.kind() == TypeKind.SCALAR) {
                            return new Match(new Scalar(type.name()), member.type(), Optional.empty());
                        }
                        TypeDeclaration target = types.get(type.name()).get(0);
                        return new Match(describe(target), member.type(), Optional.of(target.name()));
                    });
                }
            }
            return Optional.empty();
        }

        private Symbol describe(TypeDeclaration declaration) {
            if (declaration.syntax() instanceof ModelDeclaration model) {
                List<Field> fields = members(model).values().stream()
                        .filter(group -> group.size() == 1)
                        .map(group -> field(model.name(), group.get(0)))
                        .flatMap(Optional::stream)
                        .toList();
                return new Model(model.name(), fields);
            }
            if (declaration.syntax() instanceof EnumDeclaration enumeration) {
                Map<String, Integer> values = new LinkedHashMap<>();
                for (EnumValue value : enumeration.values()) {
                    int index = layout.indexAt(value.span().startOffset());
                    if (layout.isAtDepth(index, 1) && layout.isIdentifier(index, value.name())
                            && SemanticValidator.isAllowedName(value.name())) {
                        values.merge(value.name(), 1, Integer::sum);
                    }
                }
                return new Enumeration(enumeration.name(), values.entrySet().stream()
                        .filter(entry -> entry.getValue() == 1)
                        .map(Map.Entry::getKey)
                        .toList());
            }
            throw new IllegalStateException("Expected schema type declaration");
        }

        private Map<String, List<Member>> members(ModelDeclaration model) {
            Map<String, List<Member>> members = new LinkedHashMap<>();
            for (FieldDeclaration field : model.fields()) {
                int index = layout.indexAt(field.span().startOffset());
                if (layout.isAtDepth(index, 1) && layout.isIdentifier(index, field.name())
                        && SemanticValidator.isAllowedName(field.name())) {
                    SourceSpan name = layout.token(index).span();
                    SourceSpan type = typeIdentifier(field, index, name);
                    members.computeIfAbsent(field.name(), ignored -> new ArrayList<>())
                            .add(new Member(field, name, type));
                }
            }
            return members;
        }

        private SourceSpan typeIdentifier(FieldDeclaration field, int index, SourceSpan name) {
            Token token = layout.token(index + 1);
            SourceSpan type = field.type().span();
            if (!layout.isIdentifier(index + 1, field.type().name())
                    || token.span().startOffset() != type.startOffset()
                    || !layout.isClean(name.startOffset(), type.endOffset())) {
                return null;
            }
            if (field.type().cardinality() == Cardinality.LIST) {
                Token close = layout.token(index + 3);
                if (close.kind() != TokenKind.RIGHT_BRACKET || close.span().endOffset() != type.endOffset()) {
                    return null;
                }
            }
            return token.span();
        }

        private Optional<Field> field(String model, Member member) {
            FieldDeclaration field = member.syntax();
            return member.type() == null ? Optional.empty() : resolve(field.type().name())
                    .map(type -> new Field(field.name(), model, type, field.type().cardinality()));
        }

        private Optional<ResolvedType> resolve(String name) {
            List<TypeDeclaration> group = types.getOrDefault(name, List.of());
            if (group.size() > 1) {
                return Optional.empty();
            }
            return SemanticValidator.resolve(name, ignored ->
                    group.isEmpty() || !usable(group.get(0)) ? null : group.get(0).kind());
        }

        private static boolean usable(TypeDeclaration declaration) {
            return declaration.hasBody() && SemanticValidator.isAllowedName(declaration.name().text());
        }
    }

    // Parser recovery can emit members or declarations inside an unfinished expression or block.
    // Use delimiter depth only to check AST anchors, while the parser owns their grammar roles.
    private static final class TokenLayout {
        private record Position(Token token, int braces, boolean inExpression) {}

        private final List<Position> tokens = new ArrayList<>();
        private final Map<Integer, Integer> indices = new HashMap<>();
        private final NavigableSet<Integer> lexicalErrors = new TreeSet<>();

        TokenLayout(Lexer.Result lexed) {
            int braces = 0;
            int parentheses = 0;
            int brackets = 0;
            for (Token token : lexed.tokens()) {
                if (token.kind() == TokenKind.COMMENT) {
                    continue;
                }
                indices.put(token.span().startOffset(), tokens.size());
                tokens.add(new Position(token, braces, parentheses > 0 || brackets > 0));
                switch (token.kind()) {
                    case LEFT_BRACE, RIGHT_BRACE -> {
                        braces = Math.max(0, braces + (token.kind() == TokenKind.LEFT_BRACE ? 1 : -1));
                        parentheses = 0;
                        brackets = 0;
                    }
                    case LEFT_PAREN -> parentheses++;
                    case RIGHT_PAREN -> parentheses = Math.max(0, parentheses - 1);
                    case LEFT_BRACKET -> brackets++;
                    case RIGHT_BRACKET -> brackets = Math.max(0, brackets - 1);
                    default -> { }
                }
            }
            for (SchemaDiagnostic diagnostic : lexed.diagnostics()) {
                lexicalErrors.add(diagnostic.span().startOffset());
            }
        }

        int indexAt(int offset) {
            return indices.getOrDefault(offset, -1);
        }

        Token token(int index) {
            return tokens.get(Math.min(index, tokens.size() - 1)).token();
        }

        boolean isAtDepth(int index, int depth) {
            return index >= 0 && tokens.get(index).braces() == depth && !tokens.get(index).inExpression();
        }

        boolean isIdentifier(int index, String name) {
            Token token = token(index);
            return token.kind() == TokenKind.IDENTIFIER && token.value().equals(name);
        }

        boolean isClean(int start, int end) {
            Integer error = lexicalErrors.ceiling(start);
            return error == null || error >= end;
        }
    }

    private static boolean contains(SourceSpan span, int offset) {
        return span.startOffset() <= offset && offset < span.endOffset();
    }
}
