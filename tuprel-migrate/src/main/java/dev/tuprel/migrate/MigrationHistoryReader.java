package dev.tuprel.migrate;

/** Database-independent port consumed by migration status. */
@FunctionalInterface
public interface MigrationHistoryReader {
    /**
     * Reads one consistent, read-only history snapshot without bootstrap.
     * @param targetSchema explicit existing target
     * @return immutable snapshot
     * @throws MigrationHistoryException for unsafe, incompatible or inaccessible history
     */
    MigrationHistory read(String targetSchema) throws MigrationHistoryException;
}
