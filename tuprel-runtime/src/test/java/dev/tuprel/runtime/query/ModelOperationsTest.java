package dev.tuprel.runtime.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuprel.runtime.RowReader;
import dev.tuprel.runtime.TuprelDatabase;
import dev.tuprel.runtime.TuprelDatabaseException;
import dev.tuprel.sql.RenderedSql;
import dev.tuprel.sql.SqlCommand;
import dev.tuprel.sql.SqlCondition;
import dev.tuprel.sql.SqlIdentifier;
import dev.tuprel.sql.SqlOrder;
import dev.tuprel.sql.SqlQuery;
import dev.tuprel.sql.SqlRenderer;
import dev.tuprel.sql.SqlValue;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class ModelOperationsTest {
    private record Person(long id, String name, Integer age) { }

    private static final ComparableField<Person, Long> ID = ComparableField.int64("id", false, Person::id);
    private static final TextField<Person> NAME = TextField.text("name", false, Person::name);
    private static final ComparableField<Person, Integer> AGE = ComparableField.int32("age", true, Person::age);
    private static final ModelTable<Person, Long> TABLE = ModelTable.of("person", ID, List.of(ID, NAME, AGE),
            row -> new Person(ID.read(row), NAME.read(row), AGE.read(row)));

    private static final SqlIdentifier C_ID = new SqlIdentifier("id");
    private static final SqlIdentifier C_NAME = new SqlIdentifier("name");
    private static final SqlIdentifier C_AGE = new SqlIdentifier("age");
    private static final SqlIdentifier C_TABLE = new SqlIdentifier("person");

    @Test
    void fieldsBuildTypedBoundPredicatesAndRejectNullValues() {
        assertEquals(new SqlCondition.Comparison(C_NAME, SqlCondition.Operator.CONTAINS,
                List.of(new SqlValue.Text("50%"))), NAME.contains("50%").toSql());
        assertEquals(new SqlCondition.Comparison(C_AGE, SqlCondition.Operator.BETWEEN,
                List.of(new SqlValue.Int32(1), new SqlValue.Int32(9))), AGE.between(1, 9).toSql());
        assertEquals(new SqlCondition.Comparison(C_ID, SqlCondition.Operator.NOT_IN,
                List.of(new SqlValue.Int64(1), new SqlValue.Int64(2))), ID.notIn(List.of(1L, 2L)).toSql());
        assertEquals(new SqlCondition.NullCheck(C_AGE, false), AGE.isNull().toSql());
        assertEquals(new SqlCondition.NullCheck(C_AGE, true), AGE.isNotNull().toSql());
        assertThrows(NullPointerException.class, () -> NAME.eq(null));
        assertThrows(NullPointerException.class, () -> AGE.between(1, null));
        assertThrows(IllegalArgumentException.class, () -> NAME.in(List.of()));
        List<String> withNull = new ArrayList<>();
        withNull.add(null);
        assertThrows(NullPointerException.class, () -> NAME.in(withNull));
    }

    @Test
    void conditionsComposeStructurallyAndRedactToString() {
        Condition<Person> adult = AGE.gte(18);
        Condition<Person> named = NAME.startsWith("A");
        assertEquals(new SqlCondition.And(List.of(adult.toSql(), named.toSql())), adult.and(named).toSql());
        assertEquals(new SqlCondition.Or(List.of(adult.toSql(), named.toSql())), adult.or(named).toSql());
        assertEquals(new SqlCondition.Not(adult.toSql()), adult.not().toSql());
        assertEquals(new SqlCondition.Or(List.of(adult.toSql(), named.toSql())),
                Condition.anyOf(adult, named).toSql());
        assertEquals(new SqlCondition.And(List.of(adult.toSql())), Condition.allOf(adult).toSql());
        assertThrows(IllegalArgumentException.class, Condition::<Person>anyOf);
        assertFalse(NAME.eq("secret-value").toString().contains("secret"));
    }

    @Test
    void queriesAreImmutableAndAccumulateConditionsAndOrdering() {
        Query<Person> base = Query.all();
        Query<Person> filtered = base.where(AGE.gt(1)).where(NAME.eq("a")).orderBy(NAME.asc()).orderBy(ID.desc());
        assertTrue(base.condition().isEmpty());
        assertTrue(base.orders().isEmpty());
        assertEquals(AGE.gt(1).and(NAME.eq("a")), filtered.condition().orElseThrow());
        assertEquals(List.of(NAME.asc(), ID.desc()), filtered.orders());
        assertThrows(IllegalArgumentException.class, () -> base.skip(-1));
        assertThrows(IllegalArgumentException.class, () -> base.take(0));
        assertEquals(new SqlOrder(C_NAME, SqlOrder.Direction.DESC), NAME.desc().toSql());
    }

    @Test
    void fieldsBindNullOnlyForNullableColumnsAndReadRequiredColumnsStrictly() {
        assertEquals(new SqlValue.Null(SqlValue.Type.INT32), AGE.bind(null));
        assertThrows(IllegalArgumentException.class, () -> NAME.bind(null));
        FakeRow row = new FakeRow();
        row.values.put("name", null);
        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> NAME.read(row));
        assertEquals("Column \"name\" returned NULL for a required field", exception.getMessage());
        assertNull(AGE.read(row));
        row.values.put("role", "UNKNOWN; DROP");
        Field<Person, Thread.State> state = Field.enumeration("role", false, person -> Thread.State.NEW,
                Thread.State::valueOf);
        IllegalStateException enumFailure = assertThrows(IllegalStateException.class, () -> state.read(row));
        assertFalse(enumFailure.getMessage().contains("DROP"));
    }

    @Test
    void everyScalarFactoryUsesTheMatchingBoundType() {
        assertEquals(new SqlValue.Int16((short) 1),
                ComparableField.<Person>int16("a", false, p -> (short) 1).bind((short) 1));
        assertEquals(new SqlValue.Float32(1f), ComparableField.<Person>float32("a", false, p -> 1f).bind(1f));
        assertEquals(new SqlValue.Float64(1d), ComparableField.<Person>float64("a", false, p -> 1d).bind(1d));
        assertEquals(new SqlValue.Decimal(BigDecimal.TEN),
                ComparableField.<Person>decimal("a", false, p -> BigDecimal.TEN).bind(BigDecimal.TEN));
        Instant instant = Instant.EPOCH;
        assertEquals(new SqlValue.Timestamp(instant),
                ComparableField.<Person>instant("a", false, p -> instant).bind(instant));
        LocalDateTime dateTime = LocalDateTime.of(2026, 1, 2, 3, 4);
        assertEquals(new SqlValue.DateTime(dateTime),
                ComparableField.<Person>dateTime("a", false, p -> dateTime).bind(dateTime));
        assertEquals(new SqlValue.Date(LocalDate.EPOCH),
                ComparableField.<Person>date("a", false, p -> LocalDate.EPOCH).bind(LocalDate.EPOCH));
        assertEquals(new SqlValue.Time(LocalTime.NOON),
                ComparableField.<Person>time("a", false, p -> LocalTime.NOON).bind(LocalTime.NOON));
        assertEquals(new SqlValue.Bool(true), Field.<Person>bool("a", false, p -> true).bind(true));
        UUID uuid = UUID.randomUUID();
        assertEquals(new SqlValue.Uuid(uuid), Field.<Person>uuid("a", false, p -> uuid).bind(uuid));
        assertEquals(new SqlValue.Text("RUNNABLE"), Field.<Person, Thread.State>enumeration("a", false,
                p -> Thread.State.RUNNABLE, Thread.State::valueOf).bind(Thread.State.RUNNABLE));
        assertEquals(new SqlValue.Binary(new byte[] {1}), Field.<Person, List<Byte>>bytes("a", true, p -> null,
                bytes -> List.of(bytes[0]), list -> new byte[] {list.getFirst()}).bind(List.of((byte) 1)));
        assertThrows(IllegalArgumentException.class, () -> TextField.<Person>text("bad name", false, Person::name));
    }

    @Test
    void assignmentsKeepPresenceOrderAndRejectDuplicates() {
        Assignments<Person> values = Assignments.<Person>empty().set(NAME, "Ana").set(AGE, null);
        assertEquals(List.of(new SqlCommand.Assignment(C_NAME, new SqlValue.Text("Ana")),
                new SqlCommand.Assignment(C_AGE, new SqlValue.Null(SqlValue.Type.INT32))), values.toSql());
        assertTrue(Assignments.<Person>empty().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> values.set(NAME, "Bo"));
        assertFalse(values.toString().contains("Ana"));
    }

    @Test
    void modelTablesAndProjectionsValidateTheirFields() {
        assertThrows(IllegalArgumentException.class, () -> ModelTable.of("person", ID, List.of(NAME),
                row -> new Person(1, "a", null)));
        assertThrows(IllegalArgumentException.class, () -> ModelTable.of("person", ID, List.of(ID, ID),
                row -> new Person(1, "a", null)));
        ComparableField<Person, Integer> nullableId = ComparableField.int32("pk", true, Person::age);
        assertThrows(IllegalArgumentException.class, () -> ModelTable.of("person", nullableId,
                List.of(nullableId), row -> new Person(1, "a", null)));
        assertThrows(IllegalArgumentException.class, () -> Projection.of(List.<Field<Person, ?>>of(),
                row -> "x"));
        assertThrows(IllegalArgumentException.class, () -> Projection.of(List.of(NAME, NAME), row -> "x"));
        FakeRow row = new FakeRow();
        row.values.put("name", "Ana");
        ProjectedRow<Person> projected = new ProjectedRow<>(row, Set.of(C_NAME));
        assertEquals("Ana", projected.get(NAME));
        assertThrows(IllegalArgumentException.class, () -> projected.get(AGE));
    }

    @Test
    void rendersExplicitColumnsConditionsOrderingAndPaging() {
        Recorder recorder = new Recorder();
        ModelOperations<Person, Long> people = recorder.operations();
        people.findMany(Query.<Person>all().where(NAME.eq("x")).orderBy(AGE.desc()).skip(5).take(10));
        assertEquals(SqlQuery.select(C_TABLE, List.of(C_ID, C_NAME, C_AGE))
                .where(NAME.eq("x").toSql())
                .orderBy(List.of(new SqlOrder(C_AGE, SqlOrder.Direction.DESC)))
                .limit(10).offset(5), recorder.queries.getLast());
        people.findFirst(Query.<Person>all().orderBy(NAME.asc()));
        assertEquals(Optional.of(1), recorder.queries.getLast().limit());
        people.findById(7L);
        assertEquals(SqlQuery.select(C_TABLE, List.of(C_ID, C_NAME, C_AGE)).where(ID.eq(7L).toSql()),
                recorder.queries.getLast());
        people.select(Projection.of(List.of(NAME), row -> row.get(NAME)), Query.<Person>all().take(3));
        assertEquals(SqlQuery.select(C_TABLE, List.of(C_NAME)).limit(3), recorder.queries.getLast());
        // The fake driver returns no row, which an aggregate must report as a runtime failure.
        TuprelDatabaseException noRow = assertThrows(TuprelDatabaseException.class,
                () -> people.count(Query.<Person>all().where(AGE.isNull())));
        assertEquals(TuprelDatabaseException.Phase.EXECUTION, noRow.phase());
        assertEquals(SqlQuery.count(C_TABLE).where(AGE.isNull().toSql()), recorder.queries.getLast());
        assertThrows(TuprelDatabaseException.class, () -> people.exists(Query.all()));
        assertEquals(SqlQuery.exists(C_TABLE), recorder.queries.getLast());
        assertEquals(recorder.preview, people.preview(Query.all()));
        assertEquals(SqlQuery.select(C_TABLE, List.of(C_ID, C_NAME, C_AGE)), recorder.queries.getLast());
    }

    @Test
    void writesRunOneReturningStatementAndValidateInputs() {
        Recorder recorder = new Recorder();
        ModelOperations<Person, Long> people = recorder.operations();
        assertThrows(IllegalArgumentException.class, () -> people.create(Assignments.empty()));
        assertThrows(IllegalArgumentException.class, () -> people.updateById(1L, Assignments.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> people.updateById(1L, Assignments.<Person>empty().set(ID, 2L)));
        assertThrows(NullPointerException.class, () -> people.findById(null));
        assertEquals(0, recorder.connections, "Rejected inputs must not open connections");
        assertTrue(people.updateById(1L, Assignments.<Person>empty().set(NAME, "b")).isEmpty());
        assertEquals(1, recorder.connections, "An update is one statement on one connection");
        assertEquals(new SqlCommand.UpdateByIdReturning(C_TABLE, C_ID, new SqlValue.Int64(1),
                List.of(new SqlCommand.Assignment(C_NAME, new SqlValue.Text("b"))), List.of(C_ID, C_NAME, C_AGE)),
                recorder.commands.getLast());
    }

    @Test
    void countAndExistsRejectOrderingPagingAndCursors() {
        ModelOperations<Person, Long> people = new Recorder().operations();
        assertThrows(IllegalArgumentException.class, () -> people.count(Query.<Person>all().take(1)));
        assertThrows(IllegalArgumentException.class, () -> people.count(Query.<Person>all().skip(1)));
        assertThrows(IllegalArgumentException.class, () -> people.exists(Query.<Person>all().orderBy(ID.asc())));
        Cursor cursor = new Cursor("person:id+", List.of(new SqlValue.Int64(1)));
        assertThrows(IllegalArgumentException.class, () -> people.exists(Query.<Person>all().after(cursor)));
        assertThrows(IllegalArgumentException.class, () -> people.findMany(Query.<Person>all().after(cursor)));
    }

    @Test
    void cursorPaginationUsesADeterministicKeysetOnTheIdentifier() {
        Recorder recorder = new Recorder();
        ModelOperations<Person, Long> people = recorder.operations();
        people.findManyCursor(Query.<Person>all().take(2));
        assertEquals(SqlQuery.select(C_TABLE, List.of(C_ID, C_NAME, C_AGE))
                .orderBy(List.of(new SqlOrder(C_ID, SqlOrder.Direction.ASC))).limit(3), recorder.queries.getLast());

        Cursor cursor = new Cursor("person:name-,id+", List.of(new SqlValue.Text("M"), new SqlValue.Int64(9)));
        people.findManyCursor(Query.<Person>all().where(AGE.gt(1)).orderBy(NAME.desc(), ID.asc())
                .after(Cursor.decode(cursor.encode())).take(2));
        SqlCondition keyset = new SqlCondition.Or(List.of(
                new SqlCondition.Comparison(C_NAME, SqlCondition.Operator.LT, List.of(new SqlValue.Text("M"))),
                new SqlCondition.And(List.of(
                        new SqlCondition.Comparison(C_NAME, SqlCondition.Operator.EQ, List.of(new SqlValue.Text("M"))),
                        new SqlCondition.Comparison(C_ID, SqlCondition.Operator.GT, List.of(new SqlValue.Int64(9)))))));
        assertEquals(SqlQuery.select(C_TABLE, List.of(C_ID, C_NAME, C_AGE))
                .where(new SqlCondition.And(List.of(AGE.gt(1).toSql(), keyset)))
                .orderBy(List.of(new SqlOrder(C_NAME, SqlOrder.Direction.DESC),
                        new SqlOrder(C_ID, SqlOrder.Direction.ASC)))
                .limit(3), recorder.queries.getLast());
    }

    @Test
    void cursorPaginationRejectsAmbiguousOrderingAndForeignCursors() {
        Recorder recorder = new Recorder();
        ModelOperations<Person, Long> people = recorder.operations();
        assertThrows(IllegalArgumentException.class, () -> people.findManyCursor(Query.all()));
        assertThrows(IllegalArgumentException.class,
                () -> people.findManyCursor(Query.<Person>all().take(1).skip(1)));
        assertThrows(IllegalArgumentException.class,
                () -> people.findManyCursor(Query.<Person>all().take(1).orderBy(NAME.asc())));
        assertThrows(IllegalArgumentException.class,
                () -> people.findManyCursor(Query.<Person>all().take(1).orderBy(AGE.asc(), ID.asc())));
        assertThrows(IllegalArgumentException.class,
                () -> people.findManyCursor(Query.<Person>all().take(1).orderBy(ID.asc(), ID.desc())));
        Cursor otherOrdering = new Cursor("person:id-", List.of(new SqlValue.Int64(1)));
        assertThrows(IllegalArgumentException.class,
                () -> people.findManyCursor(Query.<Person>all().take(1).after(otherOrdering)));
        Cursor otherTable = new Cursor("account:id+", List.of(new SqlValue.Int64(1)));
        assertThrows(IllegalArgumentException.class,
                () -> people.findManyCursor(Query.<Person>all().take(1).after(otherTable)));
        Cursor wrongType = new Cursor("person:id+", List.of(new SqlValue.Text("1")));
        assertThrows(IllegalArgumentException.class,
                () -> people.findManyCursor(Query.<Person>all().take(1).after(wrongType)));
        assertEquals(0, recorder.connections);
    }

    /** Records structural statements and answers every query with zero rows. */
    private static final class Recorder implements SqlRenderer {
        private final List<SqlQuery> queries = new ArrayList<>();
        private final List<SqlCommand> commands = new ArrayList<>();
        private final RenderedSql preview = new RenderedSql("SELECT 1", List.of());
        private int connections;

        ModelOperations<Person, Long> operations() {
            return new ModelOperations<>(new TuprelDatabase(dataSource(), this, (statement, index, value) -> { }),
                    TABLE);
        }

        @Override
        public RenderedSql render(SqlCommand command) {
            commands.add(command);
            return preview;
        }

        @Override
        public RenderedSql render(SqlQuery query) {
            queries.add(query);
            return preview;
        }

        private DataSource dataSource() {
            ResultSet empty = proxy(ResultSet.class, (name) -> switch (name) {
                case "next" -> false;
                default -> null;
            });
            PreparedStatement statement = proxy(PreparedStatement.class, (name) -> switch (name) {
                case "executeQuery" -> empty;
                case "executeUpdate" -> 0;
                default -> null;
            });
            Connection connection = proxy(Connection.class, (name) -> switch (name) {
                case "getAutoCommit" -> true;
                case "prepareStatement" -> statement;
                default -> null;
            });
            return proxy(DataSource.class, (name) -> {
                if (name.equals("getConnection")) {
                    connections++;
                    return connection;
                }
                throw new UnsupportedOperationException(name);
            });
        }

        private interface Answer {
            Object answer(String method);
        }

        private static <T> T proxy(Class<T> type, Answer answer) {
            return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                    (proxy, method, arguments) -> answer.answer(method.getName())));
        }
    }

    private static final class FakeRow implements RowReader {
        private final Map<String, Object> values = new HashMap<>();

        private <T> T value(String column, Class<T> type) {
            return type.cast(values.get(column));
        }

        @Override public String text(String column) { return value(column, String.class); }
        @Override public Short int16(String column) { return value(column, Short.class); }
        @Override public Integer int32(String column) { return value(column, Integer.class); }
        @Override public Long int64(String column) { return value(column, Long.class); }
        @Override public Float float32(String column) { return value(column, Float.class); }
        @Override public Double float64(String column) { return value(column, Double.class); }
        @Override public BigDecimal decimal(String column) { return value(column, BigDecimal.class); }
        @Override public Boolean bool(String column) { return value(column, Boolean.class); }
        @Override public UUID uuid(String column) { return value(column, UUID.class); }
        @Override public Instant instant(String column) { return value(column, Instant.class); }
        @Override public LocalDateTime dateTime(String column) { return value(column, LocalDateTime.class); }
        @Override public LocalDate date(String column) { return value(column, LocalDate.class); }
        @Override public LocalTime time(String column) { return value(column, LocalTime.class); }
        @Override public byte[] bytes(String column) { return value(column, byte[].class); }
    }
}
