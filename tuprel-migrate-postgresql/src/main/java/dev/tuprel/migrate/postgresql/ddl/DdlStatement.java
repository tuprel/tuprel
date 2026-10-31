package dev.tuprel.migrate.postgresql.ddl;

import dev.tuprel.sql.SqlIdentifier;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Proposed bounded DDL AST; syntax recognition is not approval or permission to execute. */
public sealed interface DdlStatement permits DdlStatement.CreateTable, DdlStatement.AddColumn, DdlStatement.CreateIndex {
    /** Maximum columns in a proposed CREATE TABLE. */
    int MAX_COLUMNS = 256;
    /** Maximum plain columns in a proposed CREATE INDEX. */
    int MAX_INDEX_COLUMNS = 32;

    /**
     * Explicit target relation, never resolved through search_path.
     * @return qualified table
     */
    TableName table();

    /**
     * Qualified relation name; reuses the existing SQL identifier contract.
     * @param schema explicit schema
     * @param name relation basename
     */
    record TableName(SqlIdentifier schema, SqlIdentifier name) {
        /** Requires both validated components. */
        public TableName { Objects.requireNonNull(schema, "schema"); Objects.requireNonNull(name, "name"); }
    }

    /** Built-in PostgreSQL types admitted by this proposal; no user-defined types or expressions. */
    enum Type {
        /** Native UUID. */ UUID("uuid"),
        /** Boolean. */ BOOLEAN("bool"),
        /** Signed 16-bit integer. */ SMALLINT("int2"),
        /** Signed 32-bit integer. */ INTEGER("int4"),
        /** Signed 64-bit integer. */ BIGINT("int8"),
        /** Unbounded text. */ TEXT("text"),
        /** Calendar date. */ DATE("date"),
        /** Timestamp with time zone. */ TIMESTAMPTZ("timestamptz");

        private final String catalogName;
        Type(String catalogName) { this.catalogName = catalogName; }
        String sql() { return "\"pg_catalog\".\"" + catalogName + "\""; }
        static Type fromCatalogName(String name) {
            for (Type type : values()) { if (type.catalogName.equals(name)) { return type; } }
            throw new IllegalArgumentException("Unsupported built-in type");
        }
    }

    /**
     * Simple column without defaults, expressions, references or generated values.
     * @param name validated column name
     * @param type admitted built-in type
     * @param nullable whether NULL is permitted
     * @param primaryKey whether this is the single inline primary key
     */
    record Column(SqlIdentifier name, Type type, boolean nullable, boolean primaryKey) {
        /** A primary key cannot be nullable. */
        public Column {
            Objects.requireNonNull(name, "name"); Objects.requireNonNull(type, "type");
            if (primaryKey && nullable) { throw new IllegalArgumentException("Primary key must be non-null"); }
        }
    }

    /**
     * Ordinary persistent table with distinct columns and at most one inline PK.
     * @param table qualified target
     * @param columns nonempty bounded column definitions
     */
    record CreateTable(TableName table, List<Column> columns) implements DdlStatement {
        /** Copies and validates the complete definition. */
        public CreateTable {
            Objects.requireNonNull(table, "table"); columns = List.copyOf(columns);
            if (columns.isEmpty() || columns.size() > MAX_COLUMNS
                    || new HashSet<>(columns.stream().map(Column::name).toList()).size() != columns.size()
                    || columns.stream().filter(Column::primaryKey).count() > 1) {
                throw new IllegalArgumentException("Invalid simple table definition");
            }
        }
    }

    /**
     * Adds one nullable column; no default or NOT NULL option exists in this node.
     * @param table qualified target
     * @param name validated new column name
     * @param type admitted built-in type
     */
    record AddColumn(TableName table, SqlIdentifier name, Type type) implements DdlStatement {
        /** Requires all components. */
        public AddColumn { Objects.requireNonNull(table, "table"); Objects.requireNonNull(name, "name"); Objects.requireNonNull(type, "type"); }
    }

    /**
     * Plain, nonunique, nonconcurrent index; belongs to the relation's schema.
     * @param table qualified indexed relation
     * @param name unqualified index basename, as required by PostgreSQL CREATE INDEX
     * @param columns nonempty distinct plain column names
     */
    record CreateIndex(TableName table, SqlIdentifier name, List<SqlIdentifier> columns) implements DdlStatement {
        /** Copies and validates columns; no expressions or predicates are representable. */
        public CreateIndex {
            Objects.requireNonNull(table, "table"); Objects.requireNonNull(name, "name"); columns = List.copyOf(columns);
            if (columns.isEmpty() || columns.size() > MAX_INDEX_COLUMNS || new HashSet<>(columns).size() != columns.size()) {
                throw new IllegalArgumentException("Invalid plain index definition");
            }
        }
    }
}
