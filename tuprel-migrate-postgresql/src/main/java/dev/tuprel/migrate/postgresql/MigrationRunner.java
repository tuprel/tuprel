package dev.tuprel.migrate.postgresql;

import static dev.tuprel.migrate.postgresql.MigrationExecutionResult.Failure;
import static dev.tuprel.migrate.postgresql.MigrationExecutionResult.Outcome;

import dev.tuprel.migrate.AppliedMigration;
import dev.tuprel.migrate.MigrationDirectory;
import dev.tuprel.migrate.MigrationFile;
import dev.tuprel.migrate.MigrationHistory;
import dev.tuprel.migrate.MigrationHistoryException;
import dev.tuprel.migrate.MigrationValidationException;
import dev.tuprel.migrate.postgresql.ddl.DdlParseResult;
import dev.tuprel.migrate.postgresql.ddl.DdlStatement;
import dev.tuprel.migrate.postgresql.ddl.PostgresqlDdlParser;
import dev.tuprel.sql.SqlIdentifier;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.ArrayList;
import java.util.Optional;
import javax.sql.DataSource;

/** Internal execution mechanics behind the public adapter facade. */
final class MigrationRunner {
    private MigrationRunner() { }

    static MigrationExecutionResult deploy(DataSource source, MigrationDatabaseSettings settings, String targetSchema,
            Path directory, MigrationPlanDigest approval) {
        MigrationSession session = null;
        List<AppliedMigration> confirmed = new ArrayList<>();
        MigrationExecutionResult result = new MigrationExecutionResult(Outcome.OUTCOME_UNKNOWN, Failure.CONNECTION, confirmed);
        try {
            session = MigrationSession.open(source, settings, targetSchema);
            Connection connection = session.connection();
            SqlIdentifier schema = settings.planTarget(targetSchema).schema();
            // Rediscovery occurs under the session lock. These immutable exact bytes are all we execute.
            List<MigrationFile> files = MigrationDirectory.discover(directory);
            MigrationHistory history = observe(connection, schema);
            MigrationPlan plan = new PostgresqlMigrationPlanCompiler().compile(settings.planTarget(targetSchema), files, history);
            if (plan.state() == MigrationPlan.State.BLOCKED) { throw new MigrationRunFailure(Failure.BLOCKED, false); }
            if (!plan.digest().equals(approval)) { throw new MigrationRunFailure(Failure.APPROVAL, false); }
            if (plan.state() == MigrationPlan.State.NO_CHANGES) {
                result = new MigrationExecutionResult(Outcome.NO_CHANGES, Failure.NONE, confirmed);
            } else {
                interrupted();
                if (!history.initialised()) {
                    bootstrap(connection, schema);
                    history = new MigrationHistory(true, List.of());
                }
                List<AppliedMigration> expected = new ArrayList<>(history.applied());
                for (MigrationPlan.Script script : plan.scripts()) {
                    interrupted();
                    MigrationFile file = files.stream().filter(value -> value.id().equals(script.id())).findFirst().orElseThrow();
                    AppliedMigration applied = apply(connection, schema, file, new MigrationHistory(true, expected));
                    confirmed.add(applied); expected.add(applied);
                }
                result = new MigrationExecutionResult(Outcome.APPLIED, Failure.NONE, confirmed);
            }
        } catch (MigrationRunFailure failure) {
            if (failure.uncertain() && session != null) { session.discard(); }
            result = new MigrationExecutionResult(failure.uncertain() ? Outcome.OUTCOME_UNKNOWN : Outcome.REFUSED, failure.failure(), confirmed);
        } catch (MigrationSessionException failure) {
            result = new MigrationExecutionResult(failure.reason() == MigrationSessionException.Reason.OUTCOME_UNKNOWN
                    ? Outcome.OUTCOME_UNKNOWN : Outcome.REFUSED, sessionFailure(failure), confirmed);
        } catch (MigrationValidationException failure) {
            result = new MigrationExecutionResult(Outcome.REFUSED, Failure.FILES, confirmed);
        } catch (MigrationHistoryException failure) {
            result = new MigrationExecutionResult(Outcome.REFUSED, Failure.HISTORY, confirmed);
        } catch (SQLException failure) {
            boolean uncertain = connectionFailure(failure);
            if (uncertain && session != null) { session.discard(); }
            result = new MigrationExecutionResult(uncertain ? Outcome.OUTCOME_UNKNOWN : Outcome.FAILED, classify(failure), confirmed);
        } catch (RuntimeException failure) {
            if (session != null) { session.discard(); }
            result = new MigrationExecutionResult(Outcome.OUTCOME_UNKNOWN, Failure.CONNECTION, confirmed);
        } finally {
            if (session != null) {
                if (result.outcome() == Outcome.OUTCOME_UNKNOWN || Thread.currentThread().isInterrupted()) {
                    if (!session.discard() && result.outcome() != Outcome.OUTCOME_UNKNOWN) {
                        result = new MigrationExecutionResult(Outcome.FAILED, Failure.CLEANUP, confirmed);
                    }
                }
                try { session.closeSettled(); }
                catch (MigrationSessionException failure) {
                    // A cleanup failure never erases confirmed commit evidence.
                    if (result.outcome() != Outcome.OUTCOME_UNKNOWN) {
                        result = new MigrationExecutionResult(Outcome.FAILED, Failure.CLEANUP, confirmed);
                    }
                }
            }
        }
        return result;
    }

