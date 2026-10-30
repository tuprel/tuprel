package dev.tuprel.sql;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Structural write statements; callers cannot supply SQL fragments. */
public sealed interface SqlCommand permits SqlCommand.Insert, SqlCommand.InsertReturning,
        SqlCommand.InsertMany, SqlCommand.FindById, SqlCommand.UpdateById,
        SqlCommand.UpdateByIdReturning, SqlCommand.UpdateWhere, SqlCommand.DeleteById,
        SqlCommand.DeleteWhere {

    SqlIdentifier table();

    /** Column and its separately bound value. */
    record Assignment(SqlIdentifier column, SqlValue value) {
        public Assignment {
            Objects.requireNonNull(column, "column");
            Objects.requireNonNull(value, "value");
        }
    }

    private static List<Assignment> checked(List<Assignment> assignments) {
        List<Assignment> copy = List.copyOf(assignments);
        if (copy.isEmpty()) {
            throw new IllegalArgumentException("At least one column is required");
        }
        Set<SqlIdentifier> seen = new HashSet<>();
        for (Assignment assignment : copy) {
            if (!seen.add(assignment.column())) {
                throw new IllegalArgumentException("Duplicate column");
            }
        }
        return copy;
    }

    record Insert(SqlIdentifier table, List<Assignment> values) implements SqlCommand {
        public Insert {
            Objects.requireNonNull(table, "table");
            values = checked(values);
        }
    }

    private static List<SqlIdentifier> checkedColumns(List<SqlIdentifier> columns) {
        return new SqlQuery.Selection.Columns(columns).columns();
    }

    /** Inserts one row and returns the listed columns of the inserted row in the same statement. */
    record InsertReturning(SqlIdentifier table, List<Assignment> values, List<SqlIdentifier> returning)
            implements SqlCommand {
        public InsertReturning {
            Objects.requireNonNull(table, "table");
            values = checked(values);
            returning = checkedColumns(returning);
        }
    }

    record FindById(SqlIdentifier table, SqlIdentifier idColumn, SqlValue id) implements SqlCommand {
        public FindById {
            Objects.requireNonNull(table, "table");
            Objects.requireNonNull(idColumn, "idColumn");
            Objects.requireNonNull(id, "id");
            if (id instanceof SqlValue.Null) {
                throw new IllegalArgumentException("Identifier value cannot be null");
            }
        }
    }

    record UpdateById(SqlIdentifier table, SqlIdentifier idColumn, SqlValue id,
            List<Assignment> values) implements SqlCommand {
        public UpdateById {
            Objects.requireNonNull(table, "table");
            Objects.requireNonNull(idColumn, "idColumn");
            Objects.requireNonNull(id, "id");
            if (id instanceof SqlValue.Null) {
                throw new IllegalArgumentException("Identifier value cannot be null");
            }
            values = checked(values);
            for (Assignment assignment : values) {
                if (assignment.column().equals(idColumn)) {
                    throw new IllegalArgumentException("Identifier column cannot be updated");
                }
            }
        }
    }

    /**
     * Inserts several rows in one statement. Every row has one cell per column; an empty cell
     * lets the database apply the column default, so rows with different present fields share
     * one atomic statement.
     */
    record InsertMany(SqlIdentifier table, List<SqlIdentifier> columns, List<List<Optional<SqlValue>>> rows)
            implements SqlCommand {
        public InsertMany {
            Objects.requireNonNull(table, "table");
            columns = checkedColumns(columns);
            Objects.requireNonNull(rows, "rows");
            if (rows.isEmpty()) {
                throw new IllegalArgumentException("At least one row is required");
            }
            int width = columns.size();
            rows = rows.stream().map(row -> {
                List<Optional<SqlValue>> copy = List.copyOf(row);
                if (copy.size() != width) {
                    throw new IllegalArgumentException("Every row needs one cell per column");
                }
                copy.forEach(cell -> Objects.requireNonNull(cell, "cell"));
                return copy;
            }).toList();
        }
    }

    /** Expected value of an optimistic-locking counter, incremented by one on success. */
    record VersionCheck(SqlIdentifier column, SqlValue expected) {
        public VersionCheck {
            Objects.requireNonNull(column, "column");
            Objects.requireNonNull(expected, "expected");
            if (expected instanceof SqlValue.Null) {
                throw new IllegalArgumentException("Expected version cannot be null");
            }
        }
    }

    /**
     * Changes one row and returns the listed columns of the changed row in the same statement.
     * With a version check, the row only changes when its counter equals the expected value, and
     * the counter is incremented in the same statement.
     */
    record UpdateByIdReturning(SqlIdentifier table, SqlIdentifier idColumn, SqlValue id,
            List<Assignment> values, List<SqlIdentifier> returning, Optional<VersionCheck> version)
            implements SqlCommand {
        public UpdateByIdReturning {
            UpdateById checkedUpdate = new UpdateById(table, idColumn, id, values);
            values = checkedUpdate.values();
            returning = checkedColumns(returning);
            Objects.requireNonNull(version, "version");
            if (version.isPresent()) {
                rejectAssigned(values, version.get().column());
                if (version.get().column().equals(idColumn)) {
                    throw new IllegalArgumentException("Version column cannot be the identifier");
                }
            }
        }

        /** An unversioned change. */
        public UpdateByIdReturning(SqlIdentifier table, SqlIdentifier idColumn, SqlValue id,
                List<Assignment> values, List<SqlIdentifier> returning) {
            this(table, idColumn, id, values, returning, Optional.empty());
        }

        /** The same change without the returned columns or version check. */
        public UpdateById update() {
            return new UpdateById(table, idColumn, id, values);
        }
    }

    /**
     * Changes every row matching a condition; there is no unconditional form. A version column,
     * when present, is incremented on every changed row.
     */
    record UpdateWhere(SqlIdentifier table, List<Assignment> values, SqlCondition condition,
            Optional<SqlIdentifier> incrementVersion) implements SqlCommand {
        public UpdateWhere {
            Objects.requireNonNull(table, "table");
            values = checked(values);
            Objects.requireNonNull(condition, "condition");
            Objects.requireNonNull(incrementVersion, "incrementVersion");
            if (incrementVersion.isPresent()) {
                rejectAssigned(values, incrementVersion.get());
            }
        }
    }

    private static void rejectAssigned(List<Assignment> values, SqlIdentifier column) {
        for (Assignment assignment : values) {
            if (assignment.column().equals(column)) {
                throw new IllegalArgumentException("Version column cannot be assigned directly");
            }
        }
    }

    /** Deletes every row matching a condition; there is no unconditional form. */
    record DeleteWhere(SqlIdentifier table, SqlCondition condition) implements SqlCommand {
        public DeleteWhere {
            Objects.requireNonNull(table, "table");
            Objects.requireNonNull(condition, "condition");
        }
    }

    record DeleteById(SqlIdentifier table, SqlIdentifier idColumn, SqlValue id) implements SqlCommand {
        public DeleteById {
            Objects.requireNonNull(table, "table");
            Objects.requireNonNull(idColumn, "idColumn");
            Objects.requireNonNull(id, "id");
            if (id instanceof SqlValue.Null) {
                throw new IllegalArgumentException("Identifier value cannot be null");
            }
        }
    }
}
