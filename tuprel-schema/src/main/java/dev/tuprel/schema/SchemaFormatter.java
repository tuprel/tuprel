package dev.tuprel.schema;

import dev.tuprel.schema.Lexer.Token;
import dev.tuprel.schema.Lexer.TokenKind;
import dev.tuprel.schema.ast.SchemaDocument;
import dev.tuprel.schema.ast.SchemaDocument.Argument;
import dev.tuprel.schema.ast.SchemaDocument.BlockAttribute;
import dev.tuprel.schema.ast.SchemaDocument.ConfigProperty;
import dev.tuprel.schema.ast.SchemaDocument.Declaration;
import dev.tuprel.schema.ast.SchemaDocument.Expression;
import dev.tuprel.schema.ast.SchemaDocument.FieldAttribute;
import dev.tuprel.schema.ast.SchemaDocument.FieldDeclaration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;

/** Deterministic formatter for the accepted RFC-001 language subset. */
public final class SchemaFormatter {
    private final SchemaCompiler compiler = new SchemaCompiler();

    /** Validates and formats a schema, returning diagnostics instead of partial output. */
    public SchemaFormatResult format(SourceText source) {
        Objects.requireNonNull(source, "source");
        SchemaCompilation compilation = compiler.compile(source);
        if (!compilation.isValid()) {
            return new SchemaFormatResult(java.util.Optional.empty(), compilation.diagnostics());
        }

        Lexer.Result lexed = Lexer.lex(source);
        boolean hasComments =
                lexed.tokens().stream().anyMatch(token -> token.kind() == TokenKind.COMMENT);
        String formatted =
                hasComments
                        ? formatPreservingComments(source.text(), lexed.tokens())
                        : new AstPrinter().print(compilation.syntax().orElseThrow());
        return new SchemaFormatResult(java.util.Optional.of(formatted), List.of());
    }

    /**
     * Checks that formatting preserved the ordered language tokens and comments.
     * Decoded string values, normalised comment line endings and optional empty
     * field-attribute argument lists are compared according to RFC-001.
     * Callers must validate both schemas separately.
     */
    public boolean preservesContents(SourceText original, SourceText formatted) {
        Lexer.Result before = Lexer.lex(original);
        Lexer.Result after = Lexer.lex(formatted);
        return before.diagnostics().isEmpty() && after.diagnostics().isEmpty()
                && comparableTokens(before.tokens()).equals(comparableTokens(after.tokens()));
    }

    private static List<TokenContent> comparableTokens(List<Token> tokens) {
        List<TokenContent> result = new ArrayList<>(tokens.size());
        for (int index = 0; index < tokens.size(); index++) {
            Token token = tokens.get(index);
            TokenKind kind = token.kind();
            String value = kind == TokenKind.COMMENT
                    ? token.text().replace("\r\n", "\n").replace('\r', '\n')
                    : token.value();
            result.add(new TokenContent(kind, value));

            // The AST printer omits empty parentheses on field attributes.
            if (kind == TokenKind.IDENTIFIER && index > 0 && index + 2 < tokens.size()
                    && tokens.get(index - 1).kind() == TokenKind.AT
                    && tokens.get(index + 1).kind() == TokenKind.LEFT_PAREN
                    && tokens.get(index + 2).kind() == TokenKind.RIGHT_PAREN) {
                index += 2;
            }
        }
        return result;
    }

    private record TokenContent(TokenKind kind, String value) {}

    private String formatPreservingComments(String input, List<Token> tokens) {
        StringBuilder output = new StringBuilder();
        int indent = 0;
        Token previous = null;
        for (Token token : tokens) {
            TokenKind kind = token.kind();
            if (kind == TokenKind.EOF) {
                break;
            }
            if (kind == TokenKind.RIGHT_BRACE) {
                indent--;
            }

            if (previous == null) {
                output.append("    ".repeat(indent));
            } else {
                int lineBreaks = countLineBreaks(
                        input, previous.span().endOffset(), token.span().startOffset());
                if (lineBreaks > 0) {
                    output.append("\n".repeat(lineBreaks)).append("    ".repeat(indent));
                } else if (kind == TokenKind.COMMENT || needsSpace(previous.kind(), kind, output)) {
                    output.append(' ');
                }
            }
            // A comment token owns its interior whitespace, including its continuation lines.
            output.append(kind == TokenKind.COMMENT
                    ? token.text().replace("\r\n", "\n").replace('\r', '\n')
                    : token.text());

            if (kind == TokenKind.LEFT_BRACE) {
                indent++;
            }
            previous = token;
        }
        return output.append('\n').toString();
    }

    private int countLineBreaks(String input, int start, int end) {
        int count = 0;
        for (int index = start; index < end; index++) {
            char current = input.charAt(index);
            if (current == '\r' || (current == '\n' && (index == start || input.charAt(index - 1) != '\r'))) {
                count++;
                if (count == 2) {
                    return count;
                }
            }
        }
        return count;
    }