    static MigrationHistory observe(Connection connection, SqlIdentifier schema)
            throws SQLException, MigrationHistoryException, MigrationRunFailure {
        connection.setReadOnly(true);
        connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        connection.setAutoCommit(false);
        try {
            MigrationCatalog.execute(connection, "SET LOCAL search_path = pg_catalog");
            try (PreparedStatement query = MigrationCatalog.query(connection, """
                    SELECT pg_catalog.current_setting('transaction_isolation') = 'repeatable read'
                       AND pg_catalog.current_setting('transaction_read_only') = 'on'
                    """); java.sql.ResultSet rows = query.executeQuery()) {
                if (!rows.next() || !rows.getBoolean(1)) { throw new MigrationRunFailure(Failure.HISTORY, false); }
            }
            return PostgresqlMigrationHistory.snapshot(connection, schema);
        } finally { rollback(connection); }
    }

    private static void begin(Connection connection) throws SQLException, MigrationRunFailure {
        // Only used after a confirmed rollback/commit; never a recovery mechanism.
        connection.setAutoCommit(true);
        connection.setReadOnly(false);
        connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        connection.setAutoCommit(false);
        MigrationCatalog.execute(connection, "SET LOCAL search_path = pg_catalog");
        MigrationCatalog.execute(connection, "SET LOCAL lock_timeout = '10s'");
        MigrationCatalog.execute(connection, "SET LOCAL statement_timeout = '20s'");
        MigrationCatalog.execute(connection, "SET LOCAL default_table_access_method = 'heap'");
        MigrationCatalog.environment(connection);
    }

    private static void bootstrap(Connection connection, SqlIdentifier schema)
            throws SQLException, MigrationRunFailure, MigrationHistoryException {
        try {
            begin(connection);
            MigrationHistory current = PostgresqlMigrationHistory.snapshot(connection, schema);
            if (current.initialised()) { throw new MigrationRunFailure(Failure.HISTORY, false); }
            MigrationCatalog.execute(connection, MigrationHistoryTable.createSql(schema));
            MigrationCatalog.lockHistory(connection, schema);
            if (!PostgresqlMigrationHistory.snapshot(connection, schema).equals(new MigrationHistory(true, List.of()))) {
                throw new MigrationRunFailure(Failure.HISTORY, false);
            }
        } catch (SQLException failure) { failedStatement(connection, failure); throw failure; }
        catch (MigrationHistoryException | MigrationRunFailure failure) { rollback(connection); throw failure; }
        commit(connection);
    }

