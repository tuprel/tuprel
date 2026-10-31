package dev.tuprel.migrate.postgresql;

import dev.tuprel.sql.SqlIdentifier;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Internal v1 definition shared by the observer and future controlled bootstrap. */
final class MigrationHistoryTable {
    static final String NAME = "_tuprel_migrations";
    private record Column(String name, String declaration, String catalogType) { }
    private record Constraint(String declaration, String catalogDefinition) { }
    private static final List<Column> COLUMNS = List.of(
            new Column("format_version", "\"pg_catalog\".\"int2\"", "smallint"),
            new Column("migration_id", "\"pg_catalog\".\"varchar\"(15)", "character varying(15)"),
            new Column("migration_name", "\"pg_catalog\".\"varchar\"(64)", "character varying(64)"),
            new Column("checksum", "\"pg_catalog\".\"bpchar\"(64)", "character(64)"),
            new Column("state", "\"pg_catalog\".\"text\"", "text"),
            new Column("started_at", "\"pg_catalog\".\"timestamptz\"", "timestamp with time zone"),
            new Column("finished_at", "\"pg_catalog\".\"timestamptz\"", "timestamp with time zone"),
            new Column("execution_millis", "\"pg_catalog\".\"int8\"", "bigint"),
            new Column("engine_version", "\"pg_catalog\".\"varchar\"(64)", "character varying(64)"));
    private static final List<Constraint> CONSTRAINTS = List.of(
            new Constraint("PRIMARY KEY (migration_id)", "PRIMARY KEY (migration_id)"),
            new Constraint("CHECK (format_version OPERATOR(pg_catalog.=) 1)", "CHECK ((format_version = 1))"),
            new Constraint("CHECK (state OPERATOR(pg_catalog.=) 'APPLIED'::\"pg_catalog\".\"text\")", "CHECK ((state = 'APPLIED'::text))"),
            new Constraint("CHECK (finished_at OPERATOR(pg_catalog.>=) started_at)", "CHECK ((finished_at >= started_at))"),
            new Constraint("CHECK (execution_millis OPERATOR(pg_catalog.>=) 0)", "CHECK ((execution_millis >= 0))"));

    private MigrationHistoryTable() { }

    static Map<String, String> columns() {
        return COLUMNS.stream().collect(Collectors.toUnmodifiableMap(Column::name, Column::catalogType));
    }

    static Set<String> constraints() {
        return CONSTRAINTS.stream().map(Constraint::catalogDefinition).collect(Collectors.toUnmodifiableSet());
    }

    /** Trusted internal DDL, not migration input. Does not connect, execute, or grant approval. */
    static String createSql(SqlIdentifier schema) {
        String columns = COLUMNS.stream().map(column -> "    \"" + column.name() + "\" "
                + column.declaration() + " NOT NULL").collect(Collectors.joining(",\n"));
        String constraints = CONSTRAINTS.stream().map(constraint -> "    " + constraint.declaration())
                .collect(Collectors.joining(",\n"));
        return "CREATE TABLE \"" + schema.name() + "\".\"" + NAME + "\" (\n"
                + columns + ",\n" + constraints + "\n);\n";
    }
}
