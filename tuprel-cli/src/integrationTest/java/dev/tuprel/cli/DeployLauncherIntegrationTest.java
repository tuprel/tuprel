package dev.tuprel.cli;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuprel.migrate.MigrationDirectory;
import dev.tuprel.migrate.MigrationHistory;
import dev.tuprel.migrate.postgresql.PostgresqlMigrationHistory;
import dev.tuprel.testsupport.TestPostgresql;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;

class DeployLauncherIntegrationTest {
    private static TestPostgresql database;
    private static PGSimpleDataSource source;
    private static final AtomicInteger sequence = new AtomicInteger();
    @TempDir Path temporary;
    private Path project;
    private String schema;
    private boolean created;
    @BeforeAll static void open() throws Exception { database = TestPostgresql.open(); source = database.dataSource(); }
    @AfterAll static void close() throws Exception { if (database != null) { database.close(); } }
    @BeforeEach void fixture() throws Exception {
        schema = source.getCurrentSchema() + "_cli" + sequence.incrementAndGet();
        execute("CREATE SCHEMA \"" + schema + "\""); created = true;
        project = Files.createDirectory(temporary.resolve("project with espaço")).toRealPath();
        Path migrations = Files.createDirectories(project.resolve("tuprel/migrations"));
        Files.writeString(migrations.resolve(MigrationDirectory.FORMAT_FILE), MigrationDirectory.FORMAT_HEADER);
        // Deployment must use existing SQL files, never parse/generate from this unrelated schema.
        Files.writeString(project.resolve("tuprel/schema.tuprel"), "deliberately not a valid schema\n");
    }
    @AfterEach void cleanup() throws Exception { if (created) { execute("DROP SCHEMA \"" + schema + "\" CASCADE"); } }

    @Test void platformShellPreviewsApprovesAndReobservesWithoutGeneratingSql() throws Exception {
        Path sql = migration("20261031_120000_initial", "-- private_marker\nCREATE TABLE " + table("items") + " (\"id\" INTEGER PRIMARY KEY);\n");
        String original = Files.readString(sql);
        Result preview = launch(true, true, "migrate", "deploy", "--target-schema", schema, "--dry-run");
        assertEquals(0, preview.exit(), preview::output); assertTrue(preview.output().contains("REVIEW_REQUIRED"));
        assertTrue(preview.output().contains("DDL_LOCKS")); assertTrue(preview.output().contains("DRIFT_NOT_CHECKED"));
        assertFalse(preview.output().contains("private_marker")); assertFalse(history().initialised()); assertFalse(exists("items"));
        assertEquals(original, Files.readString(sql)); assertFalse(Files.exists(project.resolve("tuprel/.migrations.create.lock")));
        assertEquals("deliberately not a valid schema\n", Files.readString(project.resolve("tuprel/schema.tuprel")));
        String approval = digest(preview);
        Result deployed = launch(true, true, "migrate", "deploy", "--target-schema", schema, "--approve-digest", approval);
        assertEquals(0, deployed.exit(), deployed::output); assertTrue(deployed.output().contains("Deployment: APPLIED"));
        assertTrue(deployed.output().contains("Confirmed this invocation: 1")); assertTrue(exists("items")); assertEquals(1, history().applied().size());
        assertEquals(MigrationDirectory.discover(project.resolve("tuprel/migrations")).getFirst().checksum(), history().applied().getFirst().checksum());
        Result stale = launch(true, false, "migrate", "deploy", "--target-schema", schema, "--approve-digest", approval);
        assertEquals(2, stale.exit()); assertTrue(stale.output().contains("APPROVAL")); assertEquals(1, history().applied().size());
        Result current = launch(true, false, "migrate", "deploy", "--dry-run", "--target-schema", schema);
        assertEquals(0, current.exit()); assertTrue(current.output().contains("NO_CHANGES"));
        Result noChanges = launch(true, false, "migrate", "deploy", "--approve-digest", digest(current), "--target-schema", schema);
        assertEquals(0, noChanges.exit()); assertTrue(noChanges.output().contains("NO_CHANGES"));
        assertEquals(1, history().applied().size()); assertEquals(original, Files.readString(sql));
        Result status = launch(true, false, "migrate", "status", "--target-schema", schema);
        assertEquals(0, status.exit()); assertTrue(status.output().contains("APPLIED"));
    }

