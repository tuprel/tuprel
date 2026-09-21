package dev.tuprel.schema;

import dev.tuprel.schema.ast.SchemaDocument;
import dev.tuprel.schema.ast.SchemaDocument.Argument;
import dev.tuprel.schema.ast.SchemaDocument.BlockAttribute;
import dev.tuprel.schema.ast.SchemaDocument.ConfigProperty;
import dev.tuprel.schema.ast.SchemaDocument.Declaration;
import dev.tuprel.schema.ast.SchemaDocument.Expression;
import dev.tuprel.schema.ast.SchemaDocument.FieldAttribute;
import dev.tuprel.schema.ast.SchemaDocument.FieldDeclaration;
import dev.tuprel.schema.Lexer.Token;
import dev.tuprel.schema.Lexer.TokenKind;
import java.util.ArrayList;
import java.util.List;

final class Parser {
    record Result(SchemaDocument document, List<SchemaDiagnostic> diagnostics) {
        Result {
            diagnostics = List.copyOf(diagnostics);
        }
    }

    private final SourceText source;
    private final List<Token> tokens;
    private final List<SchemaDiagnostic> diagnostics = new ArrayList<>();
    private int current;
    private Token previous;

    private Parser(SourceText source, List<Token> tokens) {
        this.source = source;
        this.tokens =
                tokens.stream().filter(token -> token.kind() != TokenKind.COMMENT).toList();
        this.previous = this.tokens.getFirst();
    }

    static Result parse(SourceText source, List<Token> tokens) {
        Parser parser = new Parser(source, tokens);
        return parser.parseDocument();
    }

    private Result parseDocument() {
        List<Declaration> declarations = new ArrayList<>();
        while (!check(TokenKind.EOF)) {
            int before = current;
            Declaration declaration = parseDeclaration();
            if (declaration != null) {
                declarations.add(declaration);
            }
            if (current == before) {
                advance();
            }
        }
        return new Result(
                new SchemaDocument(declarations, source.span(0, source.length())), diagnostics);
    }

    private Declaration parseDeclaration() {
        if (match(TokenKind.MODEL)) {
            return parseModel(previous);
        }
        if (match(TokenKind.ENUM)) {
            return parseEnum(previous);
        }
        if (match(TokenKind.DATASOURCE)) {
            return parseConfiguration(previous, true);
        }
        if (match(TokenKind.GENERATOR)) {
            return parseConfiguration(previous, false);
        }

        diagnostics.add(
                error(
                        "TUPREL-SCHEMA-PARSE-001",
                        "Expected a datasource, generator, model, or enum declaration.",
                        peek().span()));
        synchronizeTopLevel();
        return null;
    }

    private Declaration parseConfiguration(Token keyword, boolean datasource) {
        Token name = expectIdentifier("Expected a configuration name after '%s'.".formatted(keyword.text()));
        expect(TokenKind.LEFT_BRACE, "TUPREL-SCHEMA-PARSE-003", "Expected '{' after configuration name.");
        List<ConfigProperty> properties = new ArrayList<>();
        while (!check(TokenKind.RIGHT_BRACE) && !check(TokenKind.EOF)) {
            if (!check(TokenKind.IDENTIFIER)) {
                diagnostics.add(
                        error(
                                "TUPREL-SCHEMA-PARSE-011",
                                "Expected a configuration property.",
                                peek().span()));
                advance();
                continue;
            }
            Token propertyName = advance();
            expect(TokenKind.EQUALS, "TUPREL-SCHEMA-PARSE-006", "Expected '=' after property name.");
            Expression value = parseExpression();
            properties.add(
                    new ConfigProperty(
                            propertyName.value(),
                            value,
                            span(propertyName.span(), value.span())));
        }
        Token close = expect(TokenKind.RIGHT_BRACE, "TUPREL-SCHEMA-PARSE-004", "Expected '}' after configuration.");
        SourceSpan declarationSpan = span(keyword.span(), close.span());
        if (datasource) {
            return new SchemaDocument.DatasourceDeclaration(
                    name.value(), properties, declarationSpan);
        }
        return new SchemaDocument.GeneratorDeclaration(name.value(), properties, declarationSpan);
    }

