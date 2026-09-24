package dev.tuprel.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuprel.runtime.TuprelDatabase;
import dev.tuprel.runtime.TuprelDatabaseException;
import dev.tuprel.sql.SqlCommand;
import dev.tuprel.sql.SqlIdentifier;
import dev.tuprel.sql.SqlValue;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

class PostgresqlDatabaseIntegrationTest {
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-trixie");
    private static final SqlIdentifier TABLE = new SqlIdentifier("runtime_probe");
    private static final SqlIdentifier ID = new SqlIdentifier("id");
    private static PGSimpleDataSource dataSource;
    private static TuprelDatabase database;

    @BeforeAll
    static void startDatabase() throws SQLException {
        POSTGRES.start();
        dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        database = PostgresqlDatabase.using(dataSource);
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE runtime_probe (
                        id UUID PRIMARY KEY, optional_uuid UUID, payload TEXT, short_value SMALLINT,
                        int_value INTEGER, long_value BIGINT, float_value REAL,
                        double_value DOUBLE PRECISION, decimal_value NUMERIC(20,4),
                        enabled BOOLEAN, event_time TIMESTAMPTZ, local_time TIMESTAMP,
                        calendar_day DATE, clock_time TIME, blob BYTEA
                    )
                    """);
        }
    }

    @AfterAll
    static void stopDatabase() {
        POSTGRES.stop();
    }

    @BeforeEach
    void clearTable() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE runtime_probe");
        }
    }

    @Test
    void crudAndInjectionPayloadAreBoundData() throws SQLException {
        UUID id = UUID.randomUUID();
        String hostile = "Robert'); DROP TABLE runtime_probe;--";
        assertEquals(1, database.create(insert(id, new SqlCommand.Assignment(
                new SqlIdentifier("payload"), new SqlValue.Text(hostile)))));
        assertEquals(hostile, database.findById(find(id), row -> row.text("payload")).orElseThrow());

        assertEquals(1, database.updateById(new SqlCommand.UpdateById(TABLE, ID, new SqlValue.Uuid(id),
                List.of(new SqlCommand.Assignment(new SqlIdentifier("payload"),
                        new SqlValue.Text("updated"))))));
        assertEquals("updated", database.findById(find(id), row -> row.text("payload")).orElseThrow());

        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT count(*) FROM runtime_probe")) {
            assertTrue(result.next());
            assertEquals(1, result.getInt(1));
        }

        assertEquals(1, database.deleteById(new SqlCommand.DeleteById(TABLE, ID, new SqlValue.Uuid(id))));
        assertFalse(database.findById(find(id), row -> row.text("payload")).isPresent());
    }

    @Test
    void scalarRoundTripAndTypedNull() {
        UUID id = UUID.randomUUID();
        Instant instant = Instant.parse("2026-09-24T12:34:56Z");
        LocalDateTime dateTime = LocalDateTime.of(2026, 9, 24, 12, 34, 56);
        LocalDate date = LocalDate.of(2026, 9, 24);
        LocalTime time = LocalTime.of(12, 34, 56);
        byte[] bytes = {0, 1, -1};
        database.create(new SqlCommand.Insert(TABLE, List.of(
                assignment("id", new SqlValue.Uuid(id)),
                assignment("payload", new SqlValue.Text("Olá, 世界")),
                assignment("short_value", new SqlValue.Int16((short) 12)),
                assignment("int_value", new SqlValue.Int32(34)),
                assignment("long_value", new SqlValue.Int64(56L)),
                assignment("float_value", new SqlValue.Float32(1.5f)),
                assignment("double_value", new SqlValue.Float64(2.5d)),
                assignment("decimal_value", new SqlValue.Decimal(new BigDecimal("123.4500"))),
                assignment("enabled", new SqlValue.Bool(true)),
                assignment("event_time", new SqlValue.Timestamp(instant)),
                assignment("local_time", new SqlValue.DateTime(dateTime)),
                assignment("calendar_day", new SqlValue.Date(date)),
                assignment("clock_time", new SqlValue.Time(time)),
                assignment("blob", new SqlValue.Binary(bytes)))));
        Optional<ScalarRow> loaded = database.findById(find(id), row -> new ScalarRow(
                row.uuid("id"), row.text("payload"), row.int16("short_value"),
                row.int32("int_value"), row.int64("long_value"), row.float32("float_value"),
                row.float64("double_value"), row.decimal("decimal_value"), row.bool("enabled"),
                row.instant("event_time"), row.dateTime("local_time"), row.date("calendar_day"),
                row.time("clock_time"), new SqlValue.Binary(row.bytes("blob"))));
        ScalarRow value = loaded.orElseThrow();
        assertEquals(id, value.id());
        assertEquals("Olá, 世界", value.payload());
        assertEquals((short) 12, value.shortValue());
        assertEquals(34, value.intValue());
        assertEquals(56L, value.longValue());
        assertEquals(1.5f, value.floatValue());
        assertEquals(2.5d, value.doubleValue());
        assertEquals(new BigDecimal("123.4500"), value.decimalValue());
        assertEquals(true, value.enabled());
        assertEquals(instant, value.instant());
        assertEquals(dateTime, value.dateTime());
        assertEquals(date, value.date());
        assertEquals(time, value.time());
        assertEquals(new SqlValue.Binary(bytes), value.bytes());

    }

    @Test
    void typedNullBindingsRoundTrip() {
        UUID id = UUID.randomUUID();
        database.create(new SqlCommand.Insert(TABLE, List.of(
                assignment("id", new SqlValue.Uuid(id)),
                assignment("optional_uuid", new SqlValue.Null(SqlValue.Type.UUID)),
                assignment("payload", new SqlValue.Null(SqlValue.Type.TEXT)),
                assignment("short_value", new SqlValue.Null(SqlValue.Type.INT16)),
                assignment("int_value", new SqlValue.Null(SqlValue.Type.INT32)),
                assignment("long_value", new SqlValue.Null(SqlValue.Type.INT64)),
                assignment("float_value", new SqlValue.Null(SqlValue.Type.FLOAT32)),
                assignment("double_value", new SqlValue.Null(SqlValue.Type.FLOAT64)),
                assignment("decimal_value", new SqlValue.Null(SqlValue.Type.DECIMAL)),
                assignment("enabled", new SqlValue.Null(SqlValue.Type.BOOLEAN)),
                assignment("event_time", new SqlValue.Null(SqlValue.Type.INSTANT)),
                assignment("local_time", new SqlValue.Null(SqlValue.Type.LOCAL_DATE_TIME)),
                assignment("calendar_day", new SqlValue.Null(SqlValue.Type.LOCAL_DATE)),
                assignment("clock_time", new SqlValue.Null(SqlValue.Type.LOCAL_TIME)),
                assignment("blob", new SqlValue.Null(SqlValue.Type.BYTES)))));
        assertEquals(id, database.findById(find(id), row -> {
            assertNull(row.uuid("optional_uuid"));
            assertNull(row.text("payload"));
            assertNull(row.int16("short_value"));
            assertNull(row.int32("int_value"));
            assertNull(row.int64("long_value"));
            assertNull(row.float32("float_value"));
            assertNull(row.float64("double_value"));
            assertNull(row.decimal("decimal_value"));
            assertNull(row.bool("enabled"));
            assertNull(row.instant("event_time"));
            assertNull(row.dateTime("local_time"));
            assertNull(row.date("calendar_day"));
            assertNull(row.time("clock_time"));
            assertNull(row.bytes("blob"));
            return row.uuid("id");
        }).orElseThrow());
    }

    @Test
    void driverAndMapperFailuresHaveSafePhasesAndCauses() {
        UUID id = UUID.randomUUID();
        database.create(insert(id, assignment("payload", new SqlValue.Text("first"))));
        TuprelDatabaseException duplicate = assertThrows(TuprelDatabaseException.class,
                () -> database.create(insert(id, assignment("payload", new SqlValue.Text("again")))));
        assertEquals(TuprelDatabaseException.Phase.EXECUTION, duplicate.phase());
        assertEquals("23505", duplicate.sqlState());
        assertInstanceOf(SQLException.class, duplicate.getCause());
        assertFalse(duplicate.getMessage().contains("again"));

        TuprelDatabaseException mapping = assertThrows(TuprelDatabaseException.class,
                () -> database.findById(find(id), row -> { throw new IllegalStateException("bad map"); }));
        assertEquals(TuprelDatabaseException.Phase.MAPPING, mapping.phase());
        assertInstanceOf(IllegalStateException.class, mapping.getCause());
    }

    private static SqlCommand.Insert insert(UUID id, SqlCommand.Assignment extra) {
        return new SqlCommand.Insert(TABLE, List.of(assignment("id", new SqlValue.Uuid(id)), extra));
    }

    private static SqlCommand.FindById find(UUID id) {
        return new SqlCommand.FindById(TABLE, ID, new SqlValue.Uuid(id));
    }

    private static SqlCommand.Assignment assignment(String column, SqlValue value) {
        return new SqlCommand.Assignment(new SqlIdentifier(column), value);
    }

    private record ScalarRow(UUID id, String payload, Short shortValue, Integer intValue,
            Long longValue, Float floatValue, Double doubleValue, BigDecimal decimalValue,
            Boolean enabled, Instant instant, LocalDateTime dateTime, LocalDate date,
            LocalTime time, SqlValue.Binary bytes) { }
}
