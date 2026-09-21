package dev.tuprel.schema;

import dev.tuprel.schema.ast.SchemaDocument;
import dev.tuprel.schema.ast.SchemaDocument.Argument;
import dev.tuprel.schema.ast.SchemaDocument.BlockAttribute;
import dev.tuprel.schema.ast.SchemaDocument.ConfigProperty;
import dev.tuprel.schema.ast.SchemaDocument.EnumDeclaration;
import dev.tuprel.schema.ast.SchemaDocument.FieldAttribute;
import dev.tuprel.schema.ast.SchemaDocument.FieldDeclaration;
import dev.tuprel.schema.ast.SchemaDocument.ModelDeclaration;
import dev.tuprel.schema.model.ValidatedSchema;
import dev.tuprel.schema.model.ValidatedSchema.ResolvedType;
import dev.tuprel.schema.model.ValidatedSchema.TypeKind;
import dev.tuprel.schema.model.ValidatedSchema.ValidatedConfiguration;
import dev.tuprel.schema.model.ValidatedSchema.ValidatedEnum;
import dev.tuprel.schema.model.ValidatedSchema.ValidatedField;
import dev.tuprel.schema.model.ValidatedSchema.ValidatedIndex;
import dev.tuprel.schema.model.ValidatedSchema.ValidatedModel;
import dev.tuprel.schema.model.ValidatedSchema.ValidatedRelation;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

final class SemanticValidator {
    record Result(Optional<ValidatedSchema> schema, List<SchemaDiagnostic> diagnostics) {
        Result {
            diagnostics = List.copyOf(diagnostics);
        }
    }

    private static final Set<String> SCALARS =
            Set.of(
                    "String",
                    "Boolean",
                    "Short",
                    "Int",
                    "Long",
                    "Decimal",
                    "Float",
                    "Double",
                    "UUID",
                    "Instant",
                    "LocalDateTime",
                    "LocalDate",
                    "LocalTime",
                    "Bytes",
                    "Json");

    private static final Set<String> NUMERIC_SCALARS =
            Set.of("Short", "Int", "Long", "Decimal", "Float", "Double");

    private static final Set<String> JAVA_RESERVED =
            Set.of(
                    "abstract",
                    "assert",
                    "boolean",
                    "break",
                    "byte",
                    "case",
                    "catch",
                    "char",
                    "class",
                    "const",
                    "continue",
                    "default",
                    "do",
                    "double",
                    "else",
                    "enum",
                    "exports",
                    "extends",
                    "false",
                    "final",
                    "finally",
                    "float",
                    "for",
                    "goto",
                    "if",
                    "implements",
                    "import",
                    "instanceof",
                    "int",
                    "interface",
                    "long",
                    "module",
                    "native",
                    "new",
                    "non-sealed",
                    "null",
                    "open",
                    "opens",
                    "package",
                    "permits",
                    "private",
                    "protected",
                    "provides",
                    "public",
                    "record",
                    "requires",
                    "return",
                    "sealed",
                    "short",
                    "static",
                    "strictfp",
                    "super",
                    "switch",
                    "synchronized",
                    "this",
                    "throw",
                    "throws",
                    "to",
                    "transient",
                    "transitive",
                    "true",
                    "try",
                    "uses",
                    "var",
                    "void",
                    "volatile",
                    "while",
                    "with",
                    "yield");

    private final SchemaDocument document;
    private final List<SchemaDiagnostic> diagnostics = new ArrayList<>();
    private final Map<String, ModelDeclaration> models = new LinkedHashMap<>();
    private final Map<String, EnumDeclaration> enums = new LinkedHashMap<>();

    private SemanticValidator(SchemaDocument document) {
        this.document = document;
    }

    static Result validate(SchemaDocument document) {
        return new SemanticValidator(document).validate();
    }

    private Result validate() {
        collectTypes();
        if (models.isEmpty()) {
            add(
                    "TUPREL-SCHEMA-SEM-001",
                    "A schema must declare at least one model.",
                    document.span());
        }

        List<ValidatedEnum> validatedEnums = validateEnums();
        List<ValidatedModel> validatedModels = validateModels();
        Optional<ValidatedConfiguration> datasource = validateDatasource();
        Optional<ValidatedConfiguration> generator = validateGenerator();

        if (!diagnostics.isEmpty()) {
            return new Result(Optional.empty(), diagnostics);
        }
        return new Result(
                Optional.of(
                        new ValidatedSchema(
                                document,
                                validatedModels,
                                validatedEnums,
                                datasource,
                                generator)),
                diagnostics);
    }

