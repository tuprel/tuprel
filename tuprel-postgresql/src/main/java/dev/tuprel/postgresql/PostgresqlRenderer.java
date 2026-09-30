package dev.tuprel.postgresql;

import dev.tuprel.sql.RenderedSql;
import dev.tuprel.sql.SqlCommand;
import dev.tuprel.sql.SqlCondition;
import dev.tuprel.sql.SqlIdentifier;
import dev.tuprel.sql.SqlOrder;
import dev.tuprel.sql.SqlQuery;
import dev.tuprel.sql.SqlRenderer;
import dev.tuprel.sql.SqlValue;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/**
 * PostgreSQL renderer for structural CRUD and queries.
 *
 * <p>Identifiers are validated by {@link SqlIdentifier} and always double-quoted, so names are
 * case-sensitive. Every value, including LIMIT and OFFSET, is a {@code ?} bind. Text pattern
 * operators use {@code LIKE ... ESCAPE '!'}: the escape character needs no backslash, so the
 * SQL text does not depend on {@code standard_conforming_strings}.
 */
public final class PostgresqlRenderer implements SqlRenderer {
    /** Upper bound of bind parameters in one PostgreSQL extended-protocol statement. */
    static final int MAX_BINDS = 65_535;
    private static final char LIKE_ESCAPE = '!';

    private static String quote(SqlIdentifier identifier) {
        return "\"" + identifier.name() + "\"";
    }

    @Override
    public RenderedSql render(SqlCommand command) {
        return checked(switch (command) {
            case SqlCommand.Insert insert -> insert(insert.table(), insert.values());
            case SqlCommand.InsertReturning insert -> returning(
                    insert(insert.table(), insert.values()), insert.returning());
            case SqlCommand.FindById find -> new RenderedSql(
                    "SELECT * FROM " + quote(find.table()) + " WHERE " + quote(find.idColumn()) + " = ?",
                    List.of(find.id()));
            case SqlCommand.UpdateById update -> update(update);
            case SqlCommand.UpdateByIdReturning update -> returning(update(update.update()),
                    update.returning());
            case SqlCommand.DeleteById delete -> new RenderedSql(
                    "DELETE FROM " + quote(delete.table()) + " WHERE " + quote(delete.idColumn()) + " = ?",
                    List.of(delete.id()));
        });
    }

    @Override
    public RenderedSql render(SqlQuery query) {
        List<SqlValue> binds = new ArrayList<>();
        StringBuilder sql = new StringBuilder();
        switch (query.selection()) {
            case SqlQuery.Selection.Columns columns -> {
                sql.append("SELECT ").append(columns(columns.columns()))
                        .append(" FROM ").append(quote(query.table()));
                appendWhere(sql, binds, query);
                appendOrder(sql, query.orderBy());
                query.limit().ifPresent(value -> {
                    sql.append(" LIMIT ?");
                    binds.add(new SqlValue.Int32(value));
                });
                query.offset().ifPresent(value -> {
                    sql.append(" OFFSET ?");
                    binds.add(new SqlValue.Int32(value));
                });
            }
            case SqlQuery.Selection.Count count -> {
                sql.append("SELECT COUNT(*) AS \"count\" FROM ").append(quote(query.table()));
                appendWhere(sql, binds, query);
            }
            case SqlQuery.Selection.Exists exists -> {
                sql.append("SELECT EXISTS (SELECT 1 FROM ").append(quote(query.table()));
                appendWhere(sql, binds, query);
                sql.append(") AS \"exists\"");
            }
        }
        return checked(new RenderedSql(sql.toString(), binds));
    }

    private static RenderedSql checked(RenderedSql rendered) {
        if (rendered.binds().size() > MAX_BINDS) {
            throw new IllegalArgumentException("Statement exceeds the PostgreSQL limit of "
                    + MAX_BINDS + " bind parameters");
        }
        return rendered;
    }

    private static String columns(List<SqlIdentifier> columns) {
        StringJoiner joined = new StringJoiner(", ");
        for (SqlIdentifier column : columns) {
            joined.add(quote(column));
        }
        return joined.toString();
    }

    private static RenderedSql insert(SqlIdentifier table, List<SqlCommand.Assignment> values) {
        StringJoiner columns = new StringJoiner(", ");
        StringJoiner placeholders = new StringJoiner(", ");
        List<SqlValue> binds = new ArrayList<>();
        for (SqlCommand.Assignment assignment : values) {
            columns.add(quote(assignment.column()));
            placeholders.add("?");
            binds.add(assignment.value());
        }
        return new RenderedSql("INSERT INTO " + quote(table) + " (" + columns
                + ") VALUES (" + placeholders + ")", binds);
    }

