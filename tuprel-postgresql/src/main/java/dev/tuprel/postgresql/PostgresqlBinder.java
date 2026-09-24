package dev.tuprel.postgresql;

import dev.tuprel.runtime.JdbcValueBinder;
import dev.tuprel.sql.SqlValue;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** PostgreSQL JDBC scalar and typed-null binding policy. */
final class PostgresqlBinder implements JdbcValueBinder {
    @Override
    public void bind(PreparedStatement statement, int position, SqlValue value) throws SQLException {
        switch (value) {
            case SqlValue.Text item -> statement.setString(position, item.value());
            case SqlValue.Int16 item -> statement.setShort(position, item.value());
            case SqlValue.Int32 item -> statement.setInt(position, item.value());
            case SqlValue.Int64 item -> statement.setLong(position, item.value());
            case SqlValue.Float32 item -> statement.setFloat(position, item.value());
            case SqlValue.Float64 item -> statement.setDouble(position, item.value());
            case SqlValue.Decimal item -> statement.setBigDecimal(position, item.value());
            case SqlValue.Bool item -> statement.setBoolean(position, item.value());
            case SqlValue.Uuid item -> statement.setObject(position, item.value(), Types.OTHER);
            case SqlValue.Timestamp item -> statement.setObject(position,
                    OffsetDateTime.ofInstant(item.value(), ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE);
            case SqlValue.DateTime item -> statement.setObject(position, item.value(), Types.TIMESTAMP);
            case SqlValue.Date item -> statement.setObject(position, item.value(), Types.DATE);
            case SqlValue.Time item -> statement.setObject(position, item.value(), Types.TIME);
            case SqlValue.Binary item -> statement.setBytes(position, item.value());
            case SqlValue.Null item -> statement.setNull(position, jdbcType(item.type()));
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