    private SchemaDocument.ModelDeclaration parseModel(Token keyword) {
        Token name = expectIdentifier("Expected model name after 'model'.");
        expect(TokenKind.LEFT_BRACE, "TUPREL-SCHEMA-PARSE-003", "Expected '{' after model name.");
        List<FieldDeclaration> fields = new ArrayList<>();
        List<BlockAttribute> attributes = new ArrayList<>();

        while (!check(TokenKind.RIGHT_BRACE) && !check(TokenKind.EOF)) {
            if (match(TokenKind.AT_AT)) {
                attributes.add(parseBlockAttribute(previous));
            } else if (check(TokenKind.IDENTIFIER)) {
                fields.add(parseField());
            } else {
                diagnostics.add(
                        error(
                                "TUPREL-SCHEMA-PARSE-011",
                                "Expected a field or model attribute.",
                                peek().span()));
                advance();
            }
        }

        Token close = expect(TokenKind.RIGHT_BRACE, "TUPREL-SCHEMA-PARSE-004", "Expected '}' after model.");
        return new SchemaDocument.ModelDeclaration(
                name.value(), fields, attributes, span(keyword.span(), close.span()));
    }

    private FieldDeclaration parseField() {
        Token name = advance();
        Token typeName = expectIdentifier("Expected a type after field name '%s'.".formatted(name.value()));
        SchemaDocument.Cardinality cardinality = SchemaDocument.Cardinality.REQUIRED;
        SourceSpan typeSpan = typeName.span();
        if (match(TokenKind.QUESTION)) {
            cardinality = SchemaDocument.Cardinality.OPTIONAL;
            typeSpan = span(typeName.span(), previous.span());
        } else if (match(TokenKind.LEFT_BRACKET)) {
            Token close = expect(TokenKind.RIGHT_BRACKET, "TUPREL-SCHEMA-PARSE-009", "Expected ']' in list type.");
            cardinality = SchemaDocument.Cardinality.LIST;
            typeSpan = span(typeName.span(), close.span());
        }

        SchemaDocument.TypeReference type =
                new SchemaDocument.TypeReference(typeName.value(), cardinality, typeSpan);
        List<FieldAttribute> attributes = new ArrayList<>();
        while (match(TokenKind.AT)) {
            attributes.add(parseFieldAttribute(previous));
        }
        SourceSpan end = attributes.isEmpty() ? type.span() : attributes.getLast().span();
        return new FieldDeclaration(name.value(), type, attributes, span(name.span(), end));
    }

    private FieldAttribute parseFieldAttribute(Token at) {
        Token name = expectIdentifier("Expected attribute name after '@'.");
        List<Argument> arguments = check(TokenKind.LEFT_PAREN) ? parseArguments() : List.of();
        SourceSpan end = argumentsEnd(name.span());
        return new FieldAttribute(name.value(), arguments, span(at.span(), end));
    }

    private BlockAttribute parseBlockAttribute(Token atAt) {
        Token name = expectIdentifier("Expected model attribute name after '@@'.");
        List<Argument> arguments;
        if (check(TokenKind.LEFT_PAREN)) {
            arguments = parseArguments();
        } else {
            diagnostics.add(
                    error(
                            "TUPREL-SCHEMA-PARSE-008",
                            "Expected '(' after model attribute name.",
                            peek().span()));
            arguments = List.of();
        }
        SourceSpan end = argumentsEnd(name.span());
        return new BlockAttribute(name.value(), arguments, span(atAt.span(), end));
    }

    private SchemaDocument.EnumDeclaration parseEnum(Token keyword) {
        Token name = expectIdentifier("Expected enum name after 'enum'.");
        expect(TokenKind.LEFT_BRACE, "TUPREL-SCHEMA-PARSE-003", "Expected '{' after enum name.");
        List<SchemaDocument.EnumValue> values = new ArrayList<>();
        while (!check(TokenKind.RIGHT_BRACE) && !check(TokenKind.EOF)) {
            if (check(TokenKind.IDENTIFIER)) {
                Token value = advance();
                values.add(new SchemaDocument.EnumValue(value.value(), value.span()));
            } else {
                diagnostics.add(
                        error(
                                "TUPREL-SCHEMA-PARSE-011",
                                "Expected an enum value.",
                                peek().span()));
                advance();
            }
        }
        Token close = expect(TokenKind.RIGHT_BRACE, "TUPREL-SCHEMA-PARSE-004", "Expected '}' after enum.");
        return new SchemaDocument.EnumDeclaration(
                name.value(), values, span(keyword.span(), close.span()));
    }