    private static RenderedSql returning(RenderedSql statement, List<SqlIdentifier> columns) {
        return new RenderedSql(statement.text() + " RETURNING " + columns(columns), statement.binds());
    }

    private static RenderedSql update(SqlCommand.UpdateById command) {
        StringJoiner assignments = new StringJoiner(", ");
        List<SqlValue> binds = new ArrayList<>();
        for (SqlCommand.Assignment assignment : command.values()) {
            assignments.add(quote(assignment.column()) + " = ?");
            binds.add(assignment.value());
        }
        binds.add(command.id());
        return new RenderedSql("UPDATE " + quote(command.table()) + " SET " + assignments
                + " WHERE " + quote(command.idColumn()) + " = ?", binds);
    }

    private static void appendWhere(StringBuilder sql, List<SqlValue> binds, SqlQuery query) {
        query.condition().ifPresent(condition -> {
            sql.append(" WHERE ");
            appendCondition(sql, binds, condition);
        });
    }

    private static void appendCondition(StringBuilder sql, List<SqlValue> binds,
            SqlCondition condition) {
        switch (condition) {
            case SqlCondition.Comparison comparison -> appendComparison(sql, binds, comparison);
            case SqlCondition.NullCheck check -> sql.append(quote(check.column()))
                    .append(check.negated() ? " IS NOT NULL" : " IS NULL");
            case SqlCondition.And and -> appendJoined(sql, binds, " AND ", and.terms());
            case SqlCondition.Or or -> appendJoined(sql, binds, " OR ", or.terms());
            case SqlCondition.Not not -> {
                sql.append("NOT (");
                appendCondition(sql, binds, not.term());
                sql.append(')');
            }
        }
    }

    private static void appendComparison(StringBuilder sql, List<SqlValue> binds,
            SqlCondition.Comparison comparison) {
        sql.append(quote(comparison.column()));
        switch (comparison.operator()) {
            case EQ -> sql.append(" = ?");
            case NE -> sql.append(" <> ?");
            case LT -> sql.append(" < ?");
            case LTE -> sql.append(" <= ?");
            case GT -> sql.append(" > ?");
            case GTE -> sql.append(" >= ?");
            case BETWEEN -> sql.append(" BETWEEN ? AND ?");
            case IN, NOT_IN -> {
                sql.append(comparison.operator() == SqlCondition.Operator.IN ? " IN (" : " NOT IN (");
                sql.append("?, ".repeat(comparison.values().size() - 1)).append("?)");
            }
            case CONTAINS, STARTS_WITH, ENDS_WITH -> {
                sql.append(" LIKE ? ESCAPE '").append(LIKE_ESCAPE).append('\'');
                binds.add(new SqlValue.Text(pattern(comparison)));
                return;
            }
        }
        binds.addAll(comparison.values());
    }

    private static String pattern(SqlCondition.Comparison comparison) {
        String operand = ((SqlValue.Text) comparison.values().getFirst()).value();
        StringBuilder escaped = new StringBuilder(operand.length() + 2);
        for (int index = 0; index < operand.length(); index++) {
            char character = operand.charAt(index);
            if (character == LIKE_ESCAPE || character == '%' || character == '_') {
                escaped.append(LIKE_ESCAPE);
            }
            escaped.append(character);
        }
        return switch (comparison.operator()) {
            case CONTAINS -> "%" + escaped + "%";
            case STARTS_WITH -> escaped + "%";
            case ENDS_WITH -> "%" + escaped;
            default -> throw new IllegalStateException("Not a text pattern operator");
        };
    }

    private static void appendJoined(StringBuilder sql, List<SqlValue> binds, String separator,
            List<SqlCondition> terms) {
        sql.append('(');
        for (int index = 0; index < terms.size(); index++) {
            if (index > 0) {
                sql.append(separator);
            }
            appendCondition(sql, binds, terms.get(index));
        }
        sql.append(')');
    }

    private static void appendOrder(StringBuilder sql, List<SqlOrder> orders) {
        if (orders.isEmpty()) {
            return;
        }
        StringJoiner terms = new StringJoiner(", ", " ORDER BY ", "");
        for (SqlOrder order : orders) {
            terms.add(quote(order.column()) + " " + order.direction().name());
        }
        sql.append(terms);
    }
}
