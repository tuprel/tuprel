package dev.tuprel.migrate.postgresql;

import dev.tuprel.migrate.MigrationFile;
import dev.tuprel.migrate.MigrationHistoryException;
import dev.tuprel.migrate.NativeMigrationPublication;
import dev.tuprel.migrate.postgresql.diff.PostgresqlSchemaProjector;
import dev.tuprel.migrate.postgresql.diff.SchemaProjection;
import dev.tuprel.schema.SchemaCompilation;
import dev.tuprel.schema.SchemaCompiler;
import dev.tuprel.schema.SourceText;
import dev.tuprel.sql.SqlIdentifier;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;

/** Managed proposal publication. The advisory lock is acquired before the filesystem catalog lock. */
final class ManagedDevPublication {
    private final PostgresqlPhysicalApproval physical;
    private final MigrationDatabaseSettings settings;
    private final DataSource source;

    ManagedDevPublication(PostgresqlPhysicalApproval physical, MigrationDatabaseSettings settings, DataSource source) {
        this.physical = physical;
        this.settings = settings;
        this.source = source;
    }

    Result publish(SqlIdentifier schema, NativeMigrationPublication publication, Path sourceFile,
            String name, InitialBootstrapAuthorization proof) {
        Result result = null;
        try (MigrationSession session = MigrationSession.open(source, settings, schema.name())) {
            result = publish(session.connection(), settings.planTarget(schema.name()), publication, sourceFile, name, proof);
        } catch (MigrationInspectionException failure) {
            return Result.refused(Diagnostic.REFERENCE);
        } catch (MigrationSessionException | MigrationHistoryException | SQLException failure) {
            // Releasing a read-only session cannot invalidate a settled filesystem result.
            return result == null ? Result.refused(Diagnostic.SESSION) : result;
        } catch (IOException | IllegalArgumentException | SecurityException failure) {
            return Result.refused(Diagnostic.FILES);
        }
        return result;
    }

    private Result publish(Connection connection, MigrationPlanTarget target, NativeMigrationPublication publication,
            Path sourceFile, String name, InitialBootstrapAuthorization proof)
            throws IOException, SQLException, MigrationInspectionException {
        if (publication.hasUnresolvedAttempt()) { return Result.of(State.PUBLICATION_UNKNOWN); }
        NativeMigrationPublication.Source snapshot = publication.readSource(sourceFile);
        SchemaCompilation compilation = new SchemaCompiler().compile(SourceText.of("schema.tuprel", snapshot.text()));
        if (!compilation.isValid()) { return Result.of(State.INVALID_SCHEMA); }
        SchemaProjection projection = new PostgresqlSchemaProjector().project(target.schema(), compilation.validatedSchema().orElseThrow());
        if (projection.snapshot().isEmpty()) { return Result.refused(Diagnostic.PLAN); }
        NativeMigrationPublication.Baseline baseline = publication.readBaseline();
        PostgresqlPhysicalApproval.Inspection inspection = physical.read(connection, target, baseline.files(), proof);
        connection.rollback();
        if (inspection.plan().state() != MigrationPlan.State.NO_CHANGES) { return Result.refused(Diagnostic.PENDING); }
        MigrationDevPlan proposal = PostgresqlMigrationDevPlanner.plan(target, name, inspection.reference(),
                projection.snapshot().orElseThrow(), inspection.observation());
        if (proposal.state() == MigrationDevPlan.State.NO_CHANGES) { return Result.of(State.NO_CHANGES); }
        if (proposal.state() == MigrationDevPlan.State.BLOCKED) {
            return new Result(State.REFUSED, Optional.of(Diagnostic.PLAN), Optional.of(proposal), Optional.empty());
        }
        AtomicBoolean revalidated = new AtomicBoolean();
        NativeMigrationPublication.Result published = publication.publish(snapshot, baseline, name, proposal.sql().orElseThrow(), () -> {
            try {
                PostgresqlPhysicalApproval.Inspection latest = physical.read(connection, target, baseline.files(), proof);
                if (!latest.approval().equals(inspection.approval())) { throw new IOException("Publication reference changed"); }
                // Retain the session advisory lock without a transaction timeout during the native handshake.
                connection.rollback();
                revalidated.set(true);
            } catch (SQLException | MigrationInspectionException failure) {
                throw new IOException("Publication reference could not be revalidated");
            }
        });
        State outcome = State.valueOf(published.state().name());
        Optional<Diagnostic> diagnostic = outcome == State.REFUSED
                ? Optional.of(revalidated.get() ? Diagnostic.NATIVE : Diagnostic.BEFORE_RENAME) : Optional.empty();
        return new Result(outcome, diagnostic, Optional.of(proposal), published.file());
    }

    enum State { NO_CHANGES, INVALID_SCHEMA, REFUSED, PUBLISHED, COLLISION, PUBLICATION_UNKNOWN }
    enum Diagnostic { INSTALLATION, SESSION, FILES, REFERENCE, PENDING, PLAN, BEFORE_RENAME, NATIVE }

    record Result(State state, Optional<Diagnostic> diagnostic, Optional<MigrationDevPlan> proposal, Optional<MigrationFile> file) {
        static Result refused(Diagnostic diagnostic) { return new Result(State.REFUSED, Optional.of(diagnostic), Optional.empty(), Optional.empty()); }
        static Result of(State state) { return new Result(state, Optional.empty(), Optional.empty(), Optional.empty()); }
    }
}
