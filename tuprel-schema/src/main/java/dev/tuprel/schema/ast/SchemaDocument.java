package dev.tuprel.schema.ast;

import dev.tuprel.schema.SourceSpan;
import java.util.List;
import java.util.Objects;

/** Immutable syntax tree for one Tuprel schema document. */
public record SchemaDocument(List<Declaration> declarations, SourceSpan span)
        implements SchemaNode {
    /** Defensively copies the declaration list. */
    public SchemaDocument {
        declarations = List.copyOf(declarations);
        Objects.requireNonNull(span, "span");
    }

    /** Base contract for top-level declarations. */
    public sealed interface Declaration extends SchemaNode
            permits DatasourceDeclaration, GeneratorDeclaration, ModelDeclaration, EnumDeclaration {}

    /** A datasource configuration block. */
    public record DatasourceDeclaration(
            String name, List<ConfigProperty> properties, SourceSpan span) implements Declaration {
        /** Defensively copies configuration properties. */
        public DatasourceDeclaration {
            Objects.requireNonNull(name, "name");
            properties = List.copyOf(properties);
            Objects.requireNonNull(span, "span");
        }
    }

    /** A generator configuration block. */
    public record GeneratorDeclaration(
            String name, List<ConfigProperty> properties, SourceSpan span) implements Declaration {
        /** Defensively copies configuration properties. */
        public GeneratorDeclaration {
            Objects.requireNonNull(name, "name");
            properties = List.copyOf(properties);
            Objects.requireNonNull(span, "span");
        }
    }

    /** One key/value property inside a configuration declaration. */
    public record ConfigProperty(String name, Expression value, SourceSpan span) implements SchemaNode {
        /** Validates required property values. */
        public ConfigProperty {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(span, "span");
        }
    }

    /** A relational model declaration. */
    public record ModelDeclaration(
            String name,
            List<FieldDeclaration> fields,
            List<BlockAttribute> attributes,
            SourceSpan span)
            implements Declaration {
        /** Defensively copies model members. */
        public ModelDeclaration {
            Objects.requireNonNull(name, "name");
            fields = List.copyOf(fields);
            attributes = List.copyOf(attributes);
            Objects.requireNonNull(span, "span");
        }
    }

    /** An enum declaration. */
    public record EnumDeclaration(String name, List<EnumValue> values, SourceSpan span)
            implements Declaration {
        /** Defensively copies enum values. */
        public EnumDeclaration {
            Objects.requireNonNull(name, "name");
            values = List.copyOf(values);
            Objects.requireNonNull(span, "span");
        }
    }

    /** One enum value. */
    public record EnumValue(String name, SourceSpan span) implements SchemaNode {
        /** Validates required enum value data. */
        public EnumValue {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(span, "span");
        }
    }

    /** A model field declaration. */
    public record FieldDeclaration(
            String name, TypeReference type, List<FieldAttribute> attributes, SourceSpan span)
            implements SchemaNode {
        /** Defensively copies field attributes. */
        public FieldDeclaration {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(type, "type");
            attributes = List.copyOf(attributes);
            Objects.requireNonNull(span, "span");
        }
    }

    /** Cardinality forms supported by RFC-001. */
    public enum Cardinality {
        /** One required value. */
        REQUIRED,
        /** One nullable value. */
        OPTIONAL,
        /** A non-null list of non-null values. */
        LIST
    }

    /** A named type and its cardinality. */
    public record TypeReference(String name, Cardinality cardinality, SourceSpan span)
            implements SchemaNode {
        /** Validates required type reference data. */
        public TypeReference {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(cardinality, "cardinality");
            Objects.requireNonNull(span, "span");
        }
    }

    /** A field-level attribute such as {@code @id}. */
    public record FieldAttribute(String name, List<Argument> arguments, SourceSpan span)
            implements SchemaNode {
        /** Defensively copies attribute arguments. */
        public FieldAttribute {
            Objects.requireNonNull(name, "name");
            arguments = List.copyOf(arguments);
            Objects.requireNonNull(span, "span");
        }
    }

    /** A model-level attribute such as {@code @@index}. */
    public record BlockAttribute(String name, List<Argument> arguments, SourceSpan span)
            implements SchemaNode {
        /** Defensively copies attribute arguments. */
        public BlockAttribute {
            Objects.requireNonNull(name, "name");
            arguments = List.copyOf(arguments);
            Objects.requireNonNull(span, "span");
        }
    }

    /** A positional or named argument. A null name denotes a positional argument. */
    public record Argument(String name, Expression value, SourceSpan span) implements SchemaNode {
        /** Validates required argument data. */
        public Argument {
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(span, "span");
        }

        /** Returns whether this argument has an explicit name. */
        public boolean isNamed() {
            return name != null;
        }
    }

    /** Base contract for schema expressions. */
    public sealed interface Expression extends SchemaNode
            permits StringLiteral, IntegerLiteral, BooleanLiteral, Symbol, Call, ListValue {}

    /** A decoded string literal. */
    public record StringLiteral(String value, SourceSpan span) implements Expression {
        /** Validates required literal data. */
        public StringLiteral {
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(span, "span");
        }
    }

    /** An integer literal retained as text to avoid premature numeric narrowing. */
    public record IntegerLiteral(String value, SourceSpan span) implements Expression {
        /** Validates required literal data. */
        public IntegerLiteral {
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(span, "span");
        }
    }

    /** A boolean literal. */
    public record BooleanLiteral(boolean value, SourceSpan span) implements Expression {
        /** Validates required literal data. */
        public BooleanLiteral {
            Objects.requireNonNull(span, "span");
        }
    }

    /** A symbolic value, such as an enum member or field name. */
    public record Symbol(String value, SourceSpan span) implements Expression {
        /** Validates required symbol data. */
        public Symbol {
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(span, "span");
        }
    }

    /** A named expression call such as {@code uuid()}. */
    public record Call(String name, List<Argument> arguments, SourceSpan span) implements Expression {
        /** Defensively copies call arguments. */
        public Call {
            Objects.requireNonNull(name, "name");
            arguments = List.copyOf(arguments);
            Objects.requireNonNull(span, "span");
        }
    }

    /** A list expression. */
    public record ListValue(List<Expression> values, SourceSpan span) implements Expression {
        /** Defensively copies list elements. */
        public ListValue {
            values = List.copyOf(values);
            Objects.requireNonNull(span, "span");
        }
    }
}
