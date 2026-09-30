package dev.tuprel.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.tuprel.sql.RenderedSql;
import dev.tuprel.sql.SqlCommand;
import dev.tuprel.sql.SqlCondition;
import dev.tuprel.sql.SqlIdentifier;
import dev.tuprel.sql.SqlOrder;
import dev.tuprel.sql.SqlQuery;
import dev.tuprel.sql.SqlValue;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PostgresqlRendererTest {
    private final PostgresqlRenderer renderer = new PostgresqlRenderer();
    private final SqlIdentifier table = new SqlIdentifier("Order");
    private final SqlIdentifier idColumn = new SqlIdentifier("id");
    private final SqlValue.Uuid id = new SqlValue.Uuid(UUID.fromString("e1642970-ad35-45d0-9be0-69d6cd20b607"));

    @Test
    void rendersCrudWithOrderedBindsAndQuotedIdentifiers() {
        String hostile = "Robert'); DROP TABLE users;--";
        SqlCommand.Assignment name = new SqlCommand.Assignment(new SqlIdentifier("name"),
                new SqlValue.Text(hostile));
        SqlCommand.Assignment age = new SqlCommand.Assignment(new SqlIdentifier("age"),
                new SqlValue.Int32(42));
        RenderedSql insert = renderer.render(new SqlCommand.Insert(table, List.of(name, age)));
        assertEquals("INSERT INTO \"Order\" (\"name\", \"age\") VALUES (?, ?)", insert.text());
        assertEquals(List.of(name.value(), age.value()), insert.binds());
        assertFalse(insert.text().contains(hostile));

        RenderedSql find = renderer.render(new SqlCommand.FindById(table, idColumn, id));
        assertEquals("SELECT * FROM \"Order\" WHERE \"id\" = ?", find.text());
        assertEquals(List.of(id), find.binds());

        RenderedSql update = renderer.render(new SqlCommand.UpdateById(table, idColumn, id, List.of(name)));
        assertEquals("UPDATE \"Order\" SET \"name\" = ? WHERE \"id\" = ?", update.text());
        assertEquals(List.of(name.value(), id), update.binds());

        RenderedSql delete = renderer.render(new SqlCommand.DeleteById(table, idColumn, id));
        assertEquals("DELETE FROM \"Order\" WHERE \"id\" = ?", delete.text());
        assertEquals(List.of(id), delete.binds());
    }

    @Test
    void rendersReturningWithExplicitColumnsInOneStatement() {
        SqlCommand.Assignment name = new SqlCommand.Assignment(new SqlIdentifier("name"),
                new SqlValue.Text("Ana"));
        List<SqlIdentifier> columns = List.of(idColumn, new SqlIdentifier("name"));
        RenderedSql insert = renderer.render(new SqlCommand.InsertReturning(table, List.of(name), columns));
        assertEquals("INSERT INTO \"Order\" (\"name\") VALUES (?) RETURNING \"id\", \"name\"",
                insert.text());
        assertEquals(List.of(name.value()), insert.binds());
        RenderedSql update = renderer.render(new SqlCommand.UpdateByIdReturning(
                table, idColumn, id, List.of(name), columns));
        assertEquals("UPDATE \"Order\" SET \"name\" = ? WHERE \"id\" = ? RETURNING \"id\", \"name\"",
                update.text());
        assertEquals(List.of(name.value(), id), update.binds());
    }

    @Test
    void rendersFiltersOrderingAndPagingWithAllValuesBound() {
        String hostile = "Robert'); DROP TABLE users;--";
        SqlIdentifier name = new SqlIdentifier("name");
        SqlIdentifier age = new SqlIdentifier("age");
        SqlCondition condition = new SqlCondition.And(List.of(
                new SqlCondition.Or(List.of(
                        new SqlCondition.Comparison(name, SqlCondition.Operator.EQ,
                                List.of(new SqlValue.Text(hostile))),
                        new SqlCondition.Comparison(name, SqlCondition.Operator.IN,
                                List.of(new SqlValue.Text("a"), new SqlValue.Text("b"))))),
                new SqlCondition.Comparison(age, SqlCondition.Operator.BETWEEN,
                        List.of(new SqlValue.Int32(18), new SqlValue.Int32(65))),
                new SqlCondition.Not(new SqlCondition.NullCheck(age, false))));
        RenderedSql query = renderer.render(SqlQuery.select(table, List.of(idColumn, name))
                .where(condition)
                .orderBy(List.of(new SqlOrder(age, SqlOrder.Direction.DESC),
                        new SqlOrder(idColumn, SqlOrder.Direction.ASC)))
                .limit(20)
                .offset(5));
        assertEquals("SELECT \"id\", \"name\" FROM \"Order\" WHERE ((\"name\" = ? OR \"name\" IN (?, ?))"
                + " AND \"age\" BETWEEN ? AND ? AND NOT (\"age\" IS NULL))"
                + " ORDER BY \"age\" DESC, \"id\" ASC LIMIT ? OFFSET ?", query.text());
        assertEquals(List.of(new SqlValue.Text(hostile), new SqlValue.Text("a"), new SqlValue.Text("b"),
                new SqlValue.Int32(18), new SqlValue.Int32(65), new SqlValue.Int32(20),
                new SqlValue.Int32(5)), query.binds());
        assertFalse(query.text().contains("DROP"));
    }

    @Test
    void rendersEveryComparisonOperator() {
        SqlIdentifier age = new SqlIdentifier("age");
        SqlValue one = new SqlValue.Int32(1);
        List<String> rendered = new ArrayList<>();
        for (SqlCondition.Operator operator : List.of(SqlCondition.Operator.EQ, SqlCondition.Operator.NE,
                SqlCondition.Operator.LT, SqlCondition.Operator.LTE, SqlCondition.Operator.GT,
                SqlCondition.Operator.GTE, SqlCondition.Operator.NOT_IN)) {
            rendered.add(renderer.render(SqlQuery.count(table)
                    .where(new SqlCondition.Comparison(age, operator, List.of(one)))).text());
        }
        rendered.add(renderer.render(SqlQuery.count(table)
                .where(new SqlCondition.NullCheck(age, true))).text());
        String prefix = "SELECT COUNT(*) AS \"count\" FROM \"Order\" WHERE ";
        assertEquals(List.of(prefix + "\"age\" = ?", prefix + "\"age\" <> ?", prefix + "\"age\" < ?",
                prefix + "\"age\" <= ?", prefix + "\"age\" > ?", prefix + "\"age\" >= ?",
                prefix + "\"age\" NOT IN (?)", prefix + "\"age\" IS NOT NULL"), rendered);
        RenderedSql exists = renderer.render(SqlQuery.exists(table)
                .where(new SqlCondition.Comparison(age, SqlCondition.Operator.GT, List.of(one))));
        assertEquals("SELECT EXISTS (SELECT 1 FROM \"Order\" WHERE \"age\" > ?) AS \"exists\"",
                exists.text());
        assertEquals(List.of(one), exists.binds());
    }

    @Test
    void escapesLikeWildcardsInBoundPatternsWithoutBackslashLiterals() {
        SqlIdentifier name = new SqlIdentifier("name");
        String operand = "50%_off!'--";
        List<String> patterns = new ArrayList<>();
        for (SqlCondition.Operator operator : List.of(SqlCondition.Operator.CONTAINS,
                SqlCondition.Operator.STARTS_WITH, SqlCondition.Operator.ENDS_WITH)) {
            RenderedSql rendered = renderer.render(SqlQuery.count(table).where(
                    new SqlCondition.Comparison(name, operator, List.of(new SqlValue.Text(operand)))));
            assertEquals("SELECT COUNT(*) AS \"count\" FROM \"Order\" WHERE \"name\" LIKE ? ESCAPE '!'",
                    rendered.text());
            patterns.add(((SqlValue.Text) rendered.binds().getFirst()).value());
        }
        assertEquals(List.of("%50!%!_off!!'--%", "50!%!_off!!'--%", "%50!%!_off!!'--"), patterns);
    }

    @Test
    void rejectsStatementsBeyondThePostgresqlBindLimit() {
        List<SqlValue> values = new ArrayList<>();
        for (int index = 0; index <= PostgresqlRenderer.MAX_BINDS; index++) {
            values.add(new SqlValue.Int32(index));
        }
        SqlQuery query = SqlQuery.count(table).where(
                new SqlCondition.Comparison(idColumn, SqlCondition.Operator.IN, values));
        assertThrows(IllegalArgumentException.class, () -> renderer.render(query));
    }
}
