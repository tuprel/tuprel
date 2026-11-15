package dev.tuprel.postgresql;

import dev.tuprel.runtime.JdbcValueBinder;
import dev.tuprel.sql.SqlValue;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

/** PostgreSQL JDBC scalar and typed-null binding policy. */
final class PostgresqlBinder implements JdbcValueBinder {
    @Override
    public void bind(PreparedStatement statement, int position, SqlValue value) throws SQLException {
        Objects.requireNonNull(value);
        if (value instanceof SqlValue.Text item) {
            statement.setString(position, item.value());
        } else if (value instanceof SqlValue.Int16 item) {
            statement.setShort(position, item.value());
        } else if (value instanceof SqlValue.Int32 item) {
            statement.setInt(position, item.value());
        } else if (value instanceof SqlValue.Int64 item) {
            statement.setLong(position, item.value());
        } else if (value instanceof SqlValue.Float32 item) {
            statement.setFloat(position, item.value());
        } else if (value instanceof SqlValue.Float64 item) {
            statement.setDouble(position, item.value());
        } else if (value instanceof SqlValue.Decimal item) {
            statement.setBigDecimal(position, item.value());
        } else if (value instanceof SqlValue.Bool item) {
            statement.setBoolean(position, item.value());
        } else if (value instanceof SqlValue.Uuid item) {
            statement.setObject(position, item.value(), Types.OTHER);
        } else if (value instanceof SqlValue.Timestamp item) {
            statement.setObject(position,
                    OffsetDateTime.ofInstant(item.value(), ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE);
        } else if (value instanceof SqlValue.DateTime item) {
            statement.setObject(position, item.value(), Types.TIMESTAMP);
        } else if (value instanceof SqlValue.Date item) {
            statement.setObject(position, item.value(), Types.DATE);
        } else if (value instanceof SqlValue.Time item) {
            statement.setObject(position, item.value(), Types.TIME);
        } else if (value instanceof SqlValue.Binary item) {
            statement.setBytes(position, item.value());
        } else if (value instanceof SqlValue.Null item) {
            statement.setNull(position, jdbcType(item.type()));
        } else {
            throw new IncompatibleClassChangeError();
        }
    }

    private static int jdbcType(SqlValue.Type type) {
        return switch (type) {
            case TEXT -> Types.VARCHAR;
            case INT16 -> Types.SMALLINT;
            case INT32 -> Types.INTEGER;
            case INT64 -> Types.BIGINT;
            case FLOAT32 -> Types.REAL;
            case FLOAT64 -> Types.DOUBLE;
            case DECIMAL -> Types.NUMERIC;
            case BOOLEAN -> Types.BOOLEAN;
            case UUID -> Types.OTHER;
            case INSTANT -> Types.TIMESTAMP_WITH_TIMEZONE;
            case LOCAL_DATE_TIME -> Types.TIMESTAMP;
            case LOCAL_DATE -> Types.DATE;
            case LOCAL_TIME -> Types.TIME;
            case BYTES -> Types.BINARY;
        };
    }
}
