package dev.tuprel.sql;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Immutable structural SELECT over one table; it never contains caller SQL fragments.
 *
 * <p>A {@link Selection.Columns} query reads explicit columns and may be ordered and paged.
 * {@link Selection.Count} and {@link Selection.Exists} only evaluate the condition, so they
 * reject ordering and paging instead of silently changing their meaning.
 */
public record SqlQuery(
        SqlIdentifier table,
        Selection selection,
        Optional<SqlCondition> condition,
        List<SqlOrder> orderBy,
        Optional<Integer> limit,
        Optional<Integer> offset,
        Optional<SqlQuery.Lock> lock) {

    /** What the query returns. */
    public sealed interface Selection permits Selection.Columns, Selection.Count, Selection.Exists {
        /** Explicit, non-empty, duplicate-free list of columns. */
        record Columns(List<SqlIdentifier> columns) implements Selection {
            public Columns {
                columns = List.copyOf(columns);
                if (columns.isEmpty()) {
                    throw new IllegalArgumentException("At least one column is required");
                }
                Set<SqlIdentifier> seen = new HashSet<>();
                for (SqlIdentifier column : columns) {
                    if (!seen.add(column)) {
                        throw new IllegalArgumentException("Duplicate column");
                    }
                }
            }
        }

        /** Number of matching rows, returned in the column {@code count}. */
        record Count() implements Selection { }

        /** Whether any row matches, returned in the column {@code exists}. */
        record Exists() implements Selection { }
    }

    public SqlQuery {
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(selection, "selection");
        Objects.requireNonNull(condition, "condition");
        orderBy = List.copyOf(orderBy);
        Objects.requireNonNull(limit, "limit");
        Objects.requireNonNull(offset, "offset");
        limit.ifPresent(value -> {
            if (value < 1) {
                throw new IllegalArgumentException("Limit must be positive");
            }
        });
        offset.ifPresent(value -> {
            if (value < 0) {
                throw new IllegalArgumentException("Offset cannot be negative");
            }
        });
        Objects.requireNonNull(lock, "lock");
        if (!(selection instanceof Selection.Columns)
                && (!orderBy.isEmpty() || limit.isPresent() || offset.isPresent() || lock.isPresent())) {
            throw new IllegalArgumentException("Count and exists queries cannot be ordered, paged or locked");
        }
    }

    /**
     * Row lock taken on the rows a column query returns. A lock is only meaningful inside a
     * transaction; the runtime enforces that.
     */
    public record Lock(Strength strength, Wait waitPolicy) {
        public Lock {
            Objects.requireNonNull(strength, "strength");
            Objects.requireNonNull(waitPolicy, "waitPolicy");
        }

        /** Lock strength. */
        public enum Strength {
            /** Exclusive lock for rows that will be updated or deleted. */
            UPDATE,
            /** Shared lock that blocks concurrent updates and deletes. */
            SHARE
        }

        /** Behaviour when a row is already locked by another transaction. */
        public enum Wait {
            /** Wait for the other transaction, subject to statement timeouts. */
            WAIT,
            /** Fail immediately. */
            NOWAIT,
            /** Omit locked rows from the result. */
            SKIP_LOCKED
        }
    }

    /** Returns a copy that locks the returned rows. */
    public SqlQuery lock(Lock value) {
        return new SqlQuery(table, selection, condition, orderBy, limit, offset,
                Optional.of(Objects.requireNonNull(value, "value")));
    }

    /** Starts a query reading the given columns of a table. */
    public static SqlQuery select(SqlIdentifier table, List<SqlIdentifier> columns) {
        return new SqlQuery(table, new Selection.Columns(columns), Optional.empty(), List.of(),
                Optional.empty(), Optional.empty(), Optional.empty());
    }

    /** Starts a query counting rows of a table. */
    public static SqlQuery count(SqlIdentifier table) {
        return new SqlQuery(table, new Selection.Count(), Optional.empty(), List.of(),
                Optional.empty(), Optional.empty(), Optional.empty());
    }

    /** Starts a query testing whether a table has a matching row. */
    public static SqlQuery exists(SqlIdentifier table) {
        return new SqlQuery(table, new Selection.Exists(), Optional.empty(), List.of(),
                Optional.empty(), Optional.empty(), Optional.empty());
    }

    /** Returns a copy with the given condition, replacing any previous one. */
    public SqlQuery where(SqlCondition value) {
        return new SqlQuery(table, selection, Optional.of(Objects.requireNonNull(value, "value")),
                orderBy, limit, offset, lock);
    }

    /** Returns a copy with the given ordering, replacing any previous one. */
    public SqlQuery orderBy(List<SqlOrder> values) {
        return new SqlQuery(table, selection, condition, values, limit, offset, lock);
    }

    /** Returns a copy returning at most {@code value} rows. */
    public SqlQuery limit(int value) {
        return new SqlQuery(table, selection, condition, orderBy, Optional.of(value), offset, lock);
    }

    /** Returns a copy skipping the first {@code value} rows. */
    public SqlQuery offset(int value) {
        return new SqlQuery(table, selection, condition, orderBy, limit, Optional.of(value), lock);
    }
}
