package dev.tuprel.migrate.postgresql;

import static dev.tuprel.migrate.postgresql.PhysicalDriftReport.Diagnostic;

import dev.tuprel.migrate.MigrationDirectory;
import dev.tuprel.migrate.MigrationFile;
import dev.tuprel.migrate.MigrationHistory;
import dev.tuprel.migrate.MigrationHistoryException;
import dev.tuprel.migrate.MigrationStatus;
import dev.tuprel.migrate.MigrationValidationException;
import dev.tuprel.migrate.postgresql.ddl.DdlParseResult;
import dev.tuprel.migrate.postgresql.ddl.PostgresqlDdlParser;
import dev.tuprel.migrate.postgresql.diff.SchemaDiff;
import dev.tuprel.migrate.postgresql.diff.SchemaSnapshot;
import dev.tuprel.sql.SqlIdentifier;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Objects;

/**
 * Assesses physical drift against a verified applied migration prefix in the ADR-0020 profile.
 * Every result is read-only and temporal. It confers no execution permission.
 */
public final class PostgresqlDriftInspector {
    /** Administrative prerequisites confirmed by the caller for the exact target and invocation. */
    public enum ReferenceControl {
        /** Default without verified provenance and an external DDL exclusion window. */ UNVERIFIED,
        /** Managed v1 history of trusted provenance, with external DDL excluded until the call returns. */ MANAGED_HISTORY
    }

    private final PostgresqlCatalogObserver observer;

    /**
     * Uses validated migration settings and dedicated PostgreSQL 17 sessions.
     * @param settings explicit target and TLS configuration
     */
    public PostgresqlDriftInspector(MigrationDatabaseSettings settings) {
        this(new PostgresqlCatalogObserver(settings));
    }

    PostgresqlDriftInspector(PostgresqlCatalogObserver observer) { this.observer = observer; }

    /**
     * Reports that the reference cannot be verified without confirmation of administrative control.
     * @param schema explicit target schema
     * @param directory v1 migration catalog
     * @return unknown reference without a history read
     */
    public PhysicalDriftReport inspect(SqlIdentifier schema, Path directory) {
        return inspect(schema, directory, ReferenceControl.UNVERIFIED);
    }

    /**
     * Replays exact local artifacts that match applied history and compares the physical snapshot.
     * Use a confirmed control mode only for the exact target while an administrative window
     * excludes external DDL. The library verifies live identity and contents. It cannot
     * establish the administrative prerequisites itself. Pending files are excluded from replay.
     * @param schema explicit target schema
     * @param directory root of the validated v1 local migration catalog
     * @param control confirmation of the external controls for this call
     * @return an assessment limited to the versioned observation profile
     */
    public PhysicalDriftReport inspect(SqlIdentifier schema, Path directory, ReferenceControl control) {
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(directory, "directory");
        Objects.requireNonNull(control, "control");
        if (control == ReferenceControl.UNVERIFIED) { return PhysicalDriftReport.unknown(Diagnostic.REFERENCE_CONTROL); }
        List<MigrationFile> files;
        try { files = MigrationDirectory.discover(directory); }
        catch (MigrationValidationException | SecurityException failure) {
            return PhysicalDriftReport.unknown(Diagnostic.LOCAL_CATALOG);
        }
        return observer.observe(schema, (connection, target, oid, catalog) ->
                inspect(connection, target, schema, oid, catalog, files),
                diagnostic -> PhysicalDriftReport.catalogUnknown(List.of(diagnostic)));
    }