    private void collectTypes() {
        Map<String, SchemaDocument.Declaration> declarations = new LinkedHashMap<>();
        for (SchemaDocument.Declaration declaration : document.declarations()) {
            String name = declarationName(declaration);
            if (name == null) {
                continue;
            }
            if (declaration instanceof ModelDeclaration || declaration instanceof EnumDeclaration) {
                SchemaDocument.Declaration existing = declarations.putIfAbsent(name, declaration);
                if (existing != null) {
                    add(
                            "TUPREL-SCHEMA-SEM-005",
                            "Type name '%s' is declared more than once.".formatted(name),
                            declaration.span());
                    continue;
                }
                validateName(name, "type", declaration.span());
                if (declaration instanceof ModelDeclaration model) {
                    models.put(name, model);
                } else if (declaration instanceof EnumDeclaration enumDeclaration) {
                    enums.put(name, enumDeclaration);
                }
            }
        }
    }

    private List<ValidatedEnum> validateEnums() {
        List<ValidatedEnum> result = new ArrayList<>();
        for (EnumDeclaration declaration : enums.values()) {
            if (declaration.values().isEmpty()) {
                add(
                        "TUPREL-SCHEMA-SEM-020",
                        "Enum '%s' must declare at least one value.".formatted(declaration.name()),
                        declaration.span());
            }
            Set<String> values = new LinkedHashSet<>();
            for (SchemaDocument.EnumValue value : declaration.values()) {
                validateName(value.name(), "enum value", value.span());
                if (!values.add(value.name())) {
                    add(
                            "TUPREL-SCHEMA-SEM-014",
                            "Enum '%s' declares value '%s' more than once."
                                    .formatted(declaration.name(), value.name()),
                            value.span());
                }
            }
            result.add(
                    new ValidatedEnum(
                            declaration.name(), List.copyOf(values), declaration.span()));
        }
        return result;
    }

    private List<ValidatedModel> validateModels() {
        List<ValidatedModel> result = new ArrayList<>();
        for (ModelDeclaration model : models.values()) {
            result.add(validateModel(model));
        }
        return result;
    }

    private ValidatedModel validateModel(ModelDeclaration model) {
        if (model.fields().isEmpty()) {
            add(
                    "TUPREL-SCHEMA-SEM-020",
                    "Model '%s' must declare at least one field.".formatted(model.name()),
                    model.span());
        }

        Map<String, FieldDeclaration> fields = new LinkedHashMap<>();
        for (FieldDeclaration field : model.fields()) {
            validateName(field.name(), "field", field.span());
            if (fields.putIfAbsent(field.name(), field) != null) {
                add(
                        "TUPREL-SCHEMA-SEM-002",
                        "Model '%s' declares field '%s' more than once."
                                .formatted(model.name(), field.name()),
                        field.span());
            }
        }

        List<ValidatedIndex> indexes = validateBlockAttributes(model, fields);
        List<ValidatedField> validatedFields = new ArrayList<>();
        int idCount = 0;
        for (FieldDeclaration field : model.fields()) {
            Map<String, FieldAttribute> attributes = collectFieldAttributes(model, field);
            if (attributes.containsKey("id")) {
                idCount++;
            }
            validatedFields.add(validateField(model, field, fields, attributes));
        }
        if (idCount == 0) {
            add(
                    "TUPREL-SCHEMA-SEM-006",
                    "Model '%s' must declare exactly one @id field.".formatted(model.name()),
                    model.span());
        } else if (idCount > 1) {
            add(
                    "TUPREL-SCHEMA-SEM-007",
                    "Model '%s' declares more than one @id field.".formatted(model.name()),
                    model.span());
        }
        return new ValidatedModel(model.name(), validatedFields, indexes, model.span());
    }

    private Map<String, FieldAttribute> collectFieldAttributes(
            ModelDeclaration model, FieldDeclaration field) {
        Map<String, FieldAttribute> attributes = new LinkedHashMap<>();
        for (FieldAttribute attribute : field.attributes()) {
            if (!Set.of("id", "unique", "default", "relation").contains(attribute.name())) {
                add(
                        "TUPREL-SCHEMA-SEM-009",
                        "Unknown field attribute '@%s' on '%s.%s'."
                                .formatted(attribute.name(), model.name(), field.name()),
                        attribute.span());
                continue;
            }
            if (attributes.putIfAbsent(attribute.name(), attribute) != null) {
                add(
                        "TUPREL-SCHEMA-SEM-010",
                        "Attribute '@%s' appears more than once on '%s.%s'."
                                .formatted(attribute.name(), model.name(), field.name()),
                        attribute.span());
            }
        }
        return attributes;
    }