    @Test void changedArtifactsAndUnknownStatementsCannotBeApprovedOrPartiallyPreviewed() throws Exception {
        Path sql = migration("20261031_120000_initial", "CREATE TABLE " + table("items") + " (\"id\" INTEGER);\n");
        Result preview = launch(true, false, "migrate", "deploy", "--target-schema", schema, "--dry-run");
        assertEquals(0, preview.exit()); String approval = digest(preview);
        Files.writeString(sql, Files.readString(sql) + "-- private_marker changed\n");
        Result changed = launch(true, false, "migrate", "deploy", "--target-schema", schema, "--approve-digest", approval);
        assertEquals(2, changed.exit()); assertTrue(changed.output().contains("APPROVAL")); assertFalse(history().initialised());
        Files.writeString(sql, "CREATE TABLE " + table("items") + " (\"id\" INTEGER);\nSELECT 'private_marker';\n");
        Result blocked = launch(true, false, "migrate", "deploy", "--target-schema", schema, "--dry-run");
        assertEquals(2, blocked.exit()); assertTrue(blocked.output().contains("BLOCKED"));
        assertFalse(blocked.output().contains("CREATE TABLE")); assertFalse(blocked.output().contains("private_marker"));
        assertFalse(blocked.output().contains("Approval digest:"));
        Result refused = launch(true, false, "migrate", "deploy", "--target-schema", schema, "--approve-digest", approval);
        assertEquals(2, refused.exit()); assertTrue(refused.output().contains("BLOCKED"));
        assertFalse(history().initialised()); assertFalse(exists("items"));
    }

    @Test void helpAndMissingEnvironmentNeverLoadDotenvOrCreateHistory() throws Exception {
        Files.writeString(project.resolve(".env"), "TUPREL_DATABASE_URL=private_marker\nTUPREL_DATABASE_PASSWORD=private_marker\n");
        Result help = launch(false, true, "migrate", "deploy", "--help");
        assertEquals(0, help.exit()); assertTrue(help.output().contains("--approve-digest"));
        Result missing = launch(false, false, "migrate", "deploy", "--target-schema", schema, "--dry-run");
        assertEquals(2, missing.exit()); assertTrue(missing.output().contains("CONFIGURATION")); assertFalse(missing.output().contains("private_marker"));
        assertEquals(2, launch(true, false, "migrate", "deploy", "--target-schema", "../private_marker", "--dry-run").exit());
        for (String command : List.of("dev", "diff", "reset", "history")) { assertEquals(2, launch(true, false, "migrate", command, "--help").exit()); }
        assertFalse(history().initialised());
        assertEquals("TUPREL_DATABASE_URL=private_marker\nTUPREL_DATABASE_PASSWORD=private_marker\n", Files.readString(project.resolve(".env")));
    }

    @Test void aLaterFailureReportsTheConfirmedPrefixAndDoesNotExposeDriverSql() throws Exception {
        execute("CREATE TABLE " + table("existing") + " (id integer)");
        execute("INSERT INTO " + table("existing") + " VALUES (73)");
        migration("20261031_120000_first", "CREATE TABLE " + table("first") + " (\"id\" INTEGER);\n");
        migration("20261031_120001_second", "-- private_marker\nCREATE TABLE " + table("second") + " (\"id\" INTEGER);\n"
                + "ALTER TABLE " + table("existing") + " ADD COLUMN \"id\" INTEGER;\n");
        migration("20261031_120002_third", "CREATE TABLE " + table("third") + " (\"id\" INTEGER);\n");
        Result preview = launch(true, false, "migrate", "deploy", "--target-schema", schema, "--dry-run");
        assertEquals(0, preview.exit(), preview::output);
        Result failed = launch(true, false, "migrate", "deploy", "--target-schema", schema, "--approve-digest", digest(preview));
        assertEquals(2, failed.exit()); assertTrue(failed.output().contains("Deployment: FAILED"));
        assertTrue(failed.output().contains("Confirmed this invocation: 1")); assertTrue(failed.output().contains("STATEMENT"));
        assertTrue(failed.output().contains("Earlier confirmed migrations remain applied"));
        assertFalse(failed.output().contains("CREATE TABLE")); assertFalse(failed.output().contains("private_marker"));
        assertFalse(failed.output().contains("already exists")); assertTrue(exists("first")); assertFalse(exists("second")); assertFalse(exists("third"));
        assertEquals(1, history().applied().size());
        try (Connection connection = source.getConnection(); Statement query = connection.createStatement();
                ResultSet rows = query.executeQuery("SELECT id FROM " + table("existing"))) {
            assertTrue(rows.next()); assertEquals(73, rows.getInt(1)); assertFalse(rows.next());
        }
    }