    private boolean needsSpace(TokenKind previous, TokenKind current, StringBuilder output) {
        if (previous == null || output.isEmpty()) {
            return false;
        }
        if (current == TokenKind.RIGHT_PAREN
                || current == TokenKind.RIGHT_BRACKET
                || current == TokenKind.QUESTION
                || current == TokenKind.COMMA
                || current == TokenKind.COLON) {
            return false;
        }
        if (previous == TokenKind.LEFT_PAREN
                || previous == TokenKind.LEFT_BRACKET
                || previous == TokenKind.AT
                || previous == TokenKind.AT_AT) {
            return false;
        }
        if (current == TokenKind.LEFT_PAREN && previous == TokenKind.IDENTIFIER) {
            return false;
        }
        if (current == TokenKind.LEFT_BRACKET && previous == TokenKind.IDENTIFIER) {
            return false;
        }
        if (current == TokenKind.EQUALS || previous == TokenKind.EQUALS) {
            return output.charAt(output.length() - 1) != ' ';
        }
        return true;
    }

    private static final class AstPrinter {
        private final StringBuilder output = new StringBuilder();

        String print(SchemaDocument document) {
            for (int index = 0; index < document.declarations().size(); index++) {
                if (index > 0) {
                    output.append('\n');
                }
                printDeclaration(document.declarations().get(index));
            }
            return output.toString();
        }

        private void printDeclaration(Declaration declaration) {
            if (declaration instanceof SchemaDocument.DatasourceDeclaration datasource) {
                printConfiguration("datasource", datasource.name(), datasource.properties());
            } else if (declaration instanceof SchemaDocument.GeneratorDeclaration generator) {
                printConfiguration("generator", generator.name(), generator.properties());
            } else if (declaration instanceof SchemaDocument.ModelDeclaration model) {
                printModel(model);
            } else if (declaration instanceof SchemaDocument.EnumDeclaration enumDeclaration) {
                printEnum(enumDeclaration);
            }
        }

        private void printConfiguration(
                String keyword, String name, List<ConfigProperty> properties) {
            output.append(keyword).append(' ').append(name).append(" {\n");
            for (ConfigProperty property : properties) {
                output.append("    ")
                        .append(property.name())
                        .append(" = ")
                        .append(expression(property.value()))
                        .append('\n');
            }
            output.append("}\n");
        }

        private void printModel(SchemaDocument.ModelDeclaration model) {
            output.append("model ").append(model.name()).append(" {\n");
            for (FieldDeclaration field : model.fields()) {
                output.append("    ")
                        .append(field.name())
                        .append(' ')
                        .append(field.type().name())
                        .append(cardinalitySuffix(field.type().cardinality()));
                for (FieldAttribute attribute : field.attributes()) {
                    output.append(" @").append(attribute.name());
                    appendArguments(attribute.arguments());
                }
                output.append('\n');
            }
            if (!model.fields().isEmpty() && !model.attributes().isEmpty()) {
                output.append('\n');
            }
            for (BlockAttribute attribute : model.attributes()) {
                output.append("    @@").append(attribute.name());
                appendArguments(attribute.arguments());
                output.append('\n');
            }
            output.append("}\n");
        }

        private void printEnum(SchemaDocument.EnumDeclaration declaration) {
            output.append("enum ").append(declaration.name()).append(" {\n");
            for (SchemaDocument.EnumValue value : declaration.values()) {
                output.append("    ").append(value.name()).append('\n');
            }
            output.append("}\n");
        }

        private void appendArguments(List<Argument> arguments) {
            if (arguments.isEmpty()) {
                return;
            }
            StringJoiner joiner = new StringJoiner(", ", "(", ")");
            for (Argument argument : arguments) {
                String prefix = argument.isNamed() ? argument.name() + ": " : "";
                joiner.add(prefix + expression(argument.value()));
            }
            output.append(joiner);
        }

        private String expression(Expression expression) {
            if (expression instanceof SchemaDocument.StringLiteral string) {
                return '"' + escape(string.value()) + '"';
            }
            if (expression instanceof SchemaDocument.IntegerLiteral integer) {
                return integer.value();
            }
            if (expression instanceof SchemaDocument.BooleanLiteral bool) {
                return Boolean.toString(bool.value());
            }
            if (expression instanceof SchemaDocument.Symbol symbol) {
                return symbol.value();
            }
            if (expression instanceof SchemaDocument.Call call) {
                StringJoiner joiner = new StringJoiner(", ", call.name() + "(", ")");
                for (Argument argument : call.arguments()) {
                    String prefix = argument.isNamed() ? argument.name() + ": " : "";
                    joiner.add(prefix + expression(argument.value()));
                }
                return joiner.toString();
            }
            SchemaDocument.ListValue list = (SchemaDocument.ListValue) expression;
            StringJoiner joiner = new StringJoiner(", ", "[", "]");
            for (Expression value : list.values()) {
                joiner.add(expression(value));
            }
            return joiner.toString();
        }

        private String cardinalitySuffix(SchemaDocument.Cardinality cardinality) {
            return switch (cardinality) {
                case REQUIRED -> "";
                case OPTIONAL -> "?";
                case LIST -> "[]";
            };
        }

        private String escape(String value) {
            return value.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t");
        }
    }
}
