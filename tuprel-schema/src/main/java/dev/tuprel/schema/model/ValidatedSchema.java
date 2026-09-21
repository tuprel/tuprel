package dev.tuprel.schema.model;

import dev.tuprel.schema.SourceSpan;
import dev.tuprel.schema.ast.SchemaDocument;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable, semantically valid schema model consumed by later phases. */
public record ValidatedSchema(
        SchemaDocument syntax,
        List<ValidatedModel> models,
        List<ValidatedEnum> enums,
        Optional<ValidatedConfiguration> datasource,
        Optional<ValidatedConfiguration> generator) {
    /** Defensively copies all validated collections. */
    public ValidatedSchema {
        Objects.requireNonNull(syntax, "syntax");
        models = List.copyOf(models);
        enums = List.copyOf(enums);
        Objects.requireNonNull(datasource, "datasource");
        Objects.requireNonNull(generator, "generator");
    }

    /** A validated model and its fields. */
    public record ValidatedModel(
            String name,
            List<ValidatedField> fields,
            List<ValidatedIndex> indexes,
            SourceSpan span) {
        /** Defensively copies model members. */
        public ValidatedModel {
            Objects.requireNonNull(name, "name");
            fields = List.copyOf(fields);
            indexes = List.copyOf(indexes);
            Objects.requireNonNull(span, "span");
        }
    }

    /** A validated field with its resolved type and core attributes. */
    public record ValidatedField(
            String name,
            ResolvedType type,
            SchemaDocument.Cardinality cardinality,
            boolean id,
            boolean unique,
            Optional<SchemaDocument.Expression> defaultValue,
            Optional<ValidatedRelation> relation,
            SourceSpan span) {
        /** Validates required field data. */
        public ValidatedField {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(cardinality, "cardinality");
            Objects.requireNonNull(defaultValue, "defaultValue");
            Objects.requireNonNull(relation, "relation");
            Objects.requireNonNull(span, "span");
        }
    }

    /** A validated enum. */
    public record ValidatedEnum(String name, List<String> values, SourceSpan span) {
        /** Defensively copies enum values. */
        public ValidatedEnum {
            Objects.requireNonNull(name, "name");
            values = List.copyOf(values);
            Objects.requireNonNull(span, "span");
        }
    }

    /** The category of a resolved field type. */
    public enum TypeKind {
        /** A built-in Tuprel scalar. */
        SCALAR,
        /** A declared enum. */
        ENUM,
        /** A declared model relation. */
        MODEL
    }

    /** A field type resolved against built-ins and declarations. */
    public record ResolvedType(String name, TypeKind kind) {
        /** Validates required type data. */
        public ResolvedType {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(kind, "kind");
        }
    }

    /** A validated index or compound unique constraint. */
    public record ValidatedIndex(String kind, List<String> fields, SourceSpan span) {
        /** Defensively copies indexed fields. */
        public ValidatedIndex {
            Objects.requireNonNull(kind, "kind");
            fields = List.copyOf(fields);
            Objects.requireNonNull(span, "span");
        }
    }

    /** A validated explicit relation. */
    public record ValidatedRelation(
            String targetModel,
            List<String> fields,
            List<String> references,
            SourceSpan span) {
        /** Defensively copies relation columns. */
        public ValidatedRelation {
            Objects.requireNonNull(targetModel, "targetModel");
            fields = List.copyOf(fields);
            references = List.copyOf(references);
            Objects.requireNonNull(span, "span");
        }
    }

    /** A validated datasource or generator configuration. */
    public record ValidatedConfiguration(
            String name, Map<String, SchemaDocument.Expression> properties, SourceSpan span) {
        /** Preserves declaration order while preventing mutation. */
        public ValidatedConfiguration {
            Objects.requireNonNull(name, "name");
            properties = Collections.unmodifiableMap(new LinkedHashMap<>(properties));
            Objects.requireNonNull(span, "span");
        }
    }
}