    private ValidatedField validateField(
            ModelDeclaration model,
            FieldDeclaration field,
            Map<String, FieldDeclaration> modelFields,
            Map<String, FieldAttribute> attributes) {
        Optional<ResolvedType> resolved = resolve(field.type().name());
        if (resolved.isEmpty()) {
            add(
                    "TUPREL-SCHEMA-SEM-004",
                    "Unknown type '%s' for field '%s.%s'."
                            .formatted(field.type().name(), model.name(), field.name()),
                    field.type().span());
        }
        ResolvedType type =
                resolved.orElse(new ResolvedType(field.type().name(), TypeKind.SCALAR));

        boolean id = attributes.containsKey("id");
        boolean unique = attributes.containsKey("unique");
        if (id) {
            validateNoArguments(attributes.get("id"), model, field);
            if (type.kind() != TypeKind.SCALAR
                    || field.type().cardinality() != SchemaDocument.Cardinality.REQUIRED) {
                add(
                        "TUPREL-SCHEMA-SEM-008",
                        "@id on '%s.%s' requires a singular required scalar field."
                                .formatted(model.name(), field.name()),
                        attributes.get("id").span());
            }
        }
        if (unique) {
            validateNoArguments(attributes.get("unique"), model, field);
            if (type.kind() == TypeKind.MODEL
                    || field.type().cardinality() == SchemaDocument.Cardinality.LIST) {
                add(
                        "TUPREL-SCHEMA-SEM-011",
                        "@unique on '%s.%s' requires a singular scalar or enum field."
                                .formatted(model.name(), field.name()),
                        attributes.get("unique").span());
            }
        }

        Optional<SchemaDocument.Expression> defaultValue = Optional.empty();
        FieldAttribute defaultAttribute = attributes.get("default");
        if (defaultAttribute != null) {
            defaultValue = validateDefault(model, field, type, defaultAttribute);
        }

        Optional<ValidatedRelation> relation = Optional.empty();
        FieldAttribute relationAttribute = attributes.get("relation");
        if (type.kind() == TypeKind.MODEL) {
            if (field.type().cardinality() == SchemaDocument.Cardinality.LIST) {
                if (relationAttribute != null) {
                    add(
                            "TUPREL-SCHEMA-SEM-015",
                            "List relation '%s.%s' cannot declare @relation in Phase 1."
                                    .formatted(model.name(), field.name()),
                            relationAttribute.span());
                }
            } else if (relationAttribute != null) {
                relation = validateRelation(model, field, modelFields, relationAttribute);
            }
        } else if (relationAttribute != null) {
            add(
                    "TUPREL-SCHEMA-SEM-015",
                    "@relation on '%s.%s' requires a model type."
                            .formatted(model.name(), field.name()),
                    relationAttribute.span());
        }

        return new ValidatedField(
                field.name(),
                type,
                field.type().cardinality(),
                id,
                unique,
                defaultValue,
                relation,
                field.span());
    }

    private void validateNoArguments(
            FieldAttribute attribute, ModelDeclaration model, FieldDeclaration field) {
        if (!attribute.arguments().isEmpty()) {
            add(
                    "TUPREL-SCHEMA-SEM-011",
                    "@%s on '%s.%s' does not accept arguments."
                            .formatted(attribute.name(), model.name(), field.name()),
                    attribute.span());
        }
    }

