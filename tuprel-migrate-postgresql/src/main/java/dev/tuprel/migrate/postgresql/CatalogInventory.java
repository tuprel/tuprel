package dev.tuprel.migrate.postgresql;

import static dev.tuprel.migrate.postgresql.CatalogObservation.Diagnostic;
import static dev.tuprel.migrate.postgresql.PostgresqlCatalogObserver.ObservationFailure;

import dev.tuprel.migrate.postgresql.ddl.DdlStatement;
import dev.tuprel.migrate.postgresql.diff.SchemaSnapshot;
import dev.tuprel.sql.SqlIdentifier;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Assembles only verified metadata. No snapshot is constructed after a refused object. */
final class CatalogInventory {
    static final int MAX_ROWS = 65536;
    static final long MAX_BYTES = 16L * 1024 * 1024;
    private static final Map<Long, DdlStatement.Type> TYPES = Map.of(
            2950L, DdlStatement.Type.UUID, 16L, DdlStatement.Type.BOOLEAN, 21L, DdlStatement.Type.SMALLINT, 23L, DdlStatement.Type.INTEGER,
            20L, DdlStatement.Type.BIGINT, 25L, DdlStatement.Type.TEXT, 1082L, DdlStatement.Type.DATE, 1184L, DdlStatement.Type.TIMESTAMPTZ);

    private final Connection connection;
    private final SqlIdentifier schema;
    private final long namespace;
    private final Map<Long, Relation> relations = new TreeMap<>();
    private final EnumSet<Diagnostic> diagnostics = EnumSet.noneOf(Diagnostic.class);
    private int rows;
    private long bytes;
    private long historyOid;

    CatalogInventory(Connection connection, SqlIdentifier schema, long namespace) {
        this.connection = connection;
        this.schema = schema;
        this.namespace = namespace;
    }

    CatalogObservation read(boolean extensionNamespace) throws SQLException, ObservationFailure {
        if (extensionNamespace) { diagnostics.add(Diagnostic.RELATION); }
        read(CatalogQueries.RELATIONS, 1, this::relation);
        read(CatalogQueries.TYPES, 2, row -> {
            require(row.getLong(1) > 0);
            if (!row.getBoolean(2)) { diagnostics.add(Diagnostic.TYPE); }
        });
        read(CatalogQueries.COLUMNS, 1, this::column);
        read(CatalogQueries.CONSTRAINTS, 1, this::constraint);
        read(CatalogQueries.INDEXES, 1, this::index);
        for (Relation relation : relations.values()) {
            if (relation.kind.equals("r")) { verifyTable(relation); }
            if (relation.kind.equals("i")) { require(relation.indexSeen); }
        }
        if (!diagnostics.isEmpty()) { return CatalogObservation.incomplete(List.copyOf(diagnostics)); }
        Map<String, Long> identities = new HashMap<>();
        for (Map.Entry<Long, Relation> entry : relations.entrySet()) {
            require(identities.putIfAbsent(entry.getValue().name, entry.getKey()) == null);
        }
        return CatalogObservation.complete(snapshot(), identities);
    }

    long historyOid() { return historyOid; }

    private void relation(ResultSet row) throws SQLException, ObservationFailure {
        long oid = row.getLong(1);
        String name = text(row, 2);
        String kind = text(row, 3);
        int columns = row.getInt(4);
        require(oid > 0 && columns >= 0 && row.getBoolean(6)
                && Set.of("r", "i", "S", "t", "v", "m", "c", "f", "p", "I").contains(kind));
        Relation relation = new Relation(name, kind, columns);
        require(relations.putIfAbsent(oid, relation) == null);
        if (relation.history()) {
            require(historyOid == 0);
            historyOid = oid;
        }
        if (!row.getBoolean(5) || !identifier(name)
                || (kind.equals("r") && reserved(name) && !relation.history())) {
            unsupported(relation, Diagnostic.RELATION);
        }
    }