    private List<Argument> parseArguments() {
        expect(TokenKind.LEFT_PAREN, "TUPREL-SCHEMA-PARSE-008", "Expected '('.");
        List<Argument> arguments = new ArrayList<>();
        if (!check(TokenKind.RIGHT_PAREN)) {
            do {
                Token start = peek();
                String name = null;
                if (check(TokenKind.IDENTIFIER) && checkNext(TokenKind.COLON)) {
                    name = advance().value();
                    advance();
                }
                Expression value = parseExpression();
                arguments.add(new Argument(name, value, span(start.span(), value.span())));
            } while (match(TokenKind.COMMA));
        }
        expect(TokenKind.RIGHT_PAREN, "TUPREL-SCHEMA-PARSE-008", "Expected ')' after arguments.");
        return arguments;
    }

    private Expression parseExpression() {
        if (match(TokenKind.STRING)) {
            return new SchemaDocument.StringLiteral(previous.value(), previous.span());
        }
        if (match(TokenKind.INTEGER)) {
            return new SchemaDocument.IntegerLiteral(previous.value(), previous.span());
        }
        if (match(TokenKind.TRUE)) {
            return new SchemaDocument.BooleanLiteral(true, previous.span());
        }
        if (match(TokenKind.FALSE)) {
            return new SchemaDocument.BooleanLiteral(false, previous.span());
        }
        if (match(TokenKind.LEFT_BRACKET)) {
            Token open = previous;
            List<Expression> values = new ArrayList<>();
            if (!check(TokenKind.RIGHT_BRACKET)) {
                do {
                    values.add(parseExpression());
                } while (match(TokenKind.COMMA));
            }
            Token close = expect(TokenKind.RIGHT_BRACKET, "TUPREL-SCHEMA-PARSE-009", "Expected ']' after list.");
            return new SchemaDocument.ListValue(values, span(open.span(), close.span()));
        }
        if (match(TokenKind.IDENTIFIER)) {
            Token identifier = previous;
            if (check(TokenKind.LEFT_PAREN)) {
                List<Argument> arguments = parseArguments();
                return new SchemaDocument.Call(
                        identifier.value(), arguments, span(identifier.span(), previous.span()));
            }
            return new SchemaDocument.Symbol(identifier.value(), identifier.span());
        }

        Token unexpected = peek();
        diagnostics.add(
                error(
                        "TUPREL-SCHEMA-PARSE-007",
                        "Expected a schema expression.",
                        unexpected.span()));
        if (!check(TokenKind.EOF)) {
            advance();
        }
        return new SchemaDocument.Symbol("<error>", unexpected.span());
    }

    private SourceSpan argumentsEnd(SourceSpan fallback) {
        return previous.kind() == TokenKind.RIGHT_PAREN ? previous.span() : fallback;
    }

    private Token expectIdentifier(String message) {
        return expect(TokenKind.IDENTIFIER, "TUPREL-SCHEMA-PARSE-002", message);
    }

    private Token expect(TokenKind kind, String code, String message) {
        if (check(kind)) {
            return advance();
        }
        diagnostics.add(error(code, message, peek().span()));
        return new Token(kind, "", "<missing>", peek().span());
    }

    private void synchronizeTopLevel() {
        while (!check(TokenKind.EOF)) {
            if (check(TokenKind.MODEL)
                    || check(TokenKind.ENUM)
                    || check(TokenKind.DATASOURCE)
                    || check(TokenKind.GENERATOR)) {
                return;
            }
            advance();
        }
    }

    private boolean match(TokenKind kind) {
        if (!check(kind)) {
            return false;
        }
        advance();
        return true;
    }

    private boolean check(TokenKind kind) {
        return peek().kind() == kind;
    }

    private boolean checkNext(TokenKind kind) {
        return current + 1 < tokens.size() && tokens.get(current + 1).kind() == kind;
    }

    private Token advance() {
        if (!check(TokenKind.EOF)) {
            current++;
        }
        previous = tokens.get(Math.max(0, current - 1));
        return previous;
    }

    private Token peek() {
        return tokens.get(current);
    }

    private SourceSpan span(SourceSpan start, SourceSpan end) {
        return source.span(start.startOffset(), end.endOffset());
    }

    private SchemaDiagnostic error(String code, String message, SourceSpan span) {
        return new SchemaDiagnostic(code, DiagnosticSeverity.ERROR, message, span);
    }
}
