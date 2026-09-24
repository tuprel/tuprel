package dev.tuprel.sql;

import java.math.BigDecimal;
import java.util.Arrays;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Objects;
import java.util.UUID;

/** Closed set of bound scalar values; SQL text never contains these values. */
public sealed interface SqlValue permits SqlValue.Text, SqlValue.Int16, SqlValue.Int32,
        SqlValue.Int64, SqlValue.Float32, SqlValue.Float64, SqlValue.Decimal,
        SqlValue.Bool, SqlValue.Uuid, SqlValue.Timestamp, SqlValue.DateTime,
        SqlValue.Date, SqlValue.Time, SqlValue.Binary, SqlValue.Null {

    /** SQL type of a typed null. */
    enum Type { TEXT, INT16, INT32, INT64, FLOAT32, FLOAT64, DECIMAL, BOOLEAN,
        UUID, INSTANT, LOCAL_DATE_TIME, LOCAL_DATE, LOCAL_TIME, BYTES }

    record Text(String value) implements SqlValue {
        public Text { Objects.requireNonNull(value, "value"); }
    }
    record Int16(short value) implements SqlValue { }
    record Int32(int value) implements SqlValue { }
    record Int64(long value) implements SqlValue { }
    record Float32(float value) implements SqlValue { }
    record Float64(double value) implements SqlValue { }
    record Decimal(BigDecimal value) implements SqlValue {
        public Decimal { Objects.requireNonNull(value, "value"); }
    }
    record Bool(boolean value) implements SqlValue { }
    record Uuid(UUID value) implements SqlValue {
        public Uuid { Objects.requireNonNull(value, "value"); }
    }
    record Timestamp(Instant value) implements SqlValue {
        public Timestamp { Objects.requireNonNull(value, "value"); }
    }
    record DateTime(LocalDateTime value) implements SqlValue {
        public DateTime { Objects.requireNonNull(value, "value"); }
    }
    record Date(LocalDate value) implements SqlValue {
        public Date { Objects.requireNonNull(value, "value"); }
    }
    record Time(LocalTime value) implements SqlValue {
        public Time { Objects.requireNonNull(value, "value"); }
    }
    /** Defensive binary value; callers cannot mutate the stored bytes. */
    final class Binary implements SqlValue {
        private final byte[] value;

        public Binary(byte[] value) {
            this.value = Objects.requireNonNull(value, "value").clone();
        }

        public byte[] value() { return value.clone(); }

        @Override public boolean equals(Object other) {
            return other instanceof Binary binary && Arrays.equals(value, binary.value);
        }

        @Override public int hashCode() { return Arrays.hashCode(value); }
    }
    record Null(Type type) implements SqlValue {
        public Null { Objects.requireNonNull(type, "type"); }
    }
}
