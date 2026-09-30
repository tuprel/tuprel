package dev.tuprel.schema.model;

import dev.tuprel.schema.SourceSpan;
import dev.tuprel.schema.ast.SchemaDocument;
import dev.tuprel.schema.model.ValidatedSchema.TypeKind;
import dev.tuprel.schema.model.ValidatedSchema.ValidatedField;
import dev.tuprel.schema.model.ValidatedSchema.ValidatedIndex;
import dev.tuprel.schema.model.ValidatedSchema.ValidatedModel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Pairs every relation field with the foreign key that implements it (RFC-003).
 *
 * <p>A singular relation field with {@code @relation(fields, references)} owns the foreign key.
 * A relation field without {@code @relation} is the inverse side and must match exactly one
 * owning relation on its target model that points back to its own model; relation names are
 * not part of the language, so zero or several candidates are errors. A singular inverse
 * describes a one-to-one relation: it must be optional and the owning foreign key must be
 * unique. The graph is a pure function of validated models and performs no I/O.
 */
public final class RelationGraph {
    private final List<RelationLink> links;
    private final List<Problem> problems;

    private RelationGraph(List<RelationLink> links, List<Problem> problems) {
        this.links = List.copyOf(links);
        this.problems = List.copyOf(problems);
    }

    /** One resolved relation field. */
    public record RelationLink(
            String model,
            String field,
            String target,
            Side side,
            SchemaDocument.Cardinality cardinality,
            List<String> localFields,
            List<String> targetFields,
            Optional<String> pairedField) {
        /** Defensively copies join columns. */
        public RelationLink {
            Objects.requireNonNull(model, "model");
            Objects.requireNonNull(field, "field");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(side, "side");
            Objects.requireNonNull(cardinality, "cardinality");
            localFields = List.copyOf(localFields);
            targetFields = List.copyOf(targetFields);
            Objects.requireNonNull(pairedField, "pairedField");
        }

        /** Whether the field loads a list of target rows. */
        public boolean toMany() {
            return cardinality == SchemaDocument.Cardinality.LIST;
        }
    }

    /** Which side of the foreign key a relation field is on. */
    public enum Side {
        /** The field's model holds the foreign key columns. */
        OWNING,
        /** The target model holds the foreign key columns. */
        INVERSE
    }

    /** A relation that cannot be paired, with a stable diagnostic code. */
    public record Problem(String code, String message, SourceSpan span) {
        /** Validates problem data. */
        public Problem {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(message, "message");
            Objects.requireNonNull(span, "span");
        }
    }

    /** Resolves every relation field of the models. */
    public static RelationGraph of(List<ValidatedModel> models) {
        Map<String, ValidatedModel> byName = new HashMap<>();
        models.forEach(model -> byName.put(model.name(), model));
        List<RelationLink> links = new ArrayList<>();
        List<Problem> problems = new ArrayList<>();
        Set<String> claimed = new HashSet<>();
        for (ValidatedModel model : models) {
            for (ValidatedField field : model.fields()) {
                if (field.type().kind() != TypeKind.MODEL || !byName.containsKey(field.type().name())) {
                    continue;
                }
                ValidatedModel target = byName.get(field.type().name());
                if (field.relation().isPresent()) {
                    links.add(new RelationLink(model.name(), field.name(), target.name(), Side.OWNING,
                            field.cardinality(), field.relation().get().fields(),
                            field.relation().get().references(), inverseOf(target, model, field)));
                    continue;
                }
                List<ValidatedField> owners = target.fields().stream()
                        .filter(candidate -> candidate.relation().isPresent()
                                && candidate.type().name().equals(model.name()))
                        .toList();
                String label = model.name() + "." + field.name();
                if (owners.isEmpty()) {
                    problems.add(new Problem("TUPREL-SCHEMA-SEM-021", ("Relation '%s' has no matching "
                            + "@relation field on '%s' that references '%s'.")
                            .formatted(label, target.name(), model.name()), field.span()));
                    continue;
                }
                if (owners.size() > 1) {
                    problems.add(new Problem("TUPREL-SCHEMA-SEM-022", ("Relation '%s' is ambiguous: '%s' "
                            + "has several @relation fields referencing '%s'.")
                            .formatted(label, target.name(), model.name()), field.span()));
                    continue;
                }
                ValidatedField owner = owners.getFirst();
                if (!claimed.add(target.name() + "." + owner.name())) {
                    problems.add(new Problem("TUPREL-SCHEMA-SEM-022", ("Relation '%s' pairs with '%s.%s', "
                            + "which another relation field already uses.")
                            .formatted(label, target.name(), owner.name()), field.span()));
                    continue;
                }
                if (field.cardinality() != SchemaDocument.Cardinality.LIST) {
                    if (field.cardinality() != SchemaDocument.Cardinality.OPTIONAL) {
                        problems.add(new Problem("TUPREL-SCHEMA-SEM-023", ("One-to-one inverse relation "
                                + "'%s' must be optional.").formatted(label), field.span()));
                        continue;
                    }
                    if (!unique(target, owner.relation().get().fields())) {
                        problems.add(new Problem("TUPREL-SCHEMA-SEM-024", ("One-to-one inverse relation "
                                + "'%s' requires '%s.%s' to use unique fields.")
                                .formatted(label, target.name(), owner.name()), field.span()));
                        continue;
                    }
                }
                links.add(new RelationLink(model.name(), field.name(), target.name(), Side.INVERSE,
                        field.cardinality(), owner.relation().get().references(),
                        owner.relation().get().fields(), Optional.of(owner.name())));
            }
        }
        return new RelationGraph(links, problems);
    }

    /** Resolved relation fields in model and field declaration order. */
    public List<RelationLink> links() {
        return links;
    }

    /** Relation fields that cannot be paired; empty for a validated schema. */
    public List<Problem> problems() {
        return problems;
    }

    /** The resolved relation field of a model, if the field is a relation. */
    public Optional<RelationLink> link(String model, String field) {
        return links.stream()
                .filter(link -> link.model().equals(model) && link.field().equals(field))
                .findFirst();
    }

    private static Optional<String> inverseOf(ValidatedModel target, ValidatedModel owner, ValidatedField owning) {
        List<ValidatedField> inverses = target.fields().stream()
                .filter(candidate -> candidate.relation().isEmpty()
                        && candidate.type().kind() == TypeKind.MODEL
                        && candidate.type().name().equals(owner.name())
                        && !(target.name().equals(owner.name()) && candidate.name().equals(owning.name())))
                .toList();
        return inverses.size() == 1 ? Optional.of(inverses.getFirst().name()) : Optional.empty();
    }

    private static boolean unique(ValidatedModel model, List<String> fields) {
        if (fields.size() == 1) {
            String name = fields.getFirst();
            if (model.fields().stream().anyMatch(field -> field.name().equals(name)
                    && (field.id() || field.unique()))) {
                return true;
            }
        }
        Set<String> wanted = Set.copyOf(fields);
        for (ValidatedIndex index : model.indexes()) {
            if (index.kind().equals("unique") && Set.copyOf(index.fields()).equals(wanted)) {
                return true;
            }
        }
        return false;
    }
}