    private Optional<SchemaDocument.Expression> validateDefault(
            ModelDeclaration model,
            FieldDeclaration field,
            ResolvedType type,
            FieldAttribute attribute) {
        if (attribute.arguments().size() != 1 || attribute.arguments().getFirst().isNamed()) {
            add(
                    "TUPREL-SCHEMA-SEM-011",
                    "@default on '%s.%s' requires one positional argument."
                            .formatted(model.name(), field.name()),
                    attribute.span());
            return Optional.empty();
        }
        if (field.type().cardinality() != SchemaDocument.Cardinality.REQUIRED
                || type.kind() == TypeKind.MODEL) {
            add(
                    "TUPREL-SCHEMA-SEM-012",
                    "@default on '%s.%s' requires a required scalar or enum field."
                            .formatted(model.name(), field.name()),
                    attribute.span());
            return Optional.empty();
        }

        SchemaDocument.Expression expression = attribute.arguments().getFirst().value();
        boolean compatible = isCompatibleDefault(type, expression);
        if (!compatible) {
            add(
                    "TUPREL-SCHEMA-SEM-012",
                    "Default for '%s.%s' is incompatible with type '%s'."
                            .formatted(model.name(), field.name(), type.name()),
                    expression.span());
            return Optional.empty();
        }
        return Optional.of(expression);
    }

    private boolean isCompatibleDefault(ResolvedType type, SchemaDocument.Expression expression) {
        if (type.kind() == TypeKind.ENUM) {
            if (!(expression instanceof SchemaDocument.Symbol symbol)) {
                return false;
            }
            EnumDeclaration declaration = enums.get(type.name());
            return declaration.values().stream().anyMatch(value -> value.name().equals(symbol.value()));
        }
        if (type.kind() != TypeKind.SCALAR) {
            return false;
        }
        if (type.name().equals("String")) {
            return expression instanceof SchemaDocument.StringLiteral;
        }
        if (type.name().equals("Boolean")) {
            return expression instanceof SchemaDocument.BooleanLiteral;
        }
        if (NUMERIC_SCALARS.contains(type.name())) {
            return expression instanceof SchemaDocument.IntegerLiteral;
        }
        if (expression instanceof SchemaDocument.Call call && call.arguments().isEmpty()) {
            return (type.name().equals("UUID") && call.name().equals("uuid"))
                    || (Set.of("Instant", "LocalDateTime").contains(type.name())
                            && call.name().equals("now"))
                    || (Set.of("Short", "Int", "Long").contains(type.name())
                            && call.name().equals("identity"));
        }
        return false;
    }

