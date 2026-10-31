package dev.tuprel.migrate.postgresql.diff;

import dev.tuprel.migrate.postgresql.PostgresqlMigrationPlanCompiler;
import dev.tuprel.migrate.postgresql.ddl.DdlStatement;
import dev.tuprel.schema.SourceSpan;
import dev.tuprel.schema.ast.SchemaDocument.Cardinality;
import dev.tuprel.schema.model.ValidatedSchema;
import dev.tuprel.schema.model.ValidatedSchema.TypeKind;
import dev.tuprel.schema.model.ValidatedSchema.ValidatedModel;
import dev.tuprel.sql.SqlIdentifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Stateless physical projection of the approved schema subset; no I/O or execution. */
public final class PostgresqlSchemaProjector {
    private static final Map<String, DdlStatement.Type> TYPES = Map.of(
            "UUID", DdlStatement.Type.UUID, "Boolean", DdlStatement.Type.BOOLEAN,
            "Short", DdlStatement.Type.SMALLINT, "Int", DdlStatement.Type.INTEGER,
            "Long", DdlStatement.Type.BIGINT, "String", DdlStatement.Type.TEXT,
            "LocalDate", DdlStatement.Type.DATE, "Instant", DdlStatement.Type.TIMESTAMPTZ);

    /** Creates a reusable projector without configuration. */
    public PostgresqlSchemaProjector() { }

