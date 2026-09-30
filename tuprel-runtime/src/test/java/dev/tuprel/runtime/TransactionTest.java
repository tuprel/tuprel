package dev.tuprel.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuprel.sql.RenderedSql;
import dev.tuprel.sql.SqlCommand;
import dev.tuprel.sql.SqlIdentifier;
import dev.tuprel.sql.SqlQuery;
import dev.tuprel.sql.SqlRenderer;
import dev.tuprel.sql.SqlValue;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Savepoint;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class TransactionTest {
    private static final SqlIdentifier TABLE = new SqlIdentifier("items");
    private static final SqlCommand.DeleteById DELETE = new SqlCommand.DeleteById(TABLE, new SqlIdentifier("id"),
            new SqlValue.Int32(1));
    private static final SqlQuery QUERY = SqlQuery.select(TABLE, List.of(new SqlIdentifier("id")));

    @Test
    void commitsOnSuccessOnOneConnectionAndRestoresItsSettings() {
        Recorder recorder = new Recorder();
        TuprelDatabase database = recorder.database();
        assertFalse(database.inTransaction());
        String result = database.transaction(TransactionOptions.defaults()
                .withIsolation(IsolationLevel.SERIALIZABLE).withReadOnly(true), tx -> {
            assertTrue(tx.inTransaction());
            tx.deleteById(DELETE);
            tx.deleteById(DELETE);
            return "done";
        });
        assertEquals("done", result);
        assertEquals(1, recorder.connectionsOpened);
        assertEquals(List.of("setReadOnly(true)", "setTransactionIsolation(8)", "setAutoCommit(false)",
                "executeUpdate", "executeUpdate", "commit", "setAutoCommit(true)", "setTransactionIsolation(2)",
                "setReadOnly(false)", "close"), recorder.connectionEvents());
    }

    @Test
    void rollsBackRethrowsAndClosesWhenWorkFails() {
        Recorder recorder = new Recorder();
        IllegalStateException failure = new IllegalStateException("business rule");
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> recorder.database().transaction(tx -> {
                    tx.deleteById(DELETE);
                    throw failure;
                }));
        assertSame(failure, thrown);
        assertEquals(List.of("setAutoCommit(false)", "executeUpdate", "rollback", "setAutoCommit(true)",
                "setTransactionIsolation(2)", "setReadOnly(false)", "close"), recorder.connectionEvents());
    }

    @Test
    void reportsCommitFailureInTheTransactionPhaseAfterRollingBack() {
        Recorder recorder = new Recorder();
        recorder.failCommit = true;
        TuprelDatabaseException exception = assertThrows(TuprelDatabaseException.class,
                () -> recorder.database().transaction(tx -> tx.deleteById(DELETE)));
        assertEquals(TuprelDatabaseException.Phase.TRANSACTION, exception.phase());
        assertEquals("40001", exception.sqlState());
        assertTrue(recorder.connectionEvents().containsAll(List.of("commit", "rollback", "close")));
    }

    @Test
    void nestedBlocksUseSavepointsAndRollBackOnlyTheirWork() {
        Recorder recorder = new Recorder();
        recorder.database().transaction(tx -> {
            tx.deleteById(DELETE);
            assertThrows(IllegalArgumentException.class, () -> tx.transaction(inner -> {
                inner.deleteById(DELETE);
                throw new IllegalArgumentException("inner");
            }));
            tx.transaction(inner -> inner.deleteById(DELETE));
            assertThrows(IllegalArgumentException.class, () -> tx.transaction(
                    TransactionOptions.defaults().withReadOnly(true), inner -> 1));
            return null;
        });
        assertEquals(List.of("setAutoCommit(false)", "executeUpdate", "setSavepoint", "executeUpdate",
                "rollback(savepoint)", "setSavepoint", "executeUpdate", "releaseSavepoint", "commit",
                "setAutoCommit(true)", "setTransactionIsolation(2)", "setReadOnly(false)", "close"),
                recorder.connectionEvents());
    }

    @Test
    void transactionHandlesCannotEscapeTheirBlockOrThread() throws InterruptedException {
        Recorder recorder = new Recorder();
        AtomicReference<TuprelDatabase> escaped = new AtomicReference<>();
        AtomicReference<TuprelDatabase> escapedNested = new AtomicReference<>();
        recorder.database().transaction(tx -> {
            escaped.set(tx);
            tx.transaction(inner -> {
                escapedNested.set(inner);
                return null;
            });
            assertThrows(IllegalStateException.class, () -> escapedNested.get().deleteById(DELETE));
            CompletableFuture<Integer> otherThread = CompletableFuture.supplyAsync(() -> tx.deleteById(DELETE));
            ExecutionException exception = assertThrows(ExecutionException.class, otherThread::get);
            assertInstanceOf(IllegalStateException.class, exception.getCause());
            return null;
        });
        assertThrows(IllegalStateException.class, () -> escaped.get().deleteById(DELETE));
        assertEquals(1, recorder.connectionsOpened);
    }

    @Test
    void appliesTheShorterOfStatementTimeoutAndRemainingDeadline() {
        Recorder recorder = new Recorder();
        TuprelDatabase database = recorder.database();
        database.findMany(QUERY, row -> 1, StatementOptions.defaults().withTimeout(Duration.ofMillis(1500)));
        database.transaction(TransactionOptions.defaults().withTimeout(Duration.ofSeconds(30)), tx -> {
            tx.findMany(QUERY, row -> 1, StatementOptions.defaults().withTimeout(Duration.ofSeconds(5)));
            tx.findMany(QUERY, row -> 1);
            return null;
        });
        assertEquals(List.of(2, 5, 30), recorder.queryTimeouts);
    }

    @Test
    void refusesStatementsAfterTheTransactionDeadlineAndRollsBack() {
        Recorder recorder = new Recorder();
        TuprelDatabaseException exception = assertThrows(TuprelDatabaseException.class, () -> recorder.database()
                .transaction(TransactionOptions.defaults().withTimeout(Duration.ofMillis(1)), tx -> {
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    return tx.deleteById(DELETE);
                }));
        assertInstanceOf(SQLTimeoutException.class, exception.getCause());
        assertFalse(recorder.connectionEvents().contains("executeUpdate"));
        assertTrue(recorder.connectionEvents().contains("rollback"));
    }

    @Test
    void streamsRequireATransactionAndAreClosedWhenItEnds() {
        Recorder recorder = new Recorder();
        recorder.rows = 3;
        TuprelDatabase database = recorder.database();
        assertThrows(IllegalStateException.class,
                () -> database.stream(QUERY, row -> 1, StatementOptions.defaults()));
        AtomicReference<TuprelStream<Integer>> leaked = new AtomicReference<>();
        List<Integer> read = database.transaction(tx -> {
            List<Integer> values = new ArrayList<>();
            try (TuprelStream<Integer> stream = tx.stream(QUERY, row -> row.int32("id"), StatementOptions.defaults())) {
                stream.forEach(values::add);
                assertThrows(IllegalStateException.class, stream::iterator);
            }
            leaked.set(tx.stream(QUERY, row -> row.int32("id"), StatementOptions.defaults().withFetchSize(2)));
            return values;
        });
        assertEquals(List.of(1, 1, 1), read);
        assertEquals(List.of(100, 2), recorder.fetchSizes);
        assertEquals(2, recorder.resultsClosed, "The transaction closes a stream left open");
        assertThrows(IllegalStateException.class, () -> leaked.get().iterator());
    }

    @Test
    void executeUpdateRejectsCommandsThatReturnRows() {
        TuprelDatabase database = new Recorder().database();
        assertThrows(IllegalArgumentException.class, () -> database.executeUpdate(
                new SqlCommand.FindById(TABLE, new SqlIdentifier("id"), new SqlValue.Int32(1))));
        assertEquals(1, database.executeUpdate(DELETE));
    }

    /** JDBC fake recording connection-level events in order. */
    private static final class Recorder implements SqlRenderer {
        private final List<String> events = new ArrayList<>();
        private final List<Integer> queryTimeouts = new ArrayList<>();
        private final List<Integer> fetchSizes = new ArrayList<>();
        private int connectionsOpened;
        private int resultsClosed;
        private int rows;
        private boolean failCommit;
        private boolean autoCommit = true;

        TuprelDatabase database() {
            return new TuprelDatabase(dataSource(), this, (statement, index, value) -> { });
        }

        List<String> connectionEvents() {
            return events;
        }

        @Override
        public RenderedSql render(SqlCommand command) {
            return new RenderedSql("DELETE", List.of());
        }

        @Override
        public RenderedSql render(SqlQuery query) {
            return new RenderedSql("SELECT", List.of());
        }

        private DataSource dataSource() {
            return proxy(DataSource.class, (method, arguments) -> {
                if (!method.equals("getConnection")) {
                    throw new UnsupportedOperationException(method);
                }
                connectionsOpened++;
                autoCommit = true;
                return connection();
            });
        }

        private Connection connection() {
            Savepoint savepoint = proxy(Savepoint.class, (method, arguments) -> null);
            return proxy(Connection.class, (method, arguments) -> switch (method) {
                case "getAutoCommit" -> autoCommit;
                case "getTransactionIsolation" -> Connection.TRANSACTION_READ_COMMITTED;
                case "isReadOnly" -> false;
                case "setAutoCommit" -> {
                    autoCommit = (Boolean) arguments[0];
                    events.add("setAutoCommit(" + arguments[0] + ")");
                    yield null;
                }
                case "setTransactionIsolation", "setReadOnly" -> {
                    events.add(method + "(" + arguments[0] + ")");
                    yield null;
                }
                case "commit" -> {
                    events.add("commit");
                    if (failCommit) {
                        throw new SQLException("serialization failure", "40001");
                    }
                    yield null;
                }
                case "rollback" -> {
                    events.add(arguments == null ? "rollback" : "rollback(savepoint)");
                    yield null;
                }
                case "setSavepoint" -> {
                    events.add("setSavepoint");
                    yield savepoint;
                }
                case "releaseSavepoint", "close" -> {
                    events.add(method);
                    yield null;
                }
                case "prepareStatement" -> statement();
                default -> throw new UnsupportedOperationException(method);
            });
        }

        private PreparedStatement statement() {
            return proxy(PreparedStatement.class, (method, arguments) -> switch (method) {
                case "executeUpdate" -> {
                    events.add("executeUpdate");
                    yield 1;
                }
                case "executeQuery" -> result();
                case "setQueryTimeout" -> {
                    queryTimeouts.add((Integer) arguments[0]);
                    yield null;
                }
                case "setFetchSize" -> {
                    fetchSizes.add((Integer) arguments[0]);
                    yield null;
                }
                case "close" -> null;
                default -> throw new UnsupportedOperationException(method);
            });
        }

        private ResultSet result() {
            int[] remaining = {rows};
            return proxy(ResultSet.class, (method, arguments) -> switch (method) {
                case "next" -> remaining[0]-- > 0;
                case "getInt" -> 1;
                case "wasNull" -> false;
                case "close" -> {
                    resultsClosed++;
                    yield null;
                }
                default -> throw new UnsupportedOperationException(method);
            });
        }

        private interface Handler {
            Object handle(String method, Object[] arguments) throws SQLException;
        }

        private static <T> T proxy(Class<T> type, Handler handler) {
            return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                    (proxy, method, arguments) -> handler.handle(method.getName(), arguments)));
        }
    }
}
