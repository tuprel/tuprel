package dev.tuprel.migrate.postgresql;

import static dev.tuprel.migrate.postgresql.MigrationExecutionResult.Failure;

import dev.tuprel.migrate.MigrationDirectory;
import dev.tuprel.migrate.MigrationFile;
import dev.tuprel.migrate.MigrationHistory;
import dev.tuprel.migrate.MigrationHistoryException;
import dev.tuprel.migrate.MigrationValidationException;
import dev.tuprel.migrate.postgresql.ddl.DdlParseResult;
import dev.tuprel.migrate.postgresql.ddl.PostgresqlDdlParser;
import dev.tuprel.migrate.postgresql.diff.SchemaDiff;
import dev.tuprel.migrate.postgresql.diff.SchemaSnapshot;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

    MigrationExecutionResult deploy(String schema, Path directory, Approval approval, InitialBootstrapAuthorization authorization) {
        return MigrationRunner.deployPhysical(source, settings, schema, directory, this,
                Objects.requireNonNull(approval, "approval"), authorization);
    }

    // Caller owns the advisory lock and is responsible for a confirmed rollback before any later writes.
    Inspection read(Connection connection, MigrationPlanTarget target, List<MigrationFile> files,
            InitialBootstrapAuthorization authorization) throws SQLException, MigrationInspectionException {
        connection.setReadOnly(true);
        connection.setAutoCommit(false);
        PostgresqlCatalogObserver.configureTransaction(connection);
        try {
            Observed observed = observe(connection, target, authorization, true);
            PostgresqlDriftInspector.Inspection drift = PostgresqlDriftInspector.inspect(connection, observed.target(),
                    target.schema(), observed.historyOid(), observed.catalog(), files);
            PostgresqlDriftInspector.Reference reference = drift.reference()
                    .orElseThrow(() -> new MigrationInspectionException(Failure.CATALOG));
            MigrationHistory history = reference.history();
            MigrationPlan plan = new PostgresqlMigrationPlanCompiler().compile(target, files, history);
            if (plan.state() == MigrationPlan.State.BLOCKED) { throw new MigrationInspectionException(Failure.BLOCKED); }
            List<DdlParseResult> replay = new ArrayList<>(reference.applied());
            PostgresqlDdlParser parser = new PostgresqlDdlParser();
            files.subList(history.applied().size(), files.size()).stream().map(parser::parse).forEach(replay::add);
            SchemaSnapshot.fromParsed(target.schema(), replay);
            PhysicalDeployDigest digest = PhysicalDeployDigest.bind(target, observed.target(), plan, reference.expected(), observed.catalog());
            Revalidation revalidation = new Revalidation(target, authorization, observed.target(),
                    observed.catalog().relationOids(), reference.expected(), reference.applied());
            return new Inspection(plan, history, new Approval(authorization.claims().generation(), digest), revalidation);
        } catch (PostgresqlCatalogObserver.ObservationFailure | IllegalArgumentException failure) {
            throw new MigrationInspectionException(Failure.CATALOG);
        }
    }

    private Observed observe(Connection connection, MigrationPlanTarget target, InitialBootstrapAuthorization authorization,
            boolean readOnly) throws SQLException, PostgresqlCatalogObserver.ObservationFailure, MigrationInspectionException {
        PostgresqlCatalogObserver observer = new PostgresqlCatalogObserver(settings, source);
        PostgresqlCatalogObserver.Target live = readOnly ? observer.verifyTarget(connection, target.schema(), true)
                : observer.verifyWriteTarget(connection, target.schema());
        CatalogInventory inventory = new CatalogInventory(connection, target.schema(), live.namespace());
        CatalogObservation catalog = inventory.read(live.extensionNamespace());
        if (catalog.state() != CatalogObservation.State.COMPLETE
                || !provenance.verifiesHistory(connection, target, live, inventory.historyOid(), authorization)) {
            throw new MigrationInspectionException(Failure.CATALOG);
        }
        return new Observed(live, catalog, inventory.historyOid());
    }

    final class Revalidation {
        private final MigrationPlanTarget target;
        private final InitialBootstrapAuthorization authorization;
        private final PostgresqlCatalogObserver.Target live;
        private final Map<String, Long> identities;
        private final SchemaSnapshot expected;
        private final List<DdlParseResult> applied;

        private Revalidation(MigrationPlanTarget target, InitialBootstrapAuthorization authorization,
                PostgresqlCatalogObserver.Target live, Map<String, Long> identities, SchemaSnapshot expected,
                List<DdlParseResult> applied) {
            this.target = target;
            this.authorization = authorization;
            this.live = live;
            this.identities = identities;
            this.expected = expected;
            this.applied = List.copyOf(applied);
        }

        void before(Connection connection) throws SQLException, MigrationRunFailure {
            if (!checked(connection, expected).equals(identities)) { throw new MigrationRunFailure(Failure.CATALOG, false); }
        }

        Revalidation after(Connection connection, DdlParseResult parsed) throws SQLException, MigrationRunFailure {
            List<DdlParseResult> next = new ArrayList<>(applied);
            next.add(parsed);
            SchemaSnapshot wanted;
            try { wanted = SchemaSnapshot.fromParsed(target.schema(), next); }
            catch (IllegalArgumentException failure) { throw new MigrationRunFailure(Failure.BLOCKED, false); }
            Map<String, Long> current = checked(connection, wanted);
            if (!current.entrySet().containsAll(identities.entrySet())) { throw new MigrationRunFailure(Failure.CATALOG, false); }
            return new Revalidation(target, authorization, live, current, wanted, next);
        }

        private Map<String, Long> checked(Connection connection, SchemaSnapshot wanted) throws SQLException, MigrationRunFailure {
            try {
                Observed observed = observe(connection, target, authorization, false);
                if (observed.target().equals(live)
                        && SchemaDiff.structuralChanges(wanted, observed.catalog().snapshot().orElseThrow()).isEmpty()) {
                    return observed.catalog().relationOids();
                }
            } catch (PostgresqlCatalogObserver.ObservationFailure | MigrationInspectionException | IllegalArgumentException failure) {
                throw new MigrationRunFailure(Failure.CATALOG, false);
            }
            throw new MigrationRunFailure(Failure.CATALOG, false);
        }
    }

    private record Observed(PostgresqlCatalogObserver.Target target, CatalogObservation catalog, long historyOid) { }

    /** Generation comes from the pinned and consumed provisioning evidence, outside the digest framing. */
    record Approval(UUID generation, PhysicalDeployDigest digest) { }

    record Inspection(MigrationPlan plan, MigrationHistory history, Approval approval, Revalidation revalidation) { }
}
