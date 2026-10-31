package dev.tuprel.migrate.postgresql.diff;

import dev.tuprel.migrate.postgresql.PostgresqlMigrationPlanCompiler;
import dev.tuprel.migrate.postgresql.ddl.DdlParseResult;
import dev.tuprel.migrate.postgresql.ddl.DdlStatement;
import dev.tuprel.sql.SqlIdentifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Deterministic, offline comparison of two fully known logical snapshots.
 * No rename inference, database observation, migration files or approval digest.
 * Preview is a proposal only; deploy still requires reviewed files and its existing gates.
 */
public final class SchemaDiff {
    /** Comparison outcome; no state grants execution permission. */
    public enum State {
        /** Logical equality only, not absence of physical drift. */ NO_CHANGES,
        /** All changes have an approved-grammar preview requiring review. */ REVIEW_REQUIRED,
        /** At least one change cannot be previewed within the accepted grammar. */ BLOCKED
    }

    /** Exact-name differences, never inferred renames. */
    public enum Kind {
        /** A new table. */ TABLE_ADDED,
        /** A removed table and its contents. */ TABLE_REMOVED,
        /** A new column. */ COLUMN_ADDED,
        /** A removed column and its contents. */ COLUMN_REMOVED,
        /** A changed built-in column type. */ COLUMN_TYPE_CHANGED,
        /** A changed NULL constraint. */ COLUMN_NULLABILITY_CHANGED,
        /** A changed inline primary-key flag. */ COLUMN_PRIMARY_KEY_CHANGED,
        /** A new explicit plain index. */ INDEX_ADDED,
        /** A removed explicit index. */ INDEX_REMOVED,
        /** An index with changed ordered keys. */ INDEX_CHANGED
    }

    /** Conservative risks; none of these means an operation is safe to apply. */
    public enum Risk {
        /** No physical catalog was read. Always present, even on logical equality. */ PHYSICAL_STATE_NOT_CHECKED,
        /** Removal/type conversion can lose stored data; no conversion is proposed. */ POTENTIAL_DATA_LOSS,
        /** The accepted DDL grammar cannot express this change. */ UNSUPPORTED_CHANGE,
        /** No execution strategy/lock estimate is modeled for this unsupported change. */ LOCK_IMPACT_NOT_MODELED,
        /** DDL takes PostgreSQL relation locks. */ DDL_LOCKS,
        /** ADD COLUMN requires ACCESS EXCLUSIVE on an existing table. */ ACCESS_EXCLUSIVE_LOCK,
        /** Normal CREATE INDEX takes SHARE and blocks writes. */ INDEX_WRITE_BLOCKING,
        /** Index construction consumes resources. */ INDEX_BUILD_COST,
        /** The full proposed preview exceeds the existing plan budget. */ RESOURCE_LIMIT
    }

    /**
     * One identified logical change; property changes on the same column are separate.
     * @param kind exact difference
     * @param table exact validated relation basename
     * @param member column/index name, empty for table changes
     * @param risks immutable risk categories
     */
    public record Change(Kind kind, SqlIdentifier table, Optional<SqlIdentifier> member, Set<Risk> risks) {
        /** Defensively copies risks and requires structured identifiers. */
        public Change {
            Objects.requireNonNull(kind, "kind"); Objects.requireNonNull(table, "table");
            Objects.requireNonNull(member, "member"); risks = immutableRisks(risks);
        }

        /** Excludes identifiers from incidental logging. */
        @Override public String toString() { return "Change[kind=" + kind + ", risks=" + risks + "]"; }
    }

    private final State state;
    private final List<Change> changes;
    private final Set<Risk> risks;
    private final String preview;

    private SchemaDiff(List<Change> changes, Set<Risk> risks, String preview) {
        this.changes = List.copyOf(changes);
        this.risks = immutableRisks(risks);
        this.state = risks.contains(Risk.UNSUPPORTED_CHANGE) || risks.contains(Risk.RESOURCE_LIMIT) ? State.BLOCKED
                : changes.isEmpty() ? State.NO_CHANGES : State.REVIEW_REQUIRED;
        this.preview = state == State.BLOCKED ? "" : preview;
    }

    /**
     * Compares exact case-sensitive names in stable table/column/index order.
     * Removes and modifications block the entire preview, including independent additions.
     * Declaration ordering is ignored; index key ordering is significant.
     * @param before complete known logical source
     * @param after complete known logical desired state in the same schema
     * @return immutable comparison with conservative risks
     * @throws IllegalArgumentException if the two explicit schemas differ
     */
    public static SchemaDiff compare(SchemaSnapshot before, SchemaSnapshot after) {
        Objects.requireNonNull(before, "before"); Objects.requireNonNull(after, "after");
        if (!before.schema().equals(after.schema())) { throw new IllegalArgumentException("TUPREL-DIFF-TARGET: Schema mismatch"); }
        Builder result = new Builder();
        for (String name : union(before.tables().keySet(), after.tables().keySet())) {
            SqlIdentifier table = new SqlIdentifier(name);
            DdlStatement.TableName target = new DdlStatement.TableName(after.schema(), table);
            SchemaSnapshot.Table oldTable = before.tables().get(name);
            SchemaSnapshot.Table newTable = after.tables().get(name);
            if (newTable == null) {
                result.block(Kind.TABLE_REMOVED, table, null, true);
            } else if (oldTable == null) {
                result.add(Kind.TABLE_ADDED, table, null, new DdlStatement.CreateTable(target, new ArrayList<>(newTable.columns().values())),
                        Risk.DDL_LOCKS);
                newTable.indexes().values().forEach(index -> result.index(table, index));
            } else {
                compareColumns(table, target, oldTable, newTable, result);
                compareIndexes(table, oldTable, newTable, result);
            }
        }
        return result.finish();
    }

