package dev.tuprel.migrate.postgresql;

import static dev.tuprel.migrate.postgresql.MigrationPlan.Risk;

import dev.tuprel.migrate.AppliedMigration;
import dev.tuprel.migrate.MigrationChecksum;
import dev.tuprel.migrate.MigrationDirectory;
import dev.tuprel.migrate.MigrationFile;
import dev.tuprel.migrate.MigrationHistory;
import dev.tuprel.migrate.MigrationId;
import dev.tuprel.migrate.MigrationStatus;
import dev.tuprel.migrate.postgresql.ddl.DdlParseResult;
import dev.tuprel.migrate.postgresql.ddl.DdlStatement;
import dev.tuprel.migrate.postgresql.ddl.PostgresqlDdlParser;
import dev.tuprel.sql.SqlIdentifier;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/** Offline plan compiler. Snapshots are supplied by the caller; no JDBC/file access or execution. */
public final class PostgresqlMigrationPlanCompiler {
    /** Normative policy/serialization version; changing it invalidates previous digests. */
    public static final String POLICY_VERSION = "postgresql-plan-v1-proposal";
    /** Whole-plan statement budget, separate from per-file grammar bounds. */
    public static final int MAX_PLAN_STATEMENTS = 4096;
    /** Whole-plan canonical preview byte budget (ASCII names/SQL, UTF-8/LF). */
    public static final int MAX_PREVIEW_BYTES = 16 * 1024 * 1024;
    private final PostgresqlDdlParser parser = new PostgresqlDdlParser();

    /** Creates a stateless compiler facade reusable across threads. */
    public PostgresqlMigrationPlanCompiler() { }

