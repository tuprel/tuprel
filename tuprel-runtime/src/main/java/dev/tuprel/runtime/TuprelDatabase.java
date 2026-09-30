package dev.tuprel.runtime;

import dev.tuprel.sql.RenderedSql;
import dev.tuprel.sql.SqlCommand;
import dev.tuprel.sql.SqlQuery;
import dev.tuprel.sql.SqlRenderer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;

/** Synchronous, stateless CRUD and query executor; the caller owns the DataSource. */
public final class TuprelDatabase {
    private final DataSource dataSource;
    private final SqlRenderer renderer;
    private final JdbcValueBinder binder;

    public TuprelDatabase(DataSource dataSource, SqlRenderer renderer, JdbcValueBinder binder) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.binder = Objects.requireNonNull(binder, "binder");
    }

    /** Inserts one row and returns the affected-row count. */
    public int create(SqlCommand.Insert command) { return updateCount(command); }

    /** Inserts one row and maps the row returned by the same statement. */
    public <T> T createReturning(SqlCommand.InsertReturning command, RowMapper<T> mapper) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(mapper, "mapper");
        return queryRows(renderer.render(command), mapper).stream().findFirst()
                .orElseThrow(() -> new TuprelDatabaseException(
                        TuprelDatabaseException.Phase.EXECUTION,
                        new SQLException("Insert returned no row")));
    }

    /** Changes one row selected by a non-null identifier value. */
    public int updateById(SqlCommand.UpdateById command) { return updateCount(command); }

    /**
     * Changes one row and maps the row returned by the same statement; empty when no row has
     * the identifier. There is no separate read, so no other statement can interleave.
     */
    public <T> Optional<T> updateReturning(SqlCommand.UpdateByIdReturning command, RowMapper<T> mapper) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(mapper, "mapper");
        return queryRows(renderer.render(command), mapper).stream().findFirst();
    }

    /** Deletes one row selected by a non-null identifier value. */
    public int deleteById(SqlCommand.DeleteById command) { return updateCount(command); }

    /** Reads one row and maps it before all JDBC resources are closed. */
    public <T> Optional<T> findById(SqlCommand.FindById command, RowMapper<T> mapper) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(mapper, "mapper");
        return execute(command, statement -> {
            ResultSet opened;
            try {
                opened = statement.executeQuery();
            } catch (SQLException exception) {
                throw new TuprelDatabaseException(TuprelDatabaseException.Phase.EXECUTION, exception);
            }
            try (ResultSet result = opened) {
                boolean hasRow;
                try {
                    hasRow = result.next();
                } catch (SQLException exception) {
                    throw new TuprelDatabaseException(TuprelDatabaseException.Phase.MAPPING, exception);
                }
                if (!hasRow) {
                    return Optional.empty();
                }
                try {
                    return Optional.of(Objects.requireNonNull(mapper.map(new JdbcRowReader(result)),
                            "mapper result"));
                } catch (TuprelDatabaseException exception) {
                    throw exception;
                } catch (RuntimeException exception) {
                    throw new TuprelDatabaseException(TuprelDatabaseException.Phase.MAPPING, exception);
                }
            } catch (SQLException exception) {
                throw new TuprelDatabaseException(TuprelDatabaseException.Phase.CLOSING, exception);
            }
        });
    }

    /**
     * Executes a column query and maps every returned row, in database order, into an
     * immutable list. All rows are read before resources close; there is no streaming.
     */
    public <T> List<T> findMany(SqlQuery query, RowMapper<T> mapper) {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(mapper, "mapper");
        require(query, SqlQuery.Selection.Columns.class);
        return queryRows(renderer.render(query), mapper);
    }

    /** Executes a count query. */
    public long count(SqlQuery query) {
        Objects.requireNonNull(query, "query");
        require(query, SqlQuery.Selection.Count.class);
        return single(queryRows(renderer.render(query), row -> row.int64("count")));
    }

    /** Executes an exists query. */
    public boolean exists(SqlQuery query) {
        Objects.requireNonNull(query, "query");
        require(query, SqlQuery.Selection.Exists.class);
        return single(queryRows(renderer.render(query), row -> row.bool("exists")));
    }

    private static <T> T single(List<T> rows) {
        if (rows.size() != 1) {
            throw new TuprelDatabaseException(TuprelDatabaseException.Phase.EXECUTION,
                    new SQLException("Aggregate query did not return exactly one row"));
        }
        return rows.getFirst();
    }

    /** Renders a query exactly as it would execute, without opening a connection. */
    public RenderedSql preview(SqlQuery query) {
        return renderer.render(Objects.requireNonNull(query, "query"));
    }

    private static void require(SqlQuery query, Class<? extends SqlQuery.Selection> selection) {
        if (!selection.isInstance(query.selection())) {
            throw new IllegalArgumentException("Query selection does not match the operation");
        }
    }

    private int updateCount(SqlCommand command) {
        Objects.requireNonNull(command, "command");
        return execute(command, statement -> {
            try {
                return statement.executeUpdate();
            } catch (SQLException exception) {
                throw new TuprelDatabaseException(TuprelDatabaseException.Phase.EXECUTION, exception);
            }
        });
    }

    private <T> List<T> queryRows(RenderedSql plan, RowMapper<T> mapper) {
        try (Connection connection = open();
                PreparedStatement statement = prepare(connection, plan.text())) {
            bind(statement, plan);
            ResultSet opened;
            try {
                opened = statement.executeQuery();
            } catch (SQLException exception) {
                throw new TuprelDatabaseException(TuprelDatabaseException.Phase.EXECUTION, exception);
            }
            try (ResultSet result = opened) {
                List<T> values = new ArrayList<>();
                while (true) {
                    boolean hasRow;
                    try {
                        hasRow = result.next();
                    } catch (SQLException exception) {
                        throw new TuprelDatabaseException(TuprelDatabaseException.Phase.MAPPING, exception);
                    }
                    if (!hasRow) {
                        return List.copyOf(values);
                    }
                    try {
                        values.add(Objects.requireNonNull(mapper.map(new JdbcRowReader(result)),
                                "mapper result"));
                    } catch (TuprelDatabaseException exception) {
                        throw exception;
                    } catch (RuntimeException exception) {
                        throw new TuprelDatabaseException(TuprelDatabaseException.Phase.MAPPING, exception);
                    }
                }
            } catch (SQLException exception) {
                throw new TuprelDatabaseException(TuprelDatabaseException.Phase.CLOSING, exception);
            }
        } catch (SQLException exception) {
            throw new TuprelDatabaseException(TuprelDatabaseException.Phase.CLOSING, exception);
        }
    }

    @FunctionalInterface
    private interface StatementWork<T> { T run(PreparedStatement statement); }

    private <T> T execute(SqlCommand command, StatementWork<T> work) {
        RenderedSql plan = renderer.render(command);
        try (Connection connection = open()) {
            try (PreparedStatement statement = prepare(connection, plan.text())) {
                bind(statement, plan);
                return work.run(statement);
            } catch (SQLException exception) {
                throw new TuprelDatabaseException(TuprelDatabaseException.Phase.CLOSING, exception);
            }
        } catch (SQLException exception) {
            throw new TuprelDatabaseException(TuprelDatabaseException.Phase.CLOSING, exception);
        }
    }

    private Connection open() {
        Connection connection;
        try {
            connection = dataSource.getConnection();
        } catch (SQLException | RuntimeException exception) {
            throw new TuprelDatabaseException(TuprelDatabaseException.Phase.CONNECTION, exception);
        }
        if (connection == null) {
            throw new TuprelDatabaseException(TuprelDatabaseException.Phase.CONNECTION,
                    new SQLException("DataSource returned no connection"));
        }
        try {
            if (!connection.getAutoCommit()) {
                throw new SQLException("Autocommit is required by the Phase 3 runtime");
            }
            return connection;
        } catch (SQLException | RuntimeException exception) {
            try {
                connection.close();
            } catch (SQLException closeFailure) {
                exception.addSuppressed(closeFailure);
            }
            throw new TuprelDatabaseException(TuprelDatabaseException.Phase.CONNECTION, exception);
        }
    }

    private static PreparedStatement prepare(Connection connection, String sql) {
        try {
            return connection.prepareStatement(sql);
        } catch (SQLException exception) {
            throw new TuprelDatabaseException(TuprelDatabaseException.Phase.PREPARATION, exception);
        }
    }

    private void bind(PreparedStatement statement, RenderedSql plan) {
        try {
            for (int index = 0; index < plan.binds().size(); index++) {
                binder.bind(statement, index + 1, plan.binds().get(index));
            }
        } catch (SQLException exception) {
            throw new TuprelDatabaseException(TuprelDatabaseException.Phase.BINDING, exception);
        }
    }

    private record JdbcRowReader(ResultSet result) implements RowReader {
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
        @Override public java.math.BigDecimal decimal(String column) {
            return read(() -> result.getBigDecimal(column));
        }
        @Override public Boolean bool(String column) { return read(() -> {
            boolean value = result.getBoolean(column); return result.wasNull() ? null : value;
        }); }
        @Override public java.util.UUID uuid(String column) {
            return read(() -> result.getObject(column, java.util.UUID.class));
        }
        @Override public java.time.Instant instant(String column) { return read(() -> {
            OffsetDateTime value = result.getObject(column, OffsetDateTime.class);
            return value == null ? null : value.toInstant();
        }); }
        @Override public java.time.LocalDateTime dateTime(String column) {
            return read(() -> result.getObject(column, java.time.LocalDateTime.class));
        }
        @Override public java.time.LocalDate date(String column) {
            return read(() -> result.getObject(column, java.time.LocalDate.class));
        }
        @Override public java.time.LocalTime time(String column) {
            return read(() -> result.getObject(column, java.time.LocalTime.class));
        }
        @Override public byte[] bytes(String column) { return read(() -> {
            byte[] value = result.getBytes(column); return value == null ? null : value.clone();
        }); }
    }
}