    /**
     * Maps exact model/field names and supported attributes, or refuses the whole schema.
     * Configuration is not evaluated. Diagnostics retain no source text or paths.
     * @param target explicit PostgreSQL schema
     * @param schema existing semantically validated frontend model
     * @return complete logical snapshot or deterministic detached diagnostics
     */
    public SchemaProjection project(SqlIdentifier target, ValidatedSchema schema) {
        Objects.requireNonNull(target, "target"); Objects.requireNonNull(schema, "schema");
        List<SchemaProjection.Diagnostic> diagnostics = new ArrayList<>();
        SourceSpan root = schema.syntax().span();
        if (target.name().startsWith("pg_") || target.name().equals("information_schema") || reserved(target.name())) {
            add(diagnostics, SchemaProjection.Kind.INVALID_IDENTIFIER, root);
        }
        if (schema.models().size() > PostgresqlMigrationPlanCompiler.MAX_PLAN_STATEMENTS
                || schema.enums().size() > SchemaSnapshot.MAX_OBJECTS) {
            add(diagnostics, SchemaProjection.Kind.RESOURCE_LIMIT, root);
            return refused(diagnostics);
        }
        schema.enums().forEach(value -> add(diagnostics, SchemaProjection.Kind.ENUM, value.span()));
        List<DdlStatement> statements = new ArrayList<>();
        int objects = 0;
        for (ValidatedModel model : schema.models()) {
            long count = 1L + model.fields().size() + model.indexes().size();
            if (count + objects > SchemaSnapshot.MAX_OBJECTS || model.fields().size() > DdlStatement.MAX_COLUMNS
                    || statements.size() + 1L + model.indexes().size() > PostgresqlMigrationPlanCompiler.MAX_PLAN_STATEMENTS) {
                add(diagnostics, SchemaProjection.Kind.RESOURCE_LIMIT, model.span());
                return refused(diagnostics);
            }
            objects += (int) count;
            SqlIdentifier table = identifier(model.name(), diagnostics, model.span(), true);
            List<DdlStatement.Column> columns = new ArrayList<>();
            int previous = diagnostics.size();
            for (ValidatedSchema.ValidatedField field : model.fields()) {
                SqlIdentifier name = identifier(field.name(), diagnostics, field.span(), false);
                if (field.defaultValue().isPresent()) { add(diagnostics, SchemaProjection.Kind.DEFAULT, field.span()); }
                if (field.unique()) { add(diagnostics, SchemaProjection.Kind.UNIQUE, field.span()); }
                if (field.cardinality() == Cardinality.LIST) { add(diagnostics, SchemaProjection.Kind.LIST, field.span()); }
                if (field.type().kind() == TypeKind.MODEL || field.relation().isPresent()) { add(diagnostics, SchemaProjection.Kind.RELATION, field.span()); }
                if (field.type().kind() == TypeKind.ENUM) { add(diagnostics, SchemaProjection.Kind.ENUM, field.span()); }
                DdlStatement.Type type = TYPES.get(field.type().name());
                if (field.type().kind() != TypeKind.SCALAR || type == null) { add(diagnostics, SchemaProjection.Kind.UNSUPPORTED_TYPE, field.span()); }
                if (name != null && type != null && field.type().kind() == TypeKind.SCALAR) {
                    boolean nullable = field.cardinality() == Cardinality.OPTIONAL;
                    if (field.id() && nullable) { add(diagnostics, SchemaProjection.Kind.CONFLICT, field.span()); }
                    else { columns.add(new DdlStatement.Column(name, type, nullable, field.id())); }
                }
            }
            if (model.fields().stream().filter(ValidatedSchema.ValidatedField::id).count() != 1) {
                add(diagnostics, SchemaProjection.Kind.CONFLICT, model.span());
            }
            if (table == null || diagnostics.size() != previous) { continue; }
            DdlStatement.TableName qualified = new DdlStatement.TableName(target, table);
            try { statements.add(new DdlStatement.CreateTable(qualified, columns)); }
            catch (IllegalArgumentException failure) { add(diagnostics, SchemaProjection.Kind.CONFLICT, model.span()); continue; }
            for (ValidatedSchema.ValidatedIndex index : model.indexes()) {
                if (!index.kind().equals("index")) { add(diagnostics, SchemaProjection.Kind.UNIQUE, index.span()); continue; }
                if (index.fields().isEmpty() || index.fields().size() > DdlStatement.MAX_INDEX_COLUMNS) {
                    add(diagnostics, SchemaProjection.Kind.INDEX, index.span()); continue;
                }
                SqlIdentifier name = identifier(model.name() + "_" + String.join("_", index.fields()) + "_idx", diagnostics, index.span(), true);
                List<SqlIdentifier> keys = new ArrayList<>();
                for (String field : index.fields()) {
                    SqlIdentifier key = identifier(field, diagnostics, index.span(), false);
                    if (key != null) { keys.add(key); }
                }
                if (name != null && keys.size() == index.fields().size()) {
                    try { statements.add(new DdlStatement.CreateIndex(qualified, name, keys)); }
                    catch (IllegalArgumentException failure) { add(diagnostics, SchemaProjection.Kind.INDEX, index.span()); }
                }
            }
        }
        if (!diagnostics.isEmpty()) { return refused(diagnostics); }
        try { return new SchemaProjection(Optional.of(SchemaSnapshot.replay(target, statements)), List.of()); }
        catch (IllegalArgumentException failure) {
            add(diagnostics, SchemaProjection.Kind.CONFLICT, root); return refused(diagnostics);
        }
    }

    private static SqlIdentifier identifier(String name, List<SchemaProjection.Diagnostic> diagnostics, SourceSpan span, boolean relation) {
        try {
            if (relation && reserved(name)) { throw new IllegalArgumentException("Reserved relation"); }
            return new SqlIdentifier(name);
        } catch (IllegalArgumentException failure) { add(diagnostics, SchemaProjection.Kind.INVALID_IDENTIFIER, span); return null; }
    }

    private static boolean reserved(String name) { return name.toLowerCase(Locale.ROOT).startsWith("_tuprel_"); }

    private static void add(List<SchemaProjection.Diagnostic> diagnostics, SchemaProjection.Kind kind, SourceSpan span) {
        diagnostics.add(new SchemaProjection.Diagnostic(kind, span.start(), span.end()));
    }

    private static SchemaProjection refused(List<SchemaProjection.Diagnostic> diagnostics) {
        diagnostics.sort(Comparator.comparingInt((SchemaProjection.Diagnostic item) -> item.start().offset())
                .thenComparing(item -> item.kind().name()));
        return new SchemaProjection(Optional.empty(), diagnostics);
    }
}