    private Optional<ValidatedRelation> validateRelation(
            ModelDeclaration model,
            FieldDeclaration relationField,
            Map<String, FieldDeclaration> localFields,
            FieldAttribute attribute) {
        Map<String, Argument> arguments = new LinkedHashMap<>();
        boolean malformed = false;
        for (Argument argument : attribute.arguments()) {
            if (!argument.isNamed()
                    || !Set.of("fields", "references").contains(argument.name())
                    || arguments.putIfAbsent(argument.name(), argument) != null) {
                malformed = true;
            }
        }
        if (malformed || !arguments.keySet().equals(Set.of("fields", "references"))) {
            add(
                    "TUPREL-SCHEMA-SEM-015",
                    "@relation on '%s.%s' requires unique named arguments 'fields' and 'references'."
                            .formatted(model.name(), relationField.name()),
                    attribute.span());
            return Optional.empty();
        }

        Optional<List<String>> localNames = symbolList(arguments.get("fields").value());
        Optional<List<String>> referenceNames = symbolList(arguments.get("references").value());
        if (localNames.isEmpty()
                || referenceNames.isEmpty()
                || localNames.get().isEmpty()
                || localNames.get().size() != referenceNames.get().size()) {
            add(
                    "TUPREL-SCHEMA-SEM-015",
                    "Relation '%s.%s' requires non-empty fields and references lists of equal size."
                            .formatted(model.name(), relationField.name()),
                    attribute.span());
            return Optional.empty();
        }

        ModelDeclaration target = models.get(relationField.type().name());
        if (target == null) {
            return Optional.empty();
        }
        Map<String, FieldDeclaration> targetFields =
                target.fields().stream()
                        .collect(
                                Collectors.toMap(
                                        FieldDeclaration::name,
                                        Function.identity(),
                                        (first, ignored) -> first,
                                        LinkedHashMap::new));

        boolean invalid = false;
        List<FieldDeclaration> resolvedLocal = new ArrayList<>();
        for (int index = 0; index < localNames.get().size(); index++) {
            String localName = localNames.get().get(index);
            String referenceName = referenceNames.get().get(index);
            FieldDeclaration local = localFields.get(localName);
            FieldDeclaration reference = targetFields.get(referenceName);
            if (local == null) {
                invalid = true;
                add(
                        "TUPREL-SCHEMA-SEM-016",
                        "Relation '%s.%s' references unknown local field '%s'."
                                .formatted(model.name(), relationField.name(), localName),
                        arguments.get("fields").span());
            }
            if (reference == null) {
                invalid = true;
                add(
                        "TUPREL-SCHEMA-SEM-016",
                        "Relation '%s.%s' references unknown field '%s.%s'."
                                .formatted(
                                        model.name(),
                                        relationField.name(),
                                        target.name(),
                                        referenceName),
                        arguments.get("references").span());
            }
            if (local != null && reference != null) {
                resolvedLocal.add(local);
                if (!local.type().name().equals(reference.type().name())
                        || local.type().cardinality() == SchemaDocument.Cardinality.LIST
                        || reference.type().cardinality() == SchemaDocument.Cardinality.LIST) {
                    invalid = true;
                    add(
                            "TUPREL-SCHEMA-SEM-017",
                            "Relation '%s.%s' has incompatible field types '%s' and '%s'."
                                    .formatted(
                                            model.name(),
                                            relationField.name(),
                                            local.type().name(),
                                            reference.type().name()),
                            attribute.span());
                }
            }
        }

        if (!invalid && !isUniqueReference(target, referenceNames.get())) {
            invalid = true;
            add(
                    "TUPREL-SCHEMA-SEM-015",
                    "Relation '%s.%s' must reference an @id, @unique, or matching @@unique key."
                            .formatted(model.name(), relationField.name()),
                    arguments.get("references").span());
        }

        if (!invalid) {
            boolean relationOptional =
                    relationField.type().cardinality() == SchemaDocument.Cardinality.OPTIONAL;
            boolean allLocalOptional =
                    resolvedLocal.stream()
                            .allMatch(
                                    field ->
                                            field.type().cardinality()
                                                    == SchemaDocument.Cardinality.OPTIONAL);
            boolean allLocalRequired =
                    resolvedLocal.stream()
                            .allMatch(
                                    field ->
                                            field.type().cardinality()
                                                    == SchemaDocument.Cardinality.REQUIRED);
            if (!((relationOptional && allLocalOptional)
                    || (!relationOptional && allLocalRequired))) {
                invalid = true;
                add(
                        "TUPREL-SCHEMA-SEM-017",
                        "Relation '%s.%s' and its local fields must have matching nullability."
                                .formatted(model.name(), relationField.name()),
                        attribute.span());
            }
        }

        if (invalid) {
            return Optional.empty();
        }
        return Optional.of(
                new ValidatedRelation(
                        target.name(), localNames.get(), referenceNames.get(), attribute.span()));
    }

    private boolean isUniqueReference(ModelDeclaration target, List<String> references) {
        if (references.size() == 1) {
            String reference = references.getFirst();
            Optional<FieldDeclaration> field =
                    target.fields().stream().filter(item -> item.name().equals(reference)).findFirst();
            if (field.isPresent()
                    && field.get().attributes().stream()
                            .anyMatch(
                                    attribute ->
                                            attribute.name().equals("id")
                                                    || attribute.name().equals("unique"))) {
                return true;
            }
        }
        return target.attributes().stream()
                .filter(attribute -> attribute.name().equals("unique"))
                .map(BlockAttribute::arguments)
                .filter(arguments -> arguments.size() == 1)
                .map(arguments -> symbolList(arguments.getFirst().value()))
                .flatMap(Optional::stream)
                .anyMatch(references::equals);
    }

