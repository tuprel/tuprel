package dev.tuprel.migrate.postgresql;

import static dev.tuprel.migrate.postgresql.MigrationDevPlan.Diagnostic;

import dev.tuprel.migrate.MigrationDirectory;
import dev.tuprel.migrate.MigrationFile;
import dev.tuprel.migrate.MigrationHistoryException;
import dev.tuprel.migrate.MigrationId;
import dev.tuprel.migrate.postgresql.ddl.DdlParseResult;
import dev.tuprel.migrate.postgresql.ddl.PostgresqlDdlParser;
import dev.tuprel.migrate.postgresql.diff.PostgresqlSchemaProjector;
import dev.tuprel.migrate.postgresql.diff.SchemaDiff;
import dev.tuprel.migrate.postgresql.diff.SchemaProjection;
import dev.tuprel.migrate.postgresql.diff.SchemaSnapshot;
import dev.tuprel.schema.model.ValidatedSchema;
import dev.tuprel.sql.SqlIdentifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

/** Plans a single read-only development preview against a verified PostgreSQL reference. */
public final class PostgresqlMigrationDevPlanner {
    // A strictly last ID for in-memory v1 validation. It is never exposed or reserved.
    private static final MigrationId VALIDATION_ID = new MigrationId("99991231_235959");
    private final MigrationDatabaseSettings settings;
    private final PostgresqlDriftInspector inspector;

    /**
     * Uses validated database settings for a read-only reference check.
     * @param settings explicit target and TLS configuration
     */
    public PostgresqlMigrationDevPlanner(MigrationDatabaseSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.inspector = new PostgresqlDriftInspector(settings);
    }

    /**
     * Verifies the applied reference, plans supported additions and validates their replay.
     * @param target explicit target schema
     * @param directory format-v1 migration catalog
     * @param desired validated desired schema
     * @param name proposed format-v1 name
     * @param control confirmed administrative prerequisites for this invocation
     * @return a complete reviewable preview, no changes, or a redacted refusal
     */
    public MigrationDevPlan preview(SqlIdentifier target, Path directory, ValidatedSchema desired, String name,
            PostgresqlDriftInspector.ReferenceControl control) {
        MigrationPlanTarget binding;
        try {
            MigrationFile.validateName(name);
            binding = settings.planTarget(target.name());
        } catch (IllegalArgumentException | MigrationHistoryException failure) {
            return MigrationDevPlan.blocked(Diagnostic.INPUT, null);
        }
        if (!binding.supportedSchema()) { return MigrationDevPlan.blocked(Diagnostic.INPUT, null); }
        SchemaProjection projection = new PostgresqlSchemaProjector().project(target, desired);
        if (projection.snapshot().isEmpty()) { return MigrationDevPlan.blocked(Diagnostic.PROJECTION, null); }
        PostgresqlDriftInspector.Inspection inspected = inspector.inspectReference(target, directory, control);
        if (inspected.reference().isEmpty()) { return MigrationDevPlan.blocked(Diagnostic.REFERENCE, inspected.report()); }
        return plan(binding, name, inspected.reference().orElseThrow(), projection.snapshot().orElseThrow(), inspected.report());
    }

    static MigrationDevPlan plan(MigrationPlanTarget binding, String name, PostgresqlDriftInspector.Reference reference,
            SchemaSnapshot wanted, PhysicalDriftReport observation) {
        if (reference.history().applied().size() != reference.files().size()) {
            return MigrationDevPlan.blocked(Diagnostic.PENDING, observation);
        }
        SchemaDiff diff = SchemaDiff.compare(reference.expected(), wanted);
        return switch (diff.state()) {
            case NO_CHANGES -> MigrationDevPlan.unchanged(observation);
            case BLOCKED -> MigrationDevPlan.blocked(Diagnostic.DIFF, observation);
            case REVIEW_REQUIRED -> validate(binding, name, reference, wanted, diff, observation);
        };
    }

    private static MigrationDevPlan validate(MigrationPlanTarget binding, String name,
            PostgresqlDriftInspector.Reference reference, SchemaSnapshot desired, SchemaDiff diff,
            PhysicalDriftReport observation) {
        String sql = diff.preview();
        byte[] bytes = sql.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MigrationFile.MAX_SQL_BYTES || reference.files().size() >= MigrationDirectory.MAX_MIGRATIONS
                || (!reference.files().isEmpty() && reference.files().getLast().id().equals(VALIDATION_ID))) {
            return MigrationDevPlan.blocked(Diagnostic.CAPACITY, observation);
        }
        MigrationFile proposed = new MigrationFile(VALIDATION_ID, name, bytes);
        DdlParseResult parsed = new PostgresqlDdlParser().parse(proposed);
        if (!parsed.isSupported() || !parsed.preview().equals(sql)) {
            return MigrationDevPlan.blocked(Diagnostic.REPLAY, observation);
        }
        List<MigrationFile> catalog = new ArrayList<>(reference.files());
        catalog.add(proposed);
        MigrationPlan plan;
        try { plan = new PostgresqlMigrationPlanCompiler().compile(binding, catalog, reference.history()); }
        catch (IllegalArgumentException failure) { return MigrationDevPlan.blocked(Diagnostic.CAPACITY, observation); }
        if (plan.state() != MigrationPlan.State.REVIEW_REQUIRED) {
            return MigrationDevPlan.blocked(Diagnostic.REPLAY, observation);
        }
        List<DdlParseResult> scripts = new ArrayList<>(reference.applied());
        scripts.add(parsed);
        try {
            SchemaSnapshot replayed = SchemaSnapshot.fromParsed(binding.schema(), scripts);
            if (!SchemaDiff.structuralChanges(replayed, desired).isEmpty()) {
                return MigrationDevPlan.blocked(Diagnostic.REPLAY, observation);
            }
        } catch (IllegalArgumentException failure) {
            return MigrationDevPlan.blocked(Diagnostic.REPLAY, observation);
        }
        EnumSet<SchemaDiff.Risk> risks = EnumSet.copyOf(diff.risks());
        risks.remove(SchemaDiff.Risk.PHYSICAL_STATE_NOT_CHECKED);
        return MigrationDevPlan.preview(observation, risks, sql);
    }
}
