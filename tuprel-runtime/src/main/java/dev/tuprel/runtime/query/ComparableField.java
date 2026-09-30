package dev.tuprel.runtime.query;

import dev.tuprel.runtime.RowReader;
import dev.tuprel.sql.SqlCondition;
import dev.tuprel.sql.SqlValue;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Typed column whose values have a meaningful order: numbers, decimals and temporal values.
 *
 * @param <M> generated model type that owns the column
 * @param <T> Java value type of the column
 */
public final class ComparableField<M, T extends Comparable<? super T>> extends Field<M, T> {
    private ComparableField(String column, SqlValue.Type type, boolean nullable,
            Function<? super T, ? extends SqlValue> binder,
            BiFunction<RowReader, String, ? extends T> reader,
            Function<? super M, ? extends T> accessor) {
        super(column, type, nullable, binder, reader, accessor);
    }

    /** {@code Short} column. */
    public static <M> ComparableField<M, Short> int16(String column, boolean nullable,
            Function<? super M, Short> accessor) {
        return new ComparableField<>(column, SqlValue.Type.INT16, nullable, SqlValue.Int16::new,
                RowReader::int16, accessor);
    }

    /** {@code Int} column. */
    public static <M> ComparableField<M, Integer> int32(String column, boolean nullable,
            Function<? super M, Integer> accessor) {
        return new ComparableField<>(column, SqlValue.Type.INT32, nullable, SqlValue.Int32::new,
                RowReader::int32, accessor);
    }

    /** {@code Long} column. */
    public static <M> ComparableField<M, Long> int64(String column, boolean nullable,
            Function<? super M, Long> accessor) {
        return new ComparableField<>(column, SqlValue.Type.INT64, nullable, SqlValue.Int64::new,
                RowReader::int64, accessor);
    }

    /** {@code Float} column. */
    public static <M> ComparableField<M, Float> float32(String column, boolean nullable,
            Function<? super M, Float> accessor) {
        return new ComparableField<>(column, SqlValue.Type.FLOAT32, nullable, SqlValue.Float32::new,
                RowReader::float32, accessor);
    }

    /** {@code Double} column. */
    public static <M> ComparableField<M, Double> float64(String column, boolean nullable,
            Function<? super M, Double> accessor) {
        return new ComparableField<>(column, SqlValue.Type.FLOAT64, nullable, SqlValue.Float64::new,
                RowReader::float64, accessor);
    }

    /** {@code Decimal} column; values compare numerically, independent of scale. */
    public static <M> ComparableField<M, BigDecimal> decimal(String column, boolean nullable,
            Function<? super M, BigDecimal> accessor) {
        return new ComparableField<>(column, SqlValue.Type.DECIMAL, nullable, SqlValue.Decimal::new,
                RowReader::decimal, accessor);
    }

    /** {@code Instant} column stored as {@code TIMESTAMPTZ}. */
    public static <M> ComparableField<M, Instant> instant(String column, boolean nullable,
            Function<? super M, Instant> accessor) {
        return new ComparableField<>(column, SqlValue.Type.INSTANT, nullable, SqlValue.Timestamp::new,
                RowReader::instant, accessor);
    }

    /** {@code LocalDateTime} column stored as {@code TIMESTAMP}. */
    public static <M> ComparableField<M, LocalDateTime> dateTime(String column, boolean nullable,
            Function<? super M, LocalDateTime> accessor) {
        return new ComparableField<>(column, SqlValue.Type.LOCAL_DATE_TIME, nullable,
                SqlValue.DateTime::new, RowReader::dateTime, accessor);
    }

    /** {@code LocalDate} column. */
    public static <M> ComparableField<M, LocalDate> date(String column, boolean nullable,
            Function<? super M, LocalDate> accessor) {
        return new ComparableField<>(column, SqlValue.Type.LOCAL_DATE, nullable, SqlValue.Date::new,
                RowReader::date, accessor);
    }

    /** {@code LocalTime} column. */
    public static <M> ComparableField<M, LocalTime> time(String column, boolean nullable,
            Function<? super M, LocalTime> accessor) {
        return new ComparableField<>(column, SqlValue.Type.LOCAL_TIME, nullable, SqlValue.Time::new,
                RowReader::time, accessor);
    }

    /** Matches rows whose column is less than the value. */
    public Condition<M> lt(T value) {
        return compare(SqlCondition.Operator.LT, value);
    }

    /** Matches rows whose column is less than or equal to the value. */
    public Condition<M> lte(T value) {
        return compare(SqlCondition.Operator.LTE, value);
    }

    /** Matches rows whose column is greater than the value. */
    public Condition<M> gt(T value) {
        return compare(SqlCondition.Operator.GT, value);
    }

    /** Matches rows whose column is greater than or equal to the value. */
    public Condition<M> gte(T value) {
        return compare(SqlCondition.Operator.GTE, value);
    }

    /** Matches rows whose column lies in the inclusive range {@code [lower, upper]}. */
    public Condition<M> between(T lower, T upper) {
        return new Condition<>(new SqlCondition.Comparison(column(), SqlCondition.Operator.BETWEEN,
                List.of(predicateValue(lower), predicateValue(upper))));
    }
}