    /**
     * Compiles a deterministic proposal using exact artifacts and observed history.
     * Does not attest snapshot provenance or physical drift. A future runner must
     * independently rederive target/history and the entire digest under lock.
     * @param target validated configured target fingerprint
     * @param local validated format-v1 catalog, iteration order immaterial
     * @param history immutable observed history snapshot
     * @return immutable all-or-nothing proposal, never execution authorization
     * @throws IllegalArgumentException for duplicate IDs or over-budget local catalog
     */
    public MigrationPlan compile(MigrationPlanTarget target, List<MigrationFile> local, MigrationHistory history) {
        Objects.requireNonNull(target, "target"); Objects.requireNonNull(history, "history");
        local = List.copyOf(local);
        if (local.size() > MigrationDirectory.MAX_MIGRATIONS) { throw new IllegalArgumentException("Catalog count limit exceeded"); }
        Map<MigrationId, MigrationFile> files = new TreeMap<>();
        long bytes = 0;
        for (MigrationFile file : local) {
            bytes += file.sql().getBytes(StandardCharsets.UTF_8).length;
            if (bytes > MigrationDirectory.MAX_TOTAL_SQL_BYTES || files.putIfAbsent(file.id(), file) != null) {
                throw new IllegalArgumentException("Invalid bounded catalog snapshot");
            }
        }
        List<MigrationFile> ordered = List.copyOf(files.values());
        EnumSet<Risk> risks = EnumSet.of(Risk.PHYSICAL_DRIFT_NOT_CHECKED);
        if (MigrationStatus.compare(ordered, history).hasProblems()) { risks.add(Risk.HISTORY_DIVERGENCE); }
        if (!target.supportedSchema()) {
            risks.add(Risk.TARGET_UNSUPPORTED);
        }
        Set<MigrationId> applied = new HashSet<>(history.applied().stream().map(AppliedMigration::id).toList());
        Set<DdlStatement.TableName> relations = new HashSet<>();
        Set<DdlStatement.TableName> createdTables = new HashSet<>();
        Set<DdlStatement.TableName> existingTables = new HashSet<>();
        Map<DdlStatement.TableName, Set<SqlIdentifier>> knownColumns = new HashMap<>();
        List<MigrationPlan.Script> scripts = new ArrayList<>();
        StringBuilder preview = new StringBuilder();
        int statementCount = 0;
        for (MigrationFile file : ordered) {
            if (applied.contains(file.id())) { continue; }
            EnumSet<Risk> scriptRisks = EnumSet.of(Risk.PHYSICAL_DRIFT_NOT_CHECKED);
            if (statementCount >= MAX_PLAN_STATEMENTS || preview.length() >= MAX_PREVIEW_BYTES) {
                scriptRisks.add(Risk.RESOURCE_LIMIT);
                scripts.add(new MigrationPlan.Script(file, 0, Optional.empty(), scriptRisks, Optional.empty()));
                risks.addAll(scriptRisks); continue;
            }
            DdlParseResult result = parser.parse(file);
            Optional<MigrationChecksum> previewChecksum = Optional.empty();
            if (!result.isSupported()) {
                DdlParseResult.Kind kind = result.diagnostic().orElseThrow().kind();
                scriptRisks.add(kind == DdlParseResult.Kind.EMPTY_SCRIPT ? Risk.EMPTY_DRAFT
                        : kind == DdlParseResult.Kind.DESTRUCTIVE_PREFIX ? Risk.DESTRUCTIVE_SQL
                        : kind == DdlParseResult.Kind.RESOURCE_LIMIT ? Risk.RESOURCE_LIMIT : Risk.UNKNOWN_SQL);
            } else {
                String sql = result.preview();
                previewChecksum = Optional.of(MigrationChecksum.of(sql.getBytes(StandardCharsets.UTF_8)));
                statementCount += result.statements().size();
                String header = "-- Migration " + file.directoryName() + "\n";
                if (statementCount > MAX_PLAN_STATEMENTS || (long) preview.length() + header.length() + sql.length() > MAX_PREVIEW_BYTES) {
                    scriptRisks.add(Risk.RESOURCE_LIMIT);
                } else {
                    preview.append(header).append(sql);
                    for (DdlStatement statement : result.statements()) {
                        scriptRisks.add(Risk.DDL_LOCKS);
                        if (!statement.table().schema().equals(target.schema())) { scriptRisks.add(Risk.TARGET_MISMATCH); }
                        if (reserved(statement.table().name())
                                || (statement instanceof DdlStatement.CreateIndex index && reserved(index.name()))) {
                            scriptRisks.add(Risk.TARGET_UNSUPPORTED);
                        }
                        switch (statement) {
                            case DdlStatement.CreateTable create -> {
                                if (!relations.add(create.table())) { scriptRisks.add(Risk.PLAN_CONFLICT); }
                                createdTables.add(create.table());
                                knownColumns.put(create.table(), new HashSet<>(create.columns().stream().map(DdlStatement.Column::name).toList()));
                            }
                            case DdlStatement.AddColumn add -> {
                                if (relations.contains(add.table()) && !createdTables.contains(add.table())
                                        && !existingTables.contains(add.table())) { scriptRisks.add(Risk.PLAN_CONFLICT); }
                                if (!createdTables.contains(add.table())) { existingTables.add(add.table()); }
                                relations.add(add.table());
                                Set<SqlIdentifier> columns = knownColumns.computeIfAbsent(add.table(), ignored -> new HashSet<>());
                                if (!columns.add(add.name())) { scriptRisks.add(Risk.PLAN_CONFLICT); }
                            }
                            case DdlStatement.CreateIndex index -> {
                                scriptRisks.add(Risk.INDEX_BUILD_COST); scriptRisks.add(Risk.INDEX_WRITE_BLOCKING);
                                if (relations.contains(index.table()) && !createdTables.contains(index.table())
                                        && !existingTables.contains(index.table())) { scriptRisks.add(Risk.PLAN_CONFLICT); }
                                if (!createdTables.contains(index.table())) { existingTables.add(index.table()); }
                                relations.add(index.table());
                                if (!relations.add(new DdlStatement.TableName(index.table().schema(), index.name()))) { scriptRisks.add(Risk.PLAN_CONFLICT); }
                                // Only fully known tables can establish a missing column offline.
                                if (createdTables.contains(index.table()) && knownColumns.containsKey(index.table())
                                        && !knownColumns.get(index.table()).containsAll(index.columns())) { scriptRisks.add(Risk.PLAN_CONFLICT); }
                            }
                        }
                    }
                }
            }
            scripts.add(new MigrationPlan.Script(file, result.statements().size(), previewChecksum, scriptRisks, result.diagnostic()));
            risks.addAll(scriptRisks);
        }
        boolean blocked = risks.stream().anyMatch(PostgresqlMigrationPlanCompiler::blocking);
        MigrationPlan.State state = blocked ? MigrationPlan.State.BLOCKED
                : scripts.isEmpty() ? MigrationPlan.State.NO_CHANGES : MigrationPlan.State.REVIEW_REQUIRED;
        String exposedPreview = blocked ? "" : preview.toString();
        MigrationPlanDigest digest = digest(target, ordered, history, state, scripts, risks, exposedPreview);
        return new MigrationPlan(state, scripts, risks, digest, exposedPreview);
    }