    @Test void realConnectionLossReportsUnknownAndRequiresANewObservedApproval() throws Exception {
        execute("CREATE TABLE " + table("items") + " (id integer)");
        migration("20261031_120000_initial", "ALTER TABLE " + table("items") + " ADD COLUMN \"label\" TEXT;\n");
        Result preview = launch(true, false, "migrate", "deploy", "--target-schema", schema, "--dry-run");
        assertEquals(0, preview.exit()); String approval = digest(preview);
        try (Connection peer = source.getConnection(); Statement held = peer.createStatement()) {
            peer.setAutoCommit(false); held.setQueryTimeout(20);
            held.execute("LOCK TABLE ONLY " + table("items") + " IN ACCESS EXCLUSIVE MODE");
            CompletableFuture<Result> pending = CompletableFuture.supplyAsync(() -> {
                try { return launch(true, false, "migrate", "deploy", "--target-schema", schema, "--approve-digest", approval); }
                catch (Exception failure) { throw new java.util.concurrent.CompletionException(failure); }
            });
            try {
                int pid = waitingOwnedBackend();
                try (Connection control = source.getConnection(); PreparedStatement terminate = control.prepareStatement("SELECT pg_catalog.pg_terminate_backend(?)")) {
                    terminate.setQueryTimeout(10); terminate.setInt(1, pid);
                    try (ResultSet rows = terminate.executeQuery()) { assertTrue(rows.next()); assertTrue(rows.getBoolean(1)); }
                }
                Result failed = pending.get(45, TimeUnit.SECONDS);
                assertEquals(2, failed.exit()); assertTrue(failed.output().contains("Deployment: OUTCOME_UNKNOWN"));
                assertTrue(failed.output().contains("Confirmed this invocation: 0")); assertTrue(failed.output().contains("Do not assume rollback"));
                assertTrue(failed.output().contains("fresh session under lock"));
            } finally { peer.rollback(); pending.get(75, TimeUnit.SECONDS); }
        }
        assertTrue(history().initialised()); assertTrue(history().applied().isEmpty());
        Result stale = launch(true, false, "migrate", "deploy", "--target-schema", schema, "--approve-digest", approval);
        assertEquals(2, stale.exit()); assertTrue(stale.output().contains("APPROVAL"));
        Result reconciled = launch(true, false, "migrate", "deploy", "--target-schema", schema, "--dry-run");
        assertEquals(0, reconciled.exit()); assertTrue(reconciled.output().contains("REVIEW_REQUIRED"));
        Result confirmed = launch(true, false, "migrate", "deploy", "--target-schema", schema, "--approve-digest", digest(reconciled));
        assertEquals(0, confirmed.exit(), confirmed::output); assertEquals(1, history().applied().size());
    }

