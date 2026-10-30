package dev.tuprel.runtime;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Typed reads from the current JDBC row; valid only during one mapper call. */
record JdbcRowReader(ResultSet result) implements RowReader {
    @FunctionalInterface private interface Read<T> { T get() throws SQLException; }

    private static <T> T read(Read<T> operation) {
        try {
            return operation.get();
        } catch (SQLException exception) {
            throw new TuprelDatabaseException(TuprelDatabaseException.Phase.MAPPING, exception);
        }
    }

    @Override public String text(String column) { return read(() -> result.getString(column)); }
    @Override public Short int16(String column) { return read(() -> {
        short value = result.getShort(column); return result.wasNull() ? null : value;
    }); }
    @Override public Integer int32(String column) { return read(() -> {
        int value = result.getInt(column); return result.wasNull() ? null : value;
    }); }
    @Override public Long int64(String column) { return read(() -> {
        long value = result.getLong(column); return result.wasNull() ? null : value;
    }); }
    @Override public Float float32(String column) { return read(() -> {
        float value = result.getFloat(column); return result.wasNull() ? null : value;
    }); }
    @Override public Double float64(String column) { return read(() -> {
        double value = result.getDouble(column); return result.wasNull() ? null : value;
    }); }
    @Override public BigDecimal decimal(String column) { return read(() -> result.getBigDecimal(column)); }
    @Override public Boolean bool(String column) { return read(() -> {
        boolean value = result.getBoolean(column); return result.wasNull() ? null : value;
    }); }
    @Override public UUID uuid(String column) { return read(() -> result.getObject(column, UUID.class)); }
    @Override public Instant instant(String column) { return read(() -> {
        OffsetDateTime value = result.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }); }
    @Override public LocalDateTime dateTime(String column) {
        return read(() -> result.getObject(column, LocalDateTime.class));
    }
    @Override public LocalDate date(String column) { return read(() -> result.getObject(column, LocalDate.class)); }
    @Override public LocalTime time(String column) { return read(() -> result.getObject(column, LocalTime.class)); }
    @Override public byte[] bytes(String column) { return read(() -> {
        byte[] value = result.getBytes(column); return value == null ? null : value.clone();
    }); }
}
