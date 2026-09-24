package dev.tuprel.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
}
