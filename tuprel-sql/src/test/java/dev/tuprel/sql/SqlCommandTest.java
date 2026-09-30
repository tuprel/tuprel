package dev.tuprel.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SqlCommandTest {
    @Test
    void rejectsStructuralInjectionAndDuplicateColumns() {
        assertThrows(IllegalArgumentException.class, () -> new SqlIdentifier("users; DROP TABLE users"));
        assertThrows(IllegalArgumentException.class, () -> new SqlIdentifier("schema.users"));
        SqlIdentifier column = new SqlIdentifier("name");
        List<SqlCommand.Assignment> values = List.of(
                new SqlCommand.Assignment(column, new SqlValue.Text("first")),
                new SqlCommand.Assignment(column, new SqlValue.Text("second")));
        assertThrows(IllegalArgumentException.class,
                () -> new SqlCommand.Insert(new SqlIdentifier("users"), values));
    }

    @Test
    void copiesAssignmentsAndBinaryInput() {
        byte[] input = {1, 2};
        SqlValue.Binary binary = new SqlValue.Binary(input);
        input[0] = 9;
        assertEquals(1, binary.value()[0]);
        byte[] output = binary.value();
        output[1] = 9;
        assertEquals(2, binary.value()[1]);

        List<SqlCommand.Assignment> mutable = new ArrayList<>();
        mutable.add(new SqlCommand.Assignment(new SqlIdentifier("data"), binary));
        SqlCommand.Insert command = new SqlCommand.Insert(new SqlIdentifier("users"), mutable);
        mutable.clear();
        assertEquals(1, command.values().size());
    }

    @Test
    void rejectsNullIdentifiersAndUpdatesToIdentifierColumn() {
        SqlIdentifier table = new SqlIdentifier("users");
        SqlIdentifier id = new SqlIdentifier("id");
        assertThrows(IllegalArgumentException.class,
                () -> new SqlCommand.FindById(table, id, new SqlValue.Null(SqlValue.Type.UUID)));
        assertThrows(IllegalArgumentException.class,
                () -> new SqlCommand.UpdateById(table, id, new SqlValue.Uuid(java.util.UUID.randomUUID()),
                        List.of(new SqlCommand.Assignment(id, new SqlValue.Text("unsafe")))));
    }

    @Test
    void updateReturningKeepsUpdateInvariantsAndRequiresReturnedColumns() {
        SqlIdentifier table = new SqlIdentifier("users");
        SqlIdentifier id = new SqlIdentifier("id");
        SqlValue idValue = new SqlValue.Int32(1);
        List<SqlCommand.Assignment> rename = List.of(
                new SqlCommand.Assignment(new SqlIdentifier("name"), new SqlValue.Text("a")));
        assertThrows(IllegalArgumentException.class, () -> new SqlCommand.UpdateByIdReturning(
                table, id, idValue, List.of(new SqlCommand.Assignment(id, idValue)), List.of(id)));
        assertThrows(IllegalArgumentException.class, () -> new SqlCommand.UpdateByIdReturning(
                table, id, new SqlValue.Null(SqlValue.Type.INT32), rename, List.of(id)));
        assertThrows(IllegalArgumentException.class,
                () -> new SqlCommand.UpdateByIdReturning(table, id, idValue, rename, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new SqlCommand.InsertReturning(table, rename, List.of(id, id)));
        assertEquals(new SqlCommand.UpdateById(table, id, idValue, rename),
                new SqlCommand.UpdateByIdReturning(table, id, idValue, rename, List.of(id)).update());
    }

    @Test
    void conditionsRejectNullComparisonsWrongArityAndNonTextPatterns() {
        SqlIdentifier name = new SqlIdentifier("name");
        SqlValue text = new SqlValue.Text("x");
        assertThrows(IllegalArgumentException.class, () -> new SqlCondition.Comparison(name,
                SqlCondition.Operator.EQ, List.of(new SqlValue.Null(SqlValue.Type.TEXT))));
        assertThrows(IllegalArgumentException.class, () -> new SqlCondition.Comparison(name,
                SqlCondition.Operator.IN, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new SqlCondition.Comparison(name,
                SqlCondition.Operator.BETWEEN, List.of(text)));
        assertThrows(IllegalArgumentException.class, () -> new SqlCondition.Comparison(name,
                SqlCondition.Operator.EQ, List.of(text, text)));
        assertThrows(IllegalArgumentException.class, () -> new SqlCondition.Comparison(name,
                SqlCondition.Operator.CONTAINS, List.of(new SqlValue.Int32(1))));
        assertThrows(IllegalArgumentException.class, () -> new SqlCondition.And(List.of()));
        assertThrows(IllegalArgumentException.class, () -> new SqlCondition.Or(List.of()));
    }

    @Test
    void queriesRequireExplicitColumnsValidPagingAndUnpagedAggregates() {
        SqlIdentifier table = new SqlIdentifier("users");
        SqlIdentifier id = new SqlIdentifier("id");
        assertThrows(IllegalArgumentException.class, () -> SqlQuery.select(table, List.of()));
        assertThrows(IllegalArgumentException.class, () -> SqlQuery.select(table, List.of(id, id)));
        assertThrows(IllegalArgumentException.class, () -> SqlQuery.select(table, List.of(id)).limit(0));
        assertThrows(IllegalArgumentException.class, () -> SqlQuery.select(table, List.of(id)).offset(-1));
        assertThrows(IllegalArgumentException.class, () -> SqlQuery.count(table).limit(1));
        assertThrows(IllegalArgumentException.class, () -> SqlQuery.exists(table)
                .orderBy(List.of(new SqlOrder(id, SqlOrder.Direction.ASC))));
    }

    @Test
    void renderedSqlToStringRedactsBoundValues() {
        RenderedSql rendered = new RenderedSql("SELECT 1 WHERE ? = ?",
                List.of(new SqlValue.Text("secret-token"), new SqlValue.Int32(42)));
        assertFalse(rendered.toString().contains("secret-token"));
        assertFalse(rendered.toString().contains("42"));
        assertTrue(rendered.toString().contains("2 redacted"));
    }
}