    private static PhysicalDriftReport inspect(Connection connection, PostgresqlCatalogObserver.Target target,
            SqlIdentifier schema, long oid, CatalogObservation catalog, List<MigrationFile> files)
            throws SQLException {
        if (catalog.state() != CatalogObservation.State.COMPLETE) {
            return PhysicalDriftReport.catalogUnknown(catalog.diagnostics());
        }
        SchemaSnapshot observed = catalog.snapshot().orElseThrow();
        if (oid == 0) { return PhysicalDriftReport.unknown(Diagnostic.REFERENCE_MISSING); }
        if (!liveIdentity(connection, target, schema, oid)) {
            return PhysicalDriftReport.unknown(Diagnostic.HISTORY_IDENTITY);
        }
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(5);
            // Both components are validated identifiers. ONLY excludes inherited tables.
            // The operational DDL window keeps the verified name bound while taking the lock.
            statement.execute("LOCK TABLE ONLY " + qualifiedHistory(schema) + " IN ACCESS SHARE MODE");
        }
        if (!lockHeld(connection, target.database(), oid) || !liveIdentity(connection, target, schema, oid)) {
            return PhysicalDriftReport.unknown(Diagnostic.HISTORY_IDENTITY);
        }
        MigrationHistory history;
        try { history = PostgresqlMigrationHistory.snapshot(connection, schema, 5); }
        catch (MigrationHistoryException failure) {
            if (failure.reason() == MigrationHistoryException.Reason.PERMISSION) {
                return PhysicalDriftReport.catalogUnknown(List.of(CatalogObservation.Diagnostic.PERMISSION));
            }
            return PhysicalDriftReport.unknown(Diagnostic.HISTORY_INCOMPATIBLE);
        } catch (IllegalArgumentException failure) {
            return PhysicalDriftReport.unknown(Diagnostic.HISTORY_INCOMPATIBLE);
        }
        if (!history.initialised() || !liveIdentity(connection, target, schema, oid)) {
            return PhysicalDriftReport.unknown(Diagnostic.HISTORY_IDENTITY);
        }
        if (MigrationStatus.compare(files, history).hasProblems()) {
            return PhysicalDriftReport.unknown(Diagnostic.APPLIED_PREFIX);
        }
        try {
            PostgresqlDdlParser parser = new PostgresqlDdlParser();
            List<DdlParseResult> applied = files.subList(0, history.applied().size()).stream().map(parser::parse).toList();
            SchemaSnapshot expected = SchemaSnapshot.fromParsed(schema, applied);
            return PhysicalDriftReport.compared(SchemaDiff.structuralChanges(expected, observed));
        } catch (IllegalArgumentException failure) {
            return PhysicalDriftReport.unknown(Diagnostic.REPLAY);
        }
    }

    private static boolean liveIdentity(Connection connection, PostgresqlCatalogObserver.Target target,
            SqlIdentifier schema, long oid) throws SQLException {
        // regnamespace/regclass resolve the live name, independently of the earlier catalog tuples.
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT pg_catalog.to_regnamespace(?)::pg_catalog.oid,
                       pg_catalog.to_regclass(?)::pg_catalog.oid
                """)) {
            statement.setQueryTimeout(5);
            statement.setString(1, quote(schema));
            statement.setString(2, qualifiedHistory(schema));
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getLong(1) == target.namespace() && result.getLong(2) == oid && !result.next();
            }
        }
    }

    private static boolean lockHeld(Connection connection, long database, long relation) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT EXISTS (
                    SELECT 1 FROM pg_catalog.pg_locks l
                    WHERE l.pid = pg_catalog.pg_backend_pid() AND l.locktype = 'relation'
                      AND l.database = ?::pg_catalog.oid AND l.relation = ?::pg_catalog.oid
                      AND l.mode = 'AccessShareLock' AND l.granted
                )
                """)) {
            statement.setQueryTimeout(5);
            statement.setLong(1, database);
            statement.setLong(2, relation);
            try (ResultSet result = statement.executeQuery()) { return result.next() && result.getBoolean(1); }
        }
    }

    private static String qualifiedHistory(SqlIdentifier schema) {
        return quote(schema) + ".\"" + MigrationHistoryTable.NAME + "\"";
    }

    private static String quote(SqlIdentifier identifier) { return "\"" + identifier.name() + "\""; }
}