    private Path migration(String name, String sql) throws Exception {
        Path child = Files.createDirectories(project.resolve("tuprel/migrations").resolve(name));
        return Files.writeString(child.resolve("migration.sql"), sql);
    }
    private static String digest(Result result) {
        Matcher match = Pattern.compile("(?m)^Approval digest: ([0-9a-f]{64})\\r?$").matcher(result.output());
        assertTrue(match.find(), "Expected a reviewable plan digest"); return match.group(1);
    }
    private String table(String name) { return "\"" + schema + "\".\"" + new dev.tuprel.sql.SqlIdentifier(name).name() + "\""; }
    private MigrationHistory history() throws Exception { return new PostgresqlMigrationHistory(source).read(schema); }
    private boolean exists(String name) throws Exception {
        try (Connection connection = source.getConnection(); PreparedStatement query = connection.prepareStatement("SELECT pg_catalog.to_regclass(?) IS NOT NULL")) {
            query.setString(1, table(name));
            try (ResultSet rows = query.executeQuery()) { assertTrue(rows.next()); return rows.getBoolean(1); }
        }
    }
    private int waitingOwnedBackend() throws Exception {
        long started = System.nanoTime();
        try (Connection peer = source.getConnection(); PreparedStatement query = peer.prepareStatement("""
                SELECT l.pid FROM pg_catalog.pg_locks l JOIN pg_catalog.pg_stat_activity a ON a.pid = l.pid
                WHERE l.relation = pg_catalog.to_regclass(?) AND NOT l.granted
                  AND a.datname = pg_catalog.current_database() AND a.usename = ?
                """)) {
            query.setString(1, table("items")); query.setString(2, source.getUser()); query.setQueryTimeout(5);
            while (System.nanoTime() - started < TimeUnit.SECONDS.toNanos(20)) {
                try (ResultSet rows = query.executeQuery()) {
                    if (rows.next()) { int pid = rows.getInt(1); assertFalse(rows.next()); return pid; }
                }
                TimeUnit.MILLISECONDS.sleep(20);
            }
        }
        throw new AssertionError("No owned launcher backend reached the fixture relation lock");
    }
    private static void execute(String sql) throws Exception {
        try (Connection connection = source.getConnection(); Statement statement = connection.createStatement()) { statement.setQueryTimeout(20); statement.execute(sql); }
    }

    private Result launch(boolean configured, boolean platformShell, String... arguments) throws Exception {
        boolean windows = System.getProperty("os.name").startsWith("Windows");
        String launcher = System.getProperty("tuprel.cli.launcher") + (windows ? ".bat" : "");
        List<String> command = new ArrayList<>();
        if (windows && platformShell) {
            command.addAll(List.of("powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive", "-Command",
                    "$cliArguments = @($env:TUPREL_TEST_CLI_ARGS -split [char]10); & $env:TUPREL_TEST_CLI_LAUNCHER @cliArguments; exit $LASTEXITCODE"));
        } else {
            if (windows) { command.addAll(List.of("cmd.exe", "/d", "/c")); }
            else if (platformShell) { command.add("bash"); }
            command.add(launcher); command.addAll(List.of(arguments));
        }
        ProcessBuilder builder = new ProcessBuilder(command).directory(project.toFile()).redirectErrorStream(true);
        Map<String, String> environment = builder.environment();
        environment.remove("TUPREL_DATABASE_URL"); environment.remove("TUPREL_DATABASE_USER"); environment.remove("TUPREL_DATABASE_PASSWORD");
        if (configured) {
            // Fixture-only mapping. Production code never reads a test dotenv file.
            environment.put("TUPREL_DATABASE_URL", "jdbc:postgresql://" + source.getServerNames()[0] + ":" + source.getPortNumbers()[0] + "/" + source.getDatabaseName());
            environment.put("TUPREL_DATABASE_USER", source.getUser()); environment.put("TUPREL_DATABASE_PASSWORD", source.getPassword());
        }
        environment.put("JAVA_HOME", System.getProperty("java.home"));
        if (windows && platformShell) {
            environment.put("TUPREL_TEST_CLI_LAUNCHER", launcher); environment.put("TUPREL_TEST_CLI_ARGS", String.join("\n", arguments));
        }
        Process process = builder.start();
        CompletableFuture<String> output = CompletableFuture.supplyAsync(() -> {
            try { return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8); }
            catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
        });
        try {
            assertTrue(process.waitFor(75, TimeUnit.SECONDS), "Packaged launcher timeout");
            String text = output.get(5, TimeUnit.SECONDS);
            // Fail without echoing output if a regression ever exposes a fixture password.
            assertFalse(text.contains(source.getPassword()), "Packaged launcher exposed fixture credentials");
            assertFalse(text.contains("\u001b"), "Packaged launcher emitted ANSI colours");
            return new Result(process.exitValue(), text);
        } finally { process.destroyForcibly(); }
    }
    private record Result(int exit, String output) { }
}