    private List<ValidatedIndex> validateBlockAttributes(
            ModelDeclaration model, Map<String, FieldDeclaration> fields) {
        List<ValidatedIndex> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (BlockAttribute attribute : model.attributes()) {
            if (!Set.of("index", "unique").contains(attribute.name())) {
                add(
                        "TUPREL-SCHEMA-SEM-009",
                        "Unknown model attribute '@@%s' on '%s'."
                                .formatted(attribute.name(), model.name()),
                        attribute.span());
                continue;
            }
            if (attribute.arguments().size() != 1
                    || attribute.arguments().getFirst().isNamed()) {
                add(
                        "TUPREL-SCHEMA-SEM-013",
                        "@@%s on '%s' requires one positional field list."
                                .formatted(attribute.name(), model.name()),
                        attribute.span());
                continue;
            }
            Optional<List<String>> names = symbolList(attribute.arguments().getFirst().value());
            if (names.isEmpty() || names.get().isEmpty()) {
                add(
                        "TUPREL-SCHEMA-SEM-013",
                        "@@%s on '%s' requires a non-empty field list."
                                .formatted(attribute.name(), model.name()),
                        attribute.span());
                continue;
            }
            boolean invalid = false;
            Set<String> uniqueNames = new HashSet<>();
            for (String name : names.get()) {
                FieldDeclaration field = fields.get(name);
                if (field == null) {
                    invalid = true;
                    add(
                            "TUPREL-SCHEMA-SEM-013",
                            "@@%s on '%s' references unknown field '%s'."
                                    .formatted(attribute.name(), model.name(), name),
                            attribute.span());
                } else if (models.containsKey(field.type().name())) {
                    invalid = true;
                    add(
                            "TUPREL-SCHEMA-SEM-013",
                            "@@%s on '%s' cannot contain relation field '%s'."
                                    .formatted(attribute.name(), model.name(), name),
                            attribute.span());
                }
                if (!uniqueNames.add(name)) {
                    invalid = true;
                    add(
                            "TUPREL-SCHEMA-SEM-013",
                            "@@%s on '%s' repeats field '%s'."
                                    .formatted(attribute.name(), model.name(), name),
                            attribute.span());
                }
            }
            String signature = attribute.name() + ':' + String.join(",", names.get());
            if (!seen.add(signature)) {
                invalid = true;
                add(
                        "TUPREL-SCHEMA-SEM-013",
                        "@@%s on '%s' duplicates the same field set."
                                .formatted(attribute.name(), model.name()),
                        attribute.span());
            }
            if (!invalid) {
                result.add(new ValidatedIndex(attribute.name(), names.get(), attribute.span()));
            }
        }
        return result;
    }

    private Optional<List<String>> symbolList(SchemaDocument.Expression expression) {
        if (!(expression instanceof SchemaDocument.ListValue list)) {
            return Optional.empty();
        }
        List<String> result = new ArrayList<>();
        for (SchemaDocument.Expression value : list.values()) {
            if (!(value instanceof SchemaDocument.Symbol symbol)) {
                return Optional.empty();
            }
            result.add(symbol.value());
        }
        return Optional.of(List.copyOf(result));
    }

    private Optional<ValidatedConfiguration> validateDatasource() {
        List<SchemaDocument.DatasourceDeclaration> declarations =
                document.declarations().stream()
                        .filter(SchemaDocument.DatasourceDeclaration.class::isInstance)
                        .map(SchemaDocument.DatasourceDeclaration.class::cast)
                        .toList();
        if (declarations.size() > 1) {
            for (int index = 1; index < declarations.size(); index++) {
                add(
                        "TUPREL-SCHEMA-SEM-019",
                        "Only one datasource declaration is allowed.",
                        declarations.get(index).span());
            }
        }
        if (declarations.isEmpty()) {
            return Optional.empty();
        }
        SchemaDocument.DatasourceDeclaration declaration = declarations.getFirst();
        Map<String, SchemaDocument.Expression> properties =
                collectProperties("datasource", declaration.properties());
        requireProperties("datasource", declaration.span(), properties, Set.of("provider", "url"));
        rejectUnknownProperties(
                "datasource", declaration.span(), properties, Set.of("provider", "url"));

        SchemaDocument.Expression provider = properties.get("provider");
        if (!(provider instanceof SchemaDocument.StringLiteral string)
                || !string.value().equals("postgresql")) {
            add(
                    "TUPREL-SCHEMA-SEM-018",
                    "Datasource provider must be the string \"postgresql\".",
                    provider == null ? declaration.span() : provider.span());
        }
        SchemaDocument.Expression url = properties.get("url");
        if (!isEnvironmentCall(url)) {
            add(
                    "TUPREL-SCHEMA-SEM-018",
                    "Datasource url must use env(\"VARIABLE_NAME\").",
                    url == null ? declaration.span() : url.span());
        }
        return Optional.of(
                new ValidatedConfiguration(declaration.name(), properties, declaration.span()));
    }