    private void column(ResultSet row) throws SQLException, ObservationFailure {
        Relation table = relations.get(row.getLong(1));
        int number = row.getInt(2);
        String name = text(row, 3);
        long type = row.getLong(4);
        int modifier = row.getInt(5);
        boolean notNull = row.getBoolean(6);
        require(table != null && table.kind.equals("r") && number > 0 && number <= table.attributes
                && row.getBoolean(8));
        require(table.columns.putIfAbsent(number, new Column(name, type, modifier, notNull)) == null);
        if (row.getBoolean(7) || !row.getBoolean(9) || !identifier(name)
                || (!table.history() && (!TYPES.containsKey(type) || modifier != -1))) {
            unsupported(table, Diagnostic.COLUMN);
        }
    }

    private void constraint(ResultSet row) throws SQLException, ObservationFailure {
        require(row.getLong(1) > 0);
        Relation table = relations.get(row.getLong(2));
        Relation referenced = relations.get(row.getLong(3));
        String kind = text(row, 4);
        require(table != null || referenced != null);
        require(Set.of("c", "f", "n", "p", "u", "t", "x").contains(kind));
        if (table == null || !table.kind.equals("r")) {
            unsupported(table == null ? referenced : table, Diagnostic.CONSTRAINT);
            return;
        }
        boolean valid = row.getBoolean(7);
        if (kind.equals("p")) {
            long index = row.getLong(5);
            int column = row.getInt(6);
            int keys = row.getInt(8);
            require(index > 0 && keys > 0 && relations.containsKey(index));
            if (table.pkIndex != 0 || !valid || keys != 1 || column <= 0) {
                unsupported(table, Diagnostic.CONSTRAINT);
            } else {
                require(table.columns.containsKey(column));
                table.pkIndex = index;
                table.pkColumn = column;
            }
        } else if (table.history() && kind.equals("c") && valid && row.getLong(5) == 0) {
            table.checks++;
        } else {
            unsupported(table, Diagnostic.CONSTRAINT);
        }
    }

    private void index(ResultSet row) throws SQLException, ObservationFailure {
        long oid = row.getLong(1);
        Relation index = relations.get(oid);
        Relation table = relations.get(row.getLong(2));
        int total = row.getInt(3);
        int keys = row.getInt(4);
        String[] positions = text(row, 5).split(" ", -1);
        boolean primary = row.getBoolean(6);
        require(row.getBoolean(7) && total > 0 && keys > 0 && total >= keys && positions.length == total);
        if (index == null || table == null) {
            // An index located outside the approved namespace can never be part of a complete result.
            unsupported(index == null ? table : index, Diagnostic.INDEX);
            return;
        }
        require(!index.indexSeen && (index.kind.equals("i") || index.kind.equals("I")));
        index.indexSeen = true;
        table.indexCount++;
        if (!row.getBoolean(8) || !table.kind.equals("r")
                || (primary != (table.pkIndex == oid))
                || (table.history() ? !primary : reserved(index.name))) {
            unsupported(table, Diagnostic.INDEX);
            return;
        }
        if (keys > DdlStatement.MAX_INDEX_COLUMNS) { throw limit(); }
        List<String> columnNames = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (String position : positions) {
            int number = Integer.parseInt(position);
            require(table.columns.containsKey(number));
            if (!seen.add(number) || (primary && (keys != 1 || table.pkColumn != number))) {
                unsupported(table, Diagnostic.INDEX);
                return;
            }
            columnNames.add(table.columns.get(number).name());
        }
        if (!primary) { table.indexes.put(index.name, List.copyOf(columnNames)); }
        else { table.pkIndexSeen = true; }
    }

    private void verifyTable(Relation table) throws ObservationFailure {
        require(table.columns.size() == table.attributes);
        if (table.attributes > DdlStatement.MAX_COLUMNS) { throw limit(); }
        if (table.attributes == 0) { unsupported(table, Diagnostic.COLUMN); }
        if (table.pkIndex != 0 && (!table.pkIndexSeen || !table.columns.get(table.pkColumn).notNull())) {
            unsupported(table, Diagnostic.CONSTRAINT);
        }
        if (table.history()) {
            Map<String, String> shape = new HashMap<>();
            for (Column column : table.columns.values()) {
                if (!column.notNull() || shape.put(column.name(), column.historyType()) != null) {
                    diagnostics.add(Diagnostic.HISTORY);
                }
            }
            if (!shape.equals(MigrationHistoryTable.columns()) || table.pkIndex == 0 || !table.pkIndexSeen
                    || !table.columns.get(table.pkColumn).name().equals("migration_id")
                    || table.checks != MigrationHistoryTable.constraints().size() - 1 || table.indexCount != 1) {
                diagnostics.add(Diagnostic.HISTORY);
            }
        }
    }

