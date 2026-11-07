package dev.tuprel.migrate.postgresql;

import static dev.tuprel.migrate.postgresql.MigrationExecutionResult.Failure;

import dev.tuprel.migrate.MigrationDirectory;
import dev.tuprel.migrate.MigrationFile;
import dev.tuprel.migrate.MigrationHistory;
import dev.tuprel.migrate.MigrationHistoryException;
import dev.tuprel.migrate.MigrationValidationException;
import dev.tuprel.migrate.postgresql.ddl.DdlParseResult;
import dev.tuprel.migrate.postgresql.ddl.PostgresqlDdlParser;
import dev.tuprel.migrate.postgresql.diff.SchemaSnapshot;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

/** Internal physical preview for the existing, administratively provisioned PostgreSQL profile. */
final class PostgresqlPhysicalApproval {
    private final MigrationDatabaseSettings settings;
    private final DataSource source;
    private final PostgresqlInitialBootstrap provenance;

    PostgresqlPhysicalApproval(MigrationDatabaseSettings settings, DataSource source, PostgresqlInitialBootstrap provenance) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.source = Objects.requireNonNull(source, "source");
        this.provenance = Objects.requireNonNull(provenance, "provenance");
    }

    Inspection inspect(String schema, Path directory, InitialBootstrapAuthorization authorization)
            throws MigrationInspectionException {
        try (MigrationSession session = MigrationSession.open(source, settings, schema)) {
            MigrationPlanTarget target = settings.planTarget(schema);
            List<MigrationFile> files = MigrationDirectory.discover(directory);
            return read(session.connection(), target, files, authorization);
        } catch (MigrationSessionException failure) {
            throw new MigrationInspectionException(MigrationRunner.sessionFailure(failure));
        } catch (MigrationValidationException | SecurityException failure) {
            throw new MigrationInspectionException(Failure.FILES);
        } catch (MigrationHistoryException failure) {
            throw new MigrationInspectionException(Failure.CONFIGURATION);
        } catch (SQLException failure) {
            throw new MigrationInspectionException(MigrationRunner.classify(failure));
        } catch (RuntimeException failure) {
            throw new MigrationInspectionException(Failure.CONNECTION);
        }
    }

    // Caller owns the advisory lock and is responsible for a confirmed rollback before any later writes.
    Inspection read(Connection connection, MigrationPlanTarget target, List<MigrationFile> files,
            InitialBootstrapAuthorization authorization) throws SQLException, MigrationInspectionException {
        connection.setReadOnly(true);
        connection.setAutoCommit(false);
        PostgresqlCatalogObserver.configureTransaction(connection);
        try {
            PostgresqlCatalogObserver.Target live = new PostgresqlCatalogObserver(settings, source)
                    .verifyTarget(connection, target.schema(), true);
            CatalogInventory inventory = new CatalogInventory(connection, target.schema(), live.namespace());
            CatalogObservation observation = inventory.read(live.extensionNamespace());
            if (observation.state() != CatalogObservation.State.COMPLETE
                    || !provenance.verifiesHistory(connection, target, live, inventory.historyOid(), authorization)) {
                throw new MigrationInspectionException(Failure.CATALOG);
            }
            PostgresqlDriftInspector.Inspection drift = PostgresqlDriftInspector.inspect(connection, live,
                    target.schema(), inventory.historyOid(), observation, files);
            PostgresqlDriftInspector.Reference reference = drift.reference()
                    .orElseThrow(() -> new MigrationInspectionException(Failure.CATALOG));
            MigrationHistory history = reference.history();
            MigrationPlan plan = new PostgresqlMigrationPlanCompiler().compile(target, files, history);
            if (plan.state() == MigrationPlan.State.BLOCKED) { throw new MigrationInspectionException(Failure.BLOCKED); }
            List<DdlParseResult> replay = new ArrayList<>(reference.applied());
            PostgresqlDdlParser parser = new PostgresqlDdlParser();
            files.subList(history.applied().size(), files.size()).stream().map(parser::parse).forEach(replay::add);
            SchemaSnapshot.fromParsed(target.schema(), replay);
            PhysicalDeployDigest digest = PhysicalDeployDigest.bind(target, live, plan, reference.expected(), observation);
            return new Inspection(plan, history, new Approval(authorization.claims().generation(), digest));
        } catch (PostgresqlCatalogObserver.ObservationFailure | IllegalArgumentException failure) {
            throw new MigrationInspectionException(Failure.CATALOG);
        }
    }

    /** Generation comes from the pinned and consumed provisioning evidence, outside the digest framing. */
    record Approval(UUID generation, PhysicalDeployDigest digest) { }

    record Inspection(MigrationPlan plan, MigrationHistory history, Approval approval) { }
}