    /**
     * Returns the logical result.
     * @return logical result, never a physical drift verdict
     */
    public State state() { return state; }
    /**
     * Returns the deterministic differences.
     * @return immutable deterministic differences
     */
    public List<Change> changes() { return changes; }
    /**
     * Returns conservative risks.
     * @return immutable conservative risks
     */
    public Set<Risk> risks() { return risks; }

    /**
     * Returns the complete proposal only when every change can be rendered.
     * @return complete canonical proposal, or empty text for logical equality
     * @throws IllegalStateException if any difference is unsupported or over budget
     */
    public String preview() {
        if (state == State.BLOCKED) { throw new IllegalStateException("TUPREL-DIFF-BLOCKED: No partial SQL preview"); }
        return preview;
    }

    /** Excludes identifiers and SQL from incidental logging. */
    @Override public String toString() { return "SchemaDiff[state=" + state + ", changes=" + changes.size() + ", risks=" + risks + "]"; }

    private static void compareColumns(SqlIdentifier table, DdlStatement.TableName target,
            SchemaSnapshot.Table before, SchemaSnapshot.Table after, Builder result) {
        for (String name : union(before.columns().keySet(), after.columns().keySet())) {
            SqlIdentifier member = new SqlIdentifier(name);
            DdlStatement.Column oldColumn = before.columns().get(name);
            DdlStatement.Column newColumn = after.columns().get(name);
            if (newColumn == null) { result.block(Kind.COLUMN_REMOVED, table, member, true); }
            else if (oldColumn == null) {
                if (!newColumn.nullable() || newColumn.primaryKey()) { result.block(Kind.COLUMN_ADDED, table, member, false); }
                else { result.add(Kind.COLUMN_ADDED, table, member, new DdlStatement.AddColumn(target, member, newColumn.type()),
                        Risk.DDL_LOCKS, Risk.ACCESS_EXCLUSIVE_LOCK); }
            } else {
                if (oldColumn.type() != newColumn.type()) { result.block(Kind.COLUMN_TYPE_CHANGED, table, member, true); }
                if (oldColumn.nullable() != newColumn.nullable()) { result.block(Kind.COLUMN_NULLABILITY_CHANGED, table, member, false); }
                if (oldColumn.primaryKey() != newColumn.primaryKey()) { result.block(Kind.COLUMN_PRIMARY_KEY_CHANGED, table, member, false); }
            }
        }
    }

    private static void compareIndexes(SqlIdentifier table, SchemaSnapshot.Table before, SchemaSnapshot.Table after, Builder result) {
        for (String name : union(before.indexes().keySet(), after.indexes().keySet())) {
            DdlStatement.CreateIndex oldIndex = before.indexes().get(name);
            DdlStatement.CreateIndex newIndex = after.indexes().get(name);
            SqlIdentifier member = new SqlIdentifier(name);
            if (newIndex == null) { result.block(Kind.INDEX_REMOVED, table, member, false); }
            else if (oldIndex == null) { result.index(table, newIndex); }
            else if (!oldIndex.equals(newIndex)) { result.block(Kind.INDEX_CHANGED, table, member, false); }
        }
    }

    private static Set<String> union(Set<String> before, Set<String> after) {
        Set<String> names = new TreeSet<>(before); names.addAll(after); return names;
    }

    private static Set<Risk> immutableRisks(Set<Risk> risks) {
        EnumSet<Risk> copy = EnumSet.noneOf(Risk.class); copy.addAll(risks);
        return Collections.unmodifiableSet(copy);
    }

    private static final class Builder {
        private final List<Change> changes = new ArrayList<>();
        private final Set<Risk> risks = EnumSet.of(Risk.PHYSICAL_STATE_NOT_CHECKED);
        private final StringBuilder preview = new StringBuilder();
        private int statements;

        void block(Kind kind, SqlIdentifier table, SqlIdentifier member, boolean dataLoss) {
            Set<Risk> detail = EnumSet.of(Risk.UNSUPPORTED_CHANGE, Risk.LOCK_IMPACT_NOT_MODELED);
            if (dataLoss) { detail.add(Risk.POTENTIAL_DATA_LOSS); }
            changes.add(new Change(kind, table, Optional.ofNullable(member), detail)); risks.addAll(detail);
        }

        void add(Kind kind, SqlIdentifier table, SqlIdentifier member, DdlStatement statement, Risk... detail) {
            Set<Risk> operationRisks = EnumSet.noneOf(Risk.class); Collections.addAll(operationRisks, detail);
            changes.add(new Change(kind, table, Optional.ofNullable(member), operationRisks)); risks.addAll(operationRisks);
            String sql = new DdlParseResult(List.of(statement), Optional.empty()).preview();
            if (++statements > PostgresqlMigrationPlanCompiler.MAX_PLAN_STATEMENTS
                    || (long) preview.length() + sql.length() > PostgresqlMigrationPlanCompiler.MAX_PREVIEW_BYTES) {
                risks.add(Risk.RESOURCE_LIMIT);
            } else if (!risks.contains(Risk.RESOURCE_LIMIT)) { preview.append(sql); }
        }

        void index(SqlIdentifier table, DdlStatement.CreateIndex index) {
            add(Kind.INDEX_ADDED, table, index.name(), index, Risk.DDL_LOCKS, Risk.INDEX_WRITE_BLOCKING, Risk.INDEX_BUILD_COST);
        }

        SchemaDiff finish() { return new SchemaDiff(changes, risks, preview.toString()); }
    }
}
