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
                        ? formatPreservingComments(source.text())
                        : new AstPrinter().print(compilation.syntax().orElseThrow());
        return new SchemaFormatResult(java.util.Optional.of(formatted), List.of());
    }

    private String formatPreservingComments(String input) {
        String normalizedNewlines = input.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalizedNewlines.split("\n", -1);
        List<String> output = new ArrayList<>();
        int indent = 0;
        boolean inBlockComment = false;
        boolean previousBlank = true;

        for (String originalLine : lines) {
            String trimmed = originalLine.strip();
            if (trimmed.isEmpty()) {
                if (!previousBlank && !output.isEmpty()) {
                    output.add("");
                }
                previousBlank = true;
                continue;
            }

            boolean startsClosing = !inBlockComment && trimmed.startsWith("}");
            if (startsClosing) {
                indent = Math.max(0, indent - 1);
            }

            String formattedLine =
                    inBlockComment || trimmed.startsWith("/*") || trimmed.startsWith("*")
                            ? trimmed
                            : normalizeCodeLine(trimmed);
            output.add("    ".repeat(indent) + formattedLine);
            previousBlank = false;

            CommentState state = updateCommentState(trimmed, inBlockComment);
            inBlockComment = state.inBlockComment();
            if (!inBlockComment && !startsClosing) {
                indent += Math.max(0, state.openBraces() - state.closeBraces());
            } else if (!inBlockComment && startsClosing) {
                indent += Math.max(0, state.openBraces() - state.closeBraces() + 1);
            }
        }

        while (!output.isEmpty() && output.getLast().isEmpty()) {
            output.removeLast();
        }
        return String.join("\n", output) + "\n";
    }

    private String normalizeCodeLine(String line) {
        Lexer.Result result = Lexer.lex(SourceText.of("<formatter-line>", line));
        List<Token> tokens =
                result.tokens().stream().filter(token -> token.kind() != TokenKind.EOF).toList();
        StringBuilder output = new StringBuilder();
        TokenKind previous = null;
        for (Token token : tokens) {
            if (token.kind() == TokenKind.COMMENT) {
                if (!output.isEmpty() && output.charAt(output.length() - 1) != ' ') {
                    output.append(' ');
                }
                output.append(token.text().stripTrailing());
                previous = token.kind();
                continue;
            }
            if (needsSpace(previous, token.kind(), output)) {
                output.append(' ');
            }
            output.append(token.text());
            previous = token.kind();
        }
        return output.toString();
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

    private CommentState updateCommentState(String line, boolean initiallyInBlockComment) {
        boolean inBlock = initiallyInBlockComment;
        boolean inString = false;
        boolean escaped = false;
        int opens = 0;
        int closes = 0;
        for (int index = 0; index < line.length(); index++) {
            char current = line.charAt(index);
            char next = index + 1 < line.length() ? line.charAt(index + 1) : '\0';
            if (inBlock) {
                if (current == '*' && next == '/') {
                    inBlock = false;
                    index++;
                }
                continue;
            }
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == '"') {
                    inString = false;
                }
                continue;
            }
            if (current == '"') {
                inString = true;
            } else if (current == '/' && next == '/') {
                break;
            } else if (current == '/' && next == '*') {
                inBlock = true;
                index++;
            } else if (current == '{') {
                opens++;
            } else if (current == '}') {
                closes++;
            }
        }
        return new CommentState(inBlock, opens, closes);
    }

    private record CommentState(boolean inBlockComment, int openBraces, int closeBraces) {}

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