    private static AppliedMigration apply(Connection connection, SqlIdentifier schema, MigrationFile file, MigrationHistory expected)
            throws SQLException, MigrationRunFailure, MigrationHistoryException {
        DdlParseResult parsed = new PostgresqlDdlParser().parse(file);
        if (!parsed.isSupported()) { throw new MigrationRunFailure(Failure.BLOCKED, false); }
        AppliedMigration applied;
        try {
            begin(connection);
            MigrationCatalog.lockHistory(connection, schema);
            if (!PostgresqlMigrationHistory.snapshot(connection, schema).equals(expected)) { throw new MigrationRunFailure(Failure.HISTORY, false); }
            MigrationCatalog.lockTargets(connection, parsed.statements());
            Instant started = Instant.now().truncatedTo(ChronoUnit.MICROS);
            long start = System.nanoTime();
            for (DdlStatement statement : parsed.statements()) {
                MigrationCatalog.validate(connection, statement);
                MigrationCatalog.execute(connection, new DdlParseResult(List.of(statement), Optional.empty()).preview());
            }
            Instant finished = Instant.now().truncatedTo(ChronoUnit.MICROS);
            if (finished.isBefore(started)) { finished = started; }
            applied = new AppliedMigration(file.id(), file.name(), file.checksum(), started, finished,
                    Math.max(0, (System.nanoTime() - start) / 1_000_000), "runner-v1-development");
            record(connection, schema, applied);
        } catch (SQLException failure) { failedStatement(connection, failure); throw failure; }
        catch (MigrationHistoryException | MigrationRunFailure failure) { rollback(connection); throw failure; }
        commit(connection);
        return applied;
    }

    private static void record(Connection connection, SqlIdentifier schema, AppliedMigration applied) throws SQLException {
        String history = MigrationCatalog.table(new DdlStatement.TableName(schema, new SqlIdentifier(MigrationHistoryTable.NAME)));
        try (PreparedStatement insert = MigrationCatalog.query(connection, "INSERT INTO " + history
                + " (format_version, migration_id, migration_name, checksum, state, started_at, finished_at, execution_millis, engine_version)"
                + " VALUES (1, ?, ?, ?, 'APPLIED', ?, ?, ?, ?)")) {
            insert.setString(1, applied.id().value()); insert.setString(2, applied.name()); insert.setString(3, applied.checksum().value());
            insert.setObject(4, OffsetDateTime.ofInstant(applied.startedAt(), ZoneOffset.UTC));
            insert.setObject(5, OffsetDateTime.ofInstant(applied.finishedAt(), ZoneOffset.UTC));
            insert.setLong(6, applied.executionMillis()); insert.setString(7, applied.engineVersion());
            if (insert.executeUpdate() != 1) { throw new SQLException("No history acknowledgement"); }
        }
    }

    private static void commit(Connection connection) throws MigrationRunFailure {
        try { connection.commit(); }
        catch (SQLException | RuntimeException failure) { throw new MigrationRunFailure(Failure.COMMIT, true); }
    }

    private static void rollback(Connection connection) throws MigrationRunFailure {
        try { connection.rollback(); }
        catch (SQLException | RuntimeException failure) { throw new MigrationRunFailure(Failure.ROLLBACK, true); }
    }

    private static void failedStatement(Connection connection, SQLException failure) throws MigrationRunFailure {
        if (connectionFailure(failure)) { throw new MigrationRunFailure(Failure.CONNECTION, true); }
        rollback(connection);
    }

    private static boolean connectionFailure(SQLException failure) {
        return failure.getSQLState() == null || failure.getSQLState().startsWith("08");
    }

    static Failure classify(SQLException failure) {
        String state = failure.getSQLState();
        return connectionFailure(failure) ? Failure.CONNECTION : "42501".equals(state) ? Failure.PERMISSION
                : failure instanceof SQLTimeoutException || "57014".equals(state) || "55P03".equals(state) ? Failure.TIMEOUT : Failure.STATEMENT;
    }

    static Failure sessionFailure(MigrationSessionException failure) {
        return switch (failure.reason()) {
            case TARGET -> Failure.CONFIGURATION;
            case ACTIVE_TRANSACTION, PREHELD_LOCK -> Failure.SESSION;
            case LOCK_BUSY -> Failure.LOCK_BUSY;
            case INTERRUPTED -> Failure.INTERRUPTED;
            case CONNECTION -> Failure.CONNECTION;
            case CLEANUP -> Failure.CLEANUP;
            case OUTCOME_UNKNOWN -> Failure.ROLLBACK;
        };
    }

    private static void interrupted() throws MigrationRunFailure {
        if (Thread.currentThread().isInterrupted()) { throw new MigrationRunFailure(Failure.INTERRUPTED, false); }
    }
}