    private static boolean blocking(Risk risk) {
        return switch (risk) {
            case PHYSICAL_DRIFT_NOT_CHECKED, DDL_LOCKS, INDEX_WRITE_BLOCKING, INDEX_BUILD_COST -> false;
            default -> true;
        };
    }

    private static boolean reserved(SqlIdentifier name) {
        return name.name().toLowerCase(java.util.Locale.ROOT).startsWith("_tuprel_");
    }

    private static MigrationPlanDigest digest(MigrationPlanTarget target, List<MigrationFile> files, MigrationHistory history,
            MigrationPlan.State state, List<MigrationPlan.Script> scripts, Set<Risk> risks, String preview) {
        MigrationChecksum hash = PlanEncoding.digest(output -> {
            PlanEncoding.text(output, "TUPREL-MIGRATION-PLAN"); output.writeInt(1);
            PlanEncoding.text(output, PostgresqlDdlParser.GRAMMAR_VERSION); PlanEncoding.text(output, DdlParseResult.PREVIEW_VERSION);
            PlanEncoding.text(output, POLICY_VERSION); PlanEncoding.text(output, MigrationDirectory.FORMAT_HEADER);
            PlanEncoding.text(output, target.fingerprint().value()); PlanEncoding.text(output, target.schema().name());
            output.writeBoolean(history.initialised());
            List<AppliedMigration> rows = history.applied().stream().sorted(Comparator.comparing(AppliedMigration::id)).toList();
            output.writeInt(rows.size());
            for (AppliedMigration row : rows) {
                PlanEncoding.text(output, row.id().value()); PlanEncoding.text(output, row.name()); PlanEncoding.text(output, row.checksum().value());
                output.writeLong(row.startedAt().getEpochSecond()); output.writeInt(row.startedAt().get(java.time.temporal.ChronoField.NANO_OF_SECOND));
                output.writeLong(row.finishedAt().getEpochSecond()); output.writeInt(row.finishedAt().get(java.time.temporal.ChronoField.NANO_OF_SECOND));
                output.writeLong(row.executionMillis()); PlanEncoding.text(output, row.engineVersion());
            }
            output.writeInt(files.size());
            for (MigrationFile file : files) {
                PlanEncoding.text(output, file.id().value()); PlanEncoding.text(output, file.name()); PlanEncoding.text(output, file.checksum().value());
            }
            PlanEncoding.text(output, state.name()); writeRisks(output, risks);
            PlanEncoding.text(output, MigrationChecksum.of(preview.getBytes(StandardCharsets.UTF_8)).value());
            output.writeInt(scripts.size());
            for (MigrationPlan.Script script : scripts) {
                PlanEncoding.text(output, script.id().value()); output.writeInt(script.statements());
                output.writeBoolean(script.previewChecksum().isPresent());
                if (script.previewChecksum().isPresent()) { PlanEncoding.text(output, script.previewChecksum().orElseThrow().value()); }
                output.writeBoolean(script.diagnostic().isPresent());
                if (script.diagnostic().isPresent()) {
                    DdlParseResult.Diagnostic diagnostic = script.diagnostic().orElseThrow();
                    PlanEncoding.text(output, diagnostic.kind().name()); output.writeInt(diagnostic.line()); output.writeInt(diagnostic.column());
                }
                writeRisks(output, script.risks());
            }
        });
        return new MigrationPlanDigest(hash.value());
    }

    private static void writeRisks(DataOutputStream output, Set<Risk> risks) throws IOException {
        List<String> names = risks.stream().map(Enum::name).sorted().toList(); output.writeInt(names.size());
        for (String name : names) { PlanEncoding.text(output, name); }
    }
}
