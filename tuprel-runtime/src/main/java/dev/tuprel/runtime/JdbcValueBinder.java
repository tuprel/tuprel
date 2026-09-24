package dev.tuprel.runtime;

import dev.tuprel.sql.SqlValue;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/** Dialect-owned JDBC binding policy for one typed value. */
@FunctionalInterface
public interface JdbcValueBinder {
    void bind(PreparedStatement statement, int position, SqlValue value) throws SQLException;
}
