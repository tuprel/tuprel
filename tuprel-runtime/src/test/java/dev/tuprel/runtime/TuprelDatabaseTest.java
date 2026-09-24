package dev.tuprel.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.tuprel.sql.RenderedSql;
import dev.tuprel.sql.SqlCommand;
import dev.tuprel.sql.SqlIdentifier;
import dev.tuprel.sql.SqlValue;
import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.List;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class TuprelDatabaseTest {
    private static final SqlIdentifier TABLE = new SqlIdentifier("items");
    private static final SqlIdentifier ID = new SqlIdentifier("id");
    private static final SqlCommand.Insert INSERT = new SqlCommand.Insert(TABLE,
            List.of(new SqlCommand.Assignment(new SqlIdentifier("name"), new SqlValue.Text("value"))));
    private static final SqlCommand.FindById FIND = new SqlCommand.FindById(TABLE, ID,
            new SqlValue.Int32(1));

    @Test
    void closesStatementAndConnectionAfterSuccessAndFailures() {
        Fixture success = new Fixture(Failure.NONE);
        assertEquals(1, database(success).create(INSERT));
        assertEquals(1, success.statementsClosed);
        assertEquals(1, success.connectionsClosed);

        for (Failure failure : List.of(Failure.ACQUIRE, Failure.PREPARE, Failure.BIND, Failure.EXECUTE)) {
            Fixture fixture = new Fixture(failure);
            TuprelDatabaseException exception = assertThrows(TuprelDatabaseException.class,
                    () -> database(fixture).create(INSERT));
            assertInstanceOf(SQLException.class, exception.getCause());
            assertEquals(failure.phase, exception.phase());
            assertEquals(failure == Failure.ACQUIRE ? 0 : 1, fixture.connectionsClosed);
            assertEquals(failure == Failure.ACQUIRE || failure == Failure.PREPARE ? 0 : 1,
                    fixture.statementsClosed);
        }
        Fixture noAutocommit = new Fixture(Failure.NO_AUTOCOMMIT);
        TuprelDatabaseException exception = assertThrows(TuprelDatabaseException.class,
                () -> database(noAutocommit).create(INSERT));
        assertEquals(TuprelDatabaseException.Phase.CONNECTION, exception.phase());
        assertEquals(1, noAutocommit.connectionsClosed);
    }

    @Test
    void closesResultStatementAndConnectionAfterMapperFailure() {
        Fixture fixture = new Fixture(Failure.NONE);
        TuprelDatabaseException exception = assertThrows(TuprelDatabaseException.class,
                () -> database(fixture).findById(FIND, row -> {
                    throw new IllegalArgumentException("mapper failed");
                }));
        assertEquals(TuprelDatabaseException.Phase.MAPPING, exception.phase());
        assertEquals(1, fixture.resultsClosed);
        assertEquals(1, fixture.statementsClosed);
        assertEquals(1, fixture.connectionsClosed);
    }

    @Test
    void closesResultStatementAndConnectionAfterSuccessfulMapping() {
        Fixture fixture = new Fixture(Failure.NONE);
        assertEquals("value", database(fixture).findById(FIND, row -> row.text("name")).orElseThrow());
        assertEquals(1, fixture.resultsClosed);
        assertEquals(1, fixture.statementsClosed);
        assertEquals(1, fixture.connectionsClosed);
    }

    @Test
    void mapsSqlReadFailureWithSqlStateAndClosesResources() {
        Fixture fixture = new Fixture(Failure.READ);
        TuprelDatabaseException exception = assertThrows(TuprelDatabaseException.class,
                () -> database(fixture).findById(FIND, row -> row.text("name")));
        assertEquals(TuprelDatabaseException.Phase.MAPPING, exception.phase());
        assertEquals("22000", exception.sqlState());
        assertEquals(1, fixture.resultsClosed);
        assertEquals(1, fixture.statementsClosed);
        assertEquals(1, fixture.connectionsClosed);
    }

    private static TuprelDatabase database(Fixture fixture) {
        return new TuprelDatabase(fixture,
                command -> new RenderedSql("SELECT 1 WHERE ? IS NOT NULL", List.of(new SqlValue.Text("value"))),
                (statement, position, value) -> statement.setString(position, ((SqlValue.Text) value).value()));
    }

    private enum Failure {
        NONE(null), ACQUIRE(TuprelDatabaseException.Phase.CONNECTION),
        PREPARE(TuprelDatabaseException.Phase.PREPARATION),
        BIND(TuprelDatabaseException.Phase.BINDING),
        EXECUTE(TuprelDatabaseException.Phase.EXECUTION), READ(TuprelDatabaseException.Phase.MAPPING),
        NO_AUTOCOMMIT(TuprelDatabaseException.Phase.CONNECTION);

        private final TuprelDatabaseException.Phase phase;
        Failure(TuprelDatabaseException.Phase phase) { this.phase = phase; }
    }

    private static final class Fixture implements DataSource {
        private final Failure failure;
        private int connectionsClosed;
        private int statementsClosed;
        private int resultsClosed;

        Fixture(Failure failure) { this.failure = failure; }

        @Override public Connection getConnection() throws SQLException {
            if (failure == Failure.ACQUIRE) { throw problem(); }
            return proxy(Connection.class, (proxy, method, args) -> switch (method.getName()) {
                case "prepareStatement" -> {
                    if (failure == Failure.PREPARE) { throw problem(); }
                    yield statement();
                }
                case "close" -> { connectionsClosed++; yield null; }
                case "getAutoCommit" -> failure != Failure.NO_AUTOCOMMIT;
                default -> throw new UnsupportedOperationException(method.getName());
            });
        }

        private PreparedStatement statement() {
            return proxy(PreparedStatement.class, (proxy, method, args) -> switch (method.getName()) {
                case "setString" -> {
                    if (failure == Failure.BIND) { throw problem(); }
                    yield null;
                }
                case "executeUpdate" -> {
                    if (failure == Failure.EXECUTE) { throw problem(); }
                    yield 1;
                }
                case "executeQuery" -> result();
                case "close" -> { statementsClosed++; yield null; }
                default -> throw new UnsupportedOperationException(method.getName());
            });
        }

        private ResultSet result() {
            return proxy(ResultSet.class, new InvocationHandler() {
                private boolean first = true;
                @Override public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args)
                        throws SQLException {
                    return switch (method.getName()) {
                        case "next" -> { boolean answer = first; first = false; yield answer; }
                        case "getString" -> {
                            if (failure == Failure.READ) { throw problem(); }
                            yield "value";
                        }
                        case "close" -> { resultsClosed++; yield null; }
                        default -> throw new UnsupportedOperationException(method.getName());
                    };
                }
            });
        }

        private static SQLException problem() { return new SQLException("driver detail", "22000", 17); }

        @SuppressWarnings("unchecked")
        private static <T> T proxy(Class<T> type, InvocationHandler handler) {
            return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
        }

        @Override public Connection getConnection(String username, String password) {
            throw new UnsupportedOperationException();
        }
        @Override public PrintWriter getLogWriter() { throw new UnsupportedOperationException(); }
        @Override public void setLogWriter(PrintWriter writer) { throw new UnsupportedOperationException(); }
        @Override public void setLoginTimeout(int seconds) { throw new UnsupportedOperationException(); }
        @Override public int getLoginTimeout() { throw new UnsupportedOperationException(); }
        @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }
        @Override public <T> T unwrap(Class<T> iface) throws SQLException {
            throw new SQLException("Not a wrapper");
        }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }
}