    private Optional<ValidatedConfiguration> validateGenerator() {
        List<SchemaDocument.GeneratorDeclaration> declarations =
                document.declarations().stream()
                        .filter(SchemaDocument.GeneratorDeclaration.class::isInstance)
                        .map(SchemaDocument.GeneratorDeclaration.class::cast)
                        .toList();
        if (declarations.size() > 1) {
            for (int index = 1; index < declarations.size(); index++) {
                add(
                        "TUPREL-SCHEMA-SEM-019",
                        "Only one generator declaration is allowed.",
                        declarations.get(index).span());
            }
        }
        if (declarations.isEmpty()) {
            return Optional.empty();
        }
        SchemaDocument.GeneratorDeclaration declaration = declarations.getFirst();
        Map<String, SchemaDocument.Expression> properties =
                collectProperties("generator", declaration.properties());
        requireProperties("generator", declaration.span(), properties, Set.of("package"));
        rejectUnknownProperties("generator", declaration.span(), properties, Set.of("package"));

        SchemaDocument.Expression packageExpression = properties.get("package");
        if (!(packageExpression instanceof SchemaDocument.StringLiteral string)
                || !isJavaPackage(string.value())) {
            add(
                    "TUPREL-SCHEMA-SEM-018",
                    "Generator package must be a valid non-reserved Java package name.",
                    packageExpression == null ? declaration.span() : packageExpression.span());
        }
        return Optional.of(
                new ValidatedConfiguration(declaration.name(), properties, declaration.span()));
    }

    private Map<String, SchemaDocument.Expression> collectProperties(
            String kind, List<ConfigProperty> properties) {
        Map<String, SchemaDocument.Expression> result = new LinkedHashMap<>();
        for (ConfigProperty property : properties) {
            if (result.putIfAbsent(property.name(), property.value()) != null) {
                add(
                        "TUPREL-SCHEMA-SEM-019",
                        "%s property '%s' is declared more than once."
                                .formatted(capitalize(kind), property.name()),
                        property.span());
            }
        }
        return result;
    }

    private void requireProperties(
            String kind,
            SourceSpan span,
            Map<String, SchemaDocument.Expression> properties,
            Set<String> required) {
        for (String name : required) {
            if (!properties.containsKey(name)) {
                add(
                        "TUPREL-SCHEMA-SEM-018",
                        "%s requires property '%s'.".formatted(capitalize(kind), name),
                        span);
            }
        }
    }

    private void rejectUnknownProperties(
            String kind,
            SourceSpan span,
            Map<String, SchemaDocument.Expression> properties,
            Set<String> allowed) {
        for (String name : properties.keySet()) {
            if (!allowed.contains(name)) {
                add(
                        "TUPREL-SCHEMA-SEM-018",
                        "Unknown %s property '%s'.".formatted(kind, name),
                        span);
            }
        }
    }

    private boolean isEnvironmentCall(SchemaDocument.Expression expression) {
        if (!(expression instanceof SchemaDocument.Call call)
                || !call.name().equals("env")
                || call.arguments().size() != 1
                || call.arguments().getFirst().isNamed()) {
            return false;
        }
        return call.arguments().getFirst().value() instanceof SchemaDocument.StringLiteral string
                && !string.value().isBlank();
    }

    private boolean isJavaPackage(String value) {
        if (value.isBlank()) {
            return false;
        }
        String[] parts = value.split("\\.", -1);
        for (String part : parts) {
            if (!part.matches("[A-Za-z_][A-Za-z0-9_]*") || JAVA_RESERVED.contains(part)) {
                return false;
            }
        }
        return true;
    }

    private Optional<ResolvedType> resolve(String name) {
        if (SCALARS.contains(name)) {
            return Optional.of(new ResolvedType(name, TypeKind.SCALAR));
        }
        if (enums.containsKey(name)) {
            return Optional.of(new ResolvedType(name, TypeKind.ENUM));
        }
        if (models.containsKey(name)) {
            return Optional.of(new ResolvedType(name, TypeKind.MODEL));
        }
        return Optional.empty();
    }

    private void validateName(String name, String kind, SourceSpan span) {
        if (JAVA_RESERVED.contains(name)) {
            add(
                    "TUPREL-SCHEMA-SEM-003",
                    "%s name '%s' is reserved by Java.".formatted(capitalize(kind), name),
                    span);
        }
    }

    private String declarationName(SchemaDocument.Declaration declaration) {
        if (declaration instanceof ModelDeclaration model) {
            return model.name();
        }
        if (declaration instanceof EnumDeclaration enumDeclaration) {
            return enumDeclaration.name();
        }
        return null;
    }

    private void add(String code, String message, SourceSpan span) {
        diagnostics.add(new SchemaDiagnostic(code, DiagnosticSeverity.ERROR, message, span));
    }

    private static String capitalize(String value) {
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }
}
