package dev.tuprel.cli;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuprel.migrate.AppliedMigration;
import dev.tuprel.migrate.MigrationDirectory;
import dev.tuprel.migrate.MigrationFile;
import dev.tuprel.migrate.MigrationHistory;
import dev.tuprel.migrate.MigrationHistoryException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MigrationStatusCommandTest {
    @TempDir Path root;
    @Test void helpDoesNotReadEnvironmentFilesOrDatabase() {
        MigrationStatusCommand command = new MigrationStatusCommand(() -> { throw new AssertionError("env access"); },
                env -> { throw new AssertionError("database access"); });
        Result help = run(command, "--help");
        assertEquals(0, help.code()); assertTrue(help.out().contains("--target-schema"));
        assertFalse(help.out().contains("deploy")); assertEquals("", help.err());
        for (String[] options : List.of(new String[]{}, new String[]{"--schema", "private_value"},
                new String[]{"--target-schema", "target", "--dry-run"})) {
            assertEquals(2, run(command, options).code());
        }
    }
    @Test void uninitialisedPendingAndEmptyHistoryAreNormalAndDriftIsExplicit() throws Exception {
        catalog();
        MigrationStatusCommand command = new MigrationStatusCommand(Map::of, env -> schema -> new MigrationHistory(false, List.of()));
        Result status = run(command, "--target-schema", "target");
        assertEquals(0, status.code()); assertTrue(status.out().contains("UNINITIALISED"));
        assertTrue(status.out().contains("PENDING")); assertTrue(status.out().contains("DRIFT_NOT_CHECKED"));
        assertFalse(status.out().contains("\u001b")); assertFalse(Files.exists(root.resolve("tuprel/.migrations.create.lock")));
    }
    @Test void divergenceUsesExitOneAndSafeOperationalFailuresUseTwo() throws Exception {
        MigrationFile file = catalog();
        AppliedMigration renamed = new AppliedMigration(file.id(), "old_name", file.checksum(), Instant.EPOCH, Instant.EPOCH, 0, "development");
        MigrationStatusCommand command = new MigrationStatusCommand(Map::of,
                env -> schema -> new MigrationHistory(true, List.of(renamed)));
        Result status = run(command, "--target-schema", "target");
        assertEquals(1, status.code()); assertTrue(status.out().contains("RENAMED"));
        assertTrue(status.err().contains("INTEGRITY"));
        Result failure = run(new MigrationStatusCommand(Map::of, env -> { throw new MigrationHistoryException(MigrationHistoryException.Reason.PERMISSION); }),
                "--target-schema", "private_value");
        assertEquals(2, failure.code()); assertFalse(failure.err().contains("private_value"));
        assertTrue(failure.err().contains("DRIFT_NOT_CHECKED")); assertEquals("", failure.out());
    }
    @Test void defaultProductionConfigurationIgnoresDotenvAndInvalidCatalogPreventsDatabaseAccess() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        MigrationStatusCommand command = new MigrationStatusCommand(Map::of, env -> { reads.incrementAndGet(); throw new AssertionError(); });
        assertEquals(2, run(command, "--target-schema", "target").code()); assertEquals(0, reads.get());
        catalog();
        Files.writeString(root.resolve(".env"), "TUPREL_DATABASE_PASSWORD=private_value\n");
        Result noEnv = run(new MigrationStatusCommand(Map::of,
                env -> new dev.tuprel.migrate.postgresql.PostgresqlMigrationHistory(
                        dev.tuprel.migrate.postgresql.MigrationDatabaseSettings.fromEnvironment(env).dataSource())), "--target-schema", "target");
        assertEquals(2, noEnv.code()); assertTrue(noEnv.err().contains("CONFIGURATION"));
        assertFalse(noEnv.err().contains("private_value"));
    }
    private MigrationFile catalog() throws Exception {
        Path catalog = Files.createDirectories(root.resolve("tuprel/migrations"));
        Files.writeString(catalog.resolve(MigrationDirectory.FORMAT_FILE), MigrationDirectory.FORMAT_HEADER);
        Path directory = Files.createDirectory(catalog.resolve("20261030_120000_initial"));
        Files.writeString(directory.resolve("migration.sql"), "-- draft\n");
        return MigrationDirectory.discover(catalog).getFirst();
    }
    private Result run(MigrationStatusCommand command, String... options) {
        String[] arguments = new String[options.length + 2]; arguments[0] = "migrate"; arguments[1] = "status";
        System.arraycopy(options, 0, arguments, 2, options.length);
        StringWriter out = new StringWriter(); StringWriter err = new StringWriter();
        int code = command.run(arguments, new PrintWriter(out, true), new PrintWriter(err, true), root);
        return new Result(code, out.toString(), err.toString());
    }
    private record Result(int code, String out, String err) {}
}