    private SchemaSnapshot snapshot() throws ObservationFailure {
        int objects = 0;
        List<DdlStatement> statements = new ArrayList<>();
        for (Relation table : relations.values()) {
            if (!table.kind.equals("r") || table.history()) { continue; }
            objects += 1 + table.columns.size() + table.indexes.size();
            if (objects > SchemaSnapshot.MAX_OBJECTS
                    || statements.size() + 1 + table.indexes.size() > PostgresqlMigrationPlanCompiler.MAX_PLAN_STATEMENTS) {
                throw limit();
            }
            DdlStatement.TableName name = new DdlStatement.TableName(schema, new SqlIdentifier(table.name));
            List<DdlStatement.Column> columns = new ArrayList<>();
            table.columns.forEach((number, column) -> columns.add(new DdlStatement.Column(
                    new SqlIdentifier(column.name()), TYPES.get(column.type()), !column.notNull(), number == table.pkColumn)));
            statements.add(new DdlStatement.CreateTable(name, columns));
            table.indexes.forEach((index, columnsInKeyOrder) -> statements.add(new DdlStatement.CreateIndex(
                    name, new SqlIdentifier(index), columnsInKeyOrder.stream().map(SqlIdentifier::new).toList())));
        }
        return SchemaSnapshot.replay(schema, statements);
    }

    private void read(String sql, int parameters, ReadRow reader) throws SQLException, ObservationFailure {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(5);
            statement.setFetchSize(256);
            statement.setMaxRows(MAX_ROWS - rows + 1);
            for (int i = 1; i <= parameters; i++) { statement.setLong(i, namespace); }
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    // Upper bound for the fixed-width fields in any of the projections.
                    bytes += 96;
                    if (++rows > MAX_ROWS || bytes > MAX_BYTES) { throw limit(); }
                    reader.accept(result);
                }
            }
        }
    }

    private String text(ResultSet row, int index) throws SQLException, ObservationFailure {
        String value = row.getString(index);
        require(value != null);
        bytes += value.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_BYTES) { throw limit(); }
        return value;
    }

    private void unsupported(Relation relation, Diagnostic category) {
        diagnostics.add(relation != null && relation.history() ? Diagnostic.HISTORY : category);
    }

    private static boolean identifier(String name) {
        try { new SqlIdentifier(name); return true; }
        catch (IllegalArgumentException failure) { return false; }
    }

    private static boolean reserved(String name) { return name.toLowerCase(Locale.ROOT).startsWith("_tuprel_"); }

    private static void require(boolean valid) throws ObservationFailure {
        if (!valid) { throw new ObservationFailure(Diagnostic.METADATA); }
    }

    private static ObservationFailure limit() { return new ObservationFailure(Diagnostic.RESOURCE_LIMIT); }

    @FunctionalInterface
    private interface ReadRow {
        void accept(ResultSet row) throws SQLException, ObservationFailure;
    }

    private static final class Relation {
        private final String name;
        private final String kind;
        private final int attributes;
        private final Map<Integer, Column> columns = new TreeMap<>();
        private final Map<String, List<String>> indexes = new TreeMap<>();
        private long pkIndex;
        private int pkColumn;
        private int indexCount;
        private int checks;
        private boolean indexSeen;
        private boolean pkIndexSeen;

        private Relation(String name, String kind, int attributes) {
            this.name = name;
            this.kind = kind;
            this.attributes = attributes;
        }

        private boolean history() { return name.equals(MigrationHistoryTable.NAME); }
    }

    private record Column(String name, long type, int modifier, boolean notNull) {
        private String historyType() {
            if (type == 1043 && modifier >= 5) { return "character varying(" + (modifier - 4) + ")"; }
            if (type == 1042 && modifier >= 5) { return "character(" + (modifier - 4) + ")"; }
            if (modifier != -1) { return ""; }
            return switch (TYPES.getOrDefault(type, DdlStatement.Type.BOOLEAN)) {
                case SMALLINT -> "smallint";
                case BIGINT -> "bigint";
                case TEXT -> "text";
                case TIMESTAMPTZ -> "timestamp with time zone";
                default -> "";
            };
        }
    }
}
