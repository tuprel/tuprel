package dev.tuprel.migrate.postgresql;

import dev.tuprel.migrate.MigrationDirectory;
import dev.tuprel.migrate.MigrationFile;
import dev.tuprel.migrate.MigrationHistory;
import dev.tuprel.migrate.MigrationHistoryException;
import dev.tuprel.migrate.MigrationValidationException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * PostgreSQL deployment facade for the approved transactional DDL subset.
 * Owns each connection for the complete invocation; requires idle dedicated
 * sessions, finite timeouts and physical abort/discard semantics. Transaction
 * pooling is unsupported. No environment/dotenv file is loaded by this class.
 * Custom sources must implement the same TLS/certificate policy as the expected
 * settings. Live checks observe encryption usage, not client-side server-certificate policy
 * attestation. Prefer settings.dataSource() for the validated driver configuration.
 */
public final class PostgresqlMigrationRunner {
    private final DataSource source;
    private final MigrationDatabaseSettings settings;

    /**
     * Uses caller-owned production configuration, independently verified on each live session.
     * @param source dedicated idle connections with finite timeouts/physical discard
     * @param settings explicit expected endpoint, principal and TLS configuration
     */
    public PostgresqlMigrationRunner(DataSource source, MigrationDatabaseSettings settings) {
        this.source = Objects.requireNonNull(source, "source"); this.settings = Objects.requireNonNull(settings, "settings");
    }

    /**
     * Inspects files and confirmed history on a fresh session under the deployment lock.
     * Useful for explicit review and reconciliation after an uncertain invocation:
     * it never retries, repairs, bootstraps or applies SQL. An APPLIED history row
     * records the atomic migration transaction; this is not full physical drift validation.
     * @param targetSchema explicit preexisting schema
     * @param directory existing format-v1 catalog, without concurrent filesystem writers
     * @return current read-only history and recompiled plan
     * @throws MigrationInspectionException if observation, lock or cleanup cannot be confirmed
     */
    public MigrationInspection inspect(String targetSchema, Path directory) throws MigrationInspectionException {
        Objects.requireNonNull(targetSchema, "targetSchema"); Objects.requireNonNull(directory, "directory");
        MigrationSession session = null;
        boolean observed = false;
        MigrationInspection result = null;
        MigrationInspectionException error = null;
        try {
            session = MigrationSession.open(source, settings, targetSchema);
            List<MigrationFile> files = MigrationDirectory.discover(directory);
            MigrationHistory history = MigrationRunner.observe(session.connection(), settings.planTarget(targetSchema).schema());
            MigrationPlan plan = new PostgresqlMigrationPlanCompiler().compile(settings.planTarget(targetSchema), files, history);
            result = new MigrationInspection(plan, history);
            observed = true;
        } catch (MigrationSessionException failure) {
            error = new MigrationInspectionException(MigrationRunner.sessionFailure(failure));
        } catch (MigrationValidationException failure) {
            error = new MigrationInspectionException(MigrationExecutionResult.Failure.FILES);
        } catch (MigrationHistoryException failure) {
            error = new MigrationInspectionException(MigrationExecutionResult.Failure.HISTORY);
        } catch (MigrationRunFailure failure) {
            error = new MigrationInspectionException(failure.failure());
        } catch (SQLException failure) {
            error = new MigrationInspectionException(MigrationRunner.classify(failure));
        } catch (RuntimeException failure) {
            error = new MigrationInspectionException(MigrationExecutionResult.Failure.CONNECTION);
        } finally {
            if (session != null) {
                if (!observed) {
                    if (!session.discard()) { error = new MigrationInspectionException(MigrationExecutionResult.Failure.CLEANUP); }
                }
                else {
                    try { session.closeSettled(); }
                    catch (MigrationSessionException failure) { error = new MigrationInspectionException(MigrationExecutionResult.Failure.CLEANUP); }
                }
            }
        }
        if (error != null) { throw error; }
        return Objects.requireNonNull(result);
    }

    /**
     * Reobserves/recompiles under lock and applies only a matching explicitly approved plan.
     * Every migration commits its DDL and history row together; earlier confirmed
     * migrations survive a later failure. OUTCOME_UNKNOWN stops and discards the
     * connection: inspect in a fresh invocation before considering further work.
     * A digest is neither human attestation nor a single-use token. This method
     * never automatically retries or generates DDL from schema.tuprel.
     * @param targetSchema explicit preexisting schema
     * @param directory existing reviewed format-v1 catalog, without concurrent writers
     * @param approval digest explicitly reviewed against current SQL/risks/state
     * @return outcome plus newly confirmed migrations, even on partial failure
     */
    public MigrationExecutionResult deploy(String targetSchema, Path directory, MigrationPlanDigest approval) {
        Objects.requireNonNull(targetSchema, "targetSchema"); Objects.requireNonNull(directory, "directory"); Objects.requireNonNull(approval, "approval");
        return MigrationRunner.deploy(source, settings, targetSchema, directory, approval);
    }

    /** Excludes connection configuration from logging. */
    @Override public String toString() { return "PostgresqlMigrationRunner[redacted]"; }
}
