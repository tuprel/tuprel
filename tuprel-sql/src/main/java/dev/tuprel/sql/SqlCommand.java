package dev.tuprel.sql;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Minimal structural CRUD statements; callers cannot supply SQL fragments. */
public sealed interface SqlCommand permits SqlCommand.Insert, SqlCommand.InsertReturning,
        SqlCommand.FindById, SqlCommand.UpdateById, SqlCommand.UpdateByIdReturning,
        SqlCommand.DeleteById {

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

    /** Changes one row and returns the listed columns of the changed row in the same statement. */
    record UpdateByIdReturning(SqlIdentifier table, SqlIdentifier idColumn, SqlValue id,
            List<Assignment> values, List<SqlIdentifier> returning) implements SqlCommand {
        public UpdateByIdReturning {
            UpdateById checkedUpdate = new UpdateById(table, idColumn, id, values);
            values = checkedUpdate.values();
            returning = checkedColumns(returning);
        }

        /** The same change without the returned columns. */
        public UpdateById update() {
            return new UpdateById(table, idColumn, id, values);
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
