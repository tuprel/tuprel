package dev.tuprel.migrate.postgresql;

import dev.tuprel.migrate.MigrationChecksum;
import dev.tuprel.migrate.postgresql.ddl.DdlStatement;
import dev.tuprel.migrate.postgresql.diff.SchemaDiff;
import dev.tuprel.migrate.postgresql.diff.SchemaSnapshot;
import dev.tuprel.sql.SqlIdentifier;
import java.util.Map;
import java.util.TreeMap;

/** Canonical ADR-0021 evidence binding. The runner must establish provenance before using it. */
record PhysicalDeployDigest(String value) {
    static final String DOMAIN = "TUPREL-PG-PHYSICAL-DEPLOY";
    static final int VERSION = 1;

    PhysicalDeployDigest { new MigrationChecksum(value); }

    static PhysicalDeployDigest bind(MigrationPlanTarget target, PostgresqlCatalogObserver.Target liveTarget,
            MigrationPlan plan, SchemaSnapshot expected, CatalogObservation observation) {
        if (plan.state() == MigrationPlan.State.BLOCKED || observation.state() != CatalogObservation.State.COMPLETE
                || !target.schema().equals(expected.schema()) || liveTarget.extensionNamespace()) {
            throw invalid();
        }
        SchemaSnapshot observed = observation.snapshot().orElseThrow();
        Map<String, Long> inventory = new TreeMap<>(observation.relationOids());
        if (!validOid(liveTarget.database()) || !validOid(liveTarget.namespace())
                || !expected.schema().equals(observed.schema())
                || !SchemaDiff.structuralChanges(expected, observed).isEmpty()
                || !inventory.containsKey(MigrationHistoryTable.NAME)
                || inventory.size() > CatalogInventory.MAX_ROWS
                || inventory.values().stream().anyMatch(oid -> !validOid(oid))) {
            throw invalid();
        }
        String profile = observation.profile();
        MigrationChecksum expectedDigest = structural(profile, expected);
        MigrationChecksum observedDigest = structural(profile, observed);
        return new PhysicalDeployDigest(PlanEncoding.digest(output -> {
            PlanEncoding.text(output, DOMAIN);
            output.writeInt(VERSION);
            PlanEncoding.text(output, target.fingerprint().value());
            output.writeLong(liveTarget.database());
            output.writeLong(liveTarget.namespace());
            PlanEncoding.text(output, profile);
            PlanEncoding.text(output, plan.digest().value());
            PlanEncoding.text(output, expectedDigest.value());
            PlanEncoding.text(output, observedDigest.value());
            output.writeInt(inventory.size());
            for (Map.Entry<String, Long> entry : inventory.entrySet()) {
                PlanEncoding.text(output, entry.getKey());
                output.writeLong(entry.getValue());
            }
        }).value());
    }

    static MigrationChecksum structural(String profile, SchemaSnapshot snapshot) {
        return PlanEncoding.digest(output -> {
            PlanEncoding.text(output, "TUPREL-PG-CATALOG-SNAPSHOT");
            output.writeInt(1);
            PlanEncoding.text(output, profile);
            PlanEncoding.text(output, snapshot.schema().name());
            output.writeInt(snapshot.tables().size());
            for (Map.Entry<String, SchemaSnapshot.Table> entry : snapshot.tables().entrySet()) {
                PlanEncoding.text(output, entry.getKey());
                SchemaSnapshot.Table table = entry.getValue();
                output.writeInt(table.columns().size());
                for (DdlStatement.Column column : table.columns().values()) {
                    PlanEncoding.text(output, column.name().name());
                    PlanEncoding.text(output, column.type().catalogName());
                    output.writeBoolean(column.nullable());
                    output.writeBoolean(column.primaryKey());
                }
                output.writeInt(table.indexes().size());
                for (DdlStatement.CreateIndex index : table.indexes().values()) {
                    PlanEncoding.text(output, index.name().name());
                    output.writeInt(index.columns().size());
                    for (SqlIdentifier column : index.columns()) { PlanEncoding.text(output, column.name()); }
                }
            }
        });
    }

    private static boolean validOid(long oid) { return oid > 0 && oid <= 0xffff_ffffL; }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Physical deploy evidence is incomplete or incompatible");
    }
}
