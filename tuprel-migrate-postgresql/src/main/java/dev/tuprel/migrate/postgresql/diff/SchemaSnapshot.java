package dev.tuprel.migrate.postgresql.diff;

import dev.tuprel.migrate.postgresql.PostgresqlMigrationPlanCompiler;
import dev.tuprel.migrate.postgresql.ddl.DdlParseResult;
import dev.tuprel.migrate.postgresql.ddl.DdlStatement;
import dev.tuprel.sql.SqlIdentifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Immutable logical state reconstructed from the accepted DDL AST, starting empty.
 * Not a PostgreSQL catalog snapshot, schema.tuprel mapping or execution approval.
 * Declaration order of tables/columns/indexes is excluded; index key order is retained.
 */
public final class SchemaSnapshot {
    /** Maximum tables, columns and explicit indexes combined in one snapshot. */
    public static final int MAX_OBJECTS = 16384;
    private final SqlIdentifier schema;
    private final Map<String, Table> tables;

    private SchemaSnapshot(SqlIdentifier schema, Map<String, Table> tables) {
        this.schema = schema;
        this.tables = Collections.unmodifiableMap(new TreeMap<>(tables));
    }

    /**
     * Immutable simple-table metadata; containing snapshots validate the complete graph.
     * @param columns columns ordered by exact case-sensitive name
     * @param indexes explicit plain indexes ordered by exact name; excludes implicit PK indexes
     */
    public record Table(Map<String, DdlStatement.Column> columns, Map<String, DdlStatement.CreateIndex> indexes) {
        /** Copies both maps; the containing snapshot validates the complete graph. */
        public Table {
            columns = Collections.unmodifiableMap(new TreeMap<>(columns));
            indexes = Collections.unmodifiableMap(new TreeMap<>(indexes));
        }

        /** Keeps identifiers out of incidental logging. */
        @Override public String toString() { return "Table[columns=" + columns.size() + ", indexes=" + indexes.size() + "]"; }
    }

    /**
     * Reconstructs from complete parser results, rejecting every unsupported result.
     * Do not extract statements() from a failed parse and interpret its empty list as
     * an empty schema. An empty results list explicitly denotes the empty logical state.
     * @param schema explicit target
     * @param scripts complete parser results in migration execution order
     * @return validated logical state, never a partial supported prefix
     * @throws IllegalArgumentException on unknown SQL or an exceeded statement budget
     */
    public static SchemaSnapshot fromParsed(SqlIdentifier schema, List<DdlParseResult> scripts) {
        Objects.requireNonNull(scripts, "scripts");
        if (scripts.size() > PostgresqlMigrationPlanCompiler.MAX_PLAN_STATEMENTS) {
            throw new IllegalArgumentException("TUPREL-DIFF-RESOURCE_LIMIT: Too many scripts");
        }
        List<DdlStatement> statements = new ArrayList<>();
        for (DdlParseResult script : scripts) {
            if (!script.isSupported()) { throw new IllegalArgumentException("TUPREL-DIFF-UNKNOWN: Complete supported input required"); }
            if ((long) statements.size() + script.statements().size() > PostgresqlMigrationPlanCompiler.MAX_PLAN_STATEMENTS) {
                throw new IllegalArgumentException("TUPREL-DIFF-RESOURCE_LIMIT: Too many statements");
            }
            statements.addAll(script.statements());
        }
        return replay(schema, statements);
    }

    /**
     * Replays a bounded complete sequence from an empty schema, without I/O.
     * Missing prerequisites, duplicate relations/columns and reserved names refuse
     * the entire snapshot. Input statement order is significant for prerequisites.
     * Empty input denotes an empty logical schema, never an observed database.
     * @param schema explicit schema for every statement
     * @param statements accepted AST nodes, normally obtained from PostgresqlDdlParser
     * @return fully known immutable logical state
     * @throws IllegalArgumentException on inconsistent, out-of-scope or oversized input
     */
    public static SchemaSnapshot replay(SqlIdentifier schema, List<DdlStatement> statements) {
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(statements, "statements");
        if (statements.size() > PostgresqlMigrationPlanCompiler.MAX_PLAN_STATEMENTS) {
            throw new IllegalArgumentException("TUPREL-DIFF-RESOURCE_LIMIT: Too many statements");
        }
        statements = List.copyOf(statements);
        Map<String, Table> tables = new TreeMap<>();
        HashSet<String> relations = new HashSet<>();
        int objects = 0;
        for (DdlStatement statement : statements) {
            Objects.requireNonNull(statement, "statement");
            String name = statement.table().name().name();
            if (!statement.table().schema().equals(schema) || reserved(name)) {
                throw new IllegalArgumentException("TUPREL-DIFF-TARGET: Explicit target mismatch or reserved relation");
            }
            objects += statement instanceof DdlStatement.CreateTable create ? 1 + create.columns().size() : 1;
            if (objects > MAX_OBJECTS) { throw new IllegalArgumentException("TUPREL-DIFF-RESOURCE_LIMIT: Too many schema objects"); }
            switch (statement) {
                case DdlStatement.CreateTable create -> {
                    if (!relations.add(name)) { throw conflict(); }
                    Map<String, DdlStatement.Column> columns = new TreeMap<>();
                    create.columns().forEach(column -> columns.put(column.name().name(), column));
                    tables.put(name, new Table(columns, Map.of()));
                }
                case DdlStatement.AddColumn add -> {
                    Table table = requireTable(tables, name);
                    Map<String, DdlStatement.Column> columns = new TreeMap<>(table.columns());
                    if (columns.putIfAbsent(add.name().name(), new DdlStatement.Column(add.name(), add.type(), true, false)) != null
                            || columns.size() > DdlStatement.MAX_COLUMNS) { throw conflict(); }
                    tables.put(name, new Table(columns, table.indexes()));
                }
                case DdlStatement.CreateIndex index -> {
                    Table table = requireTable(tables, name);
                    if (reserved(index.name().name()) || !relations.add(index.name().name())
                            || !table.columns().keySet().containsAll(index.columns().stream().map(SqlIdentifier::name).toList())) {
                        throw conflict();
                    }
                    Map<String, DdlStatement.CreateIndex> indexes = new TreeMap<>(table.indexes());
                    indexes.put(index.name().name(), index);
                    tables.put(name, new Table(table.columns(), indexes));
                }
            }
        }
        return new SchemaSnapshot(schema, tables);
    }

    /**
     * Returns the explicit logical schema.
     * @return explicit logical schema, not a connection target
     */
    public SqlIdentifier schema() { return schema; }

    /**
     * Returns known tables in deterministic order.
     * @return immutable exact-name ordered tables
     */
    public Map<String, Table> tables() { return tables; }

    /** Keeps identifiers out of incidental logging. */
    @Override public String toString() { return "SchemaSnapshot[tables=" + tables.size() + "]"; }

    private static Table requireTable(Map<String, Table> tables, String name) {
        Table table = tables.get(name);
        if (table == null) { throw new IllegalArgumentException("TUPREL-DIFF-MISSING_TABLE: Replay requires a preceding table definition"); }
        return table;
    }

    private static IllegalArgumentException conflict() {
        return new IllegalArgumentException("TUPREL-DIFF-CONFLICT: Duplicate object, missing column or unsupported table size");
    }

    private static boolean reserved(String name) { return name.toLowerCase(Locale.ROOT).startsWith("_tuprel_"); }
}
