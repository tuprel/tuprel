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
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
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

    @Test
    @EnabledIfSystemProperty(named = "tuprel.cli.legacyLauncher", matches = ".+")
    void pinnedLegacyExecutorLosesWriteAccessAfterFixtureCutover() throws Exception {
        boolean isolatedRoles = System.getProperty("tuprel.test.database").equals("container");
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String owner = isolatedRoles ? "cutover_owner_" + suffix : "tuprel_status_reader";
        String legacyUser = isolatedRoles ? "cutover_legacy_" + suffix : source.getUser();
        String credential = isolatedRoles ? UUID.randomUUID().toString() : source.getPassword();
        PGSimpleDataSource legacy = fixtureLogin(legacyUser, credential);
        String oldLauncher = System.getProperty("tuprel.cli.legacyLauncher");
        String protectedSchema = schema + "_cut";
        String namespace = quote(protectedSchema);
        String history = namespace + ".\"_tuprel_migrations\"";
        String items = namespace + ".\"items\"";
        boolean ownerCreated = false;
        boolean legacyCreated = false;
        boolean protectedCreated = false;
        try {
            if (isolatedRoles) {
                execute("CREATE ROLE " + quote(owner) + " NOLOGIN NOSUPERUSER NOCREATEROLE NOCREATEDB");
                ownerCreated = true;
                execute("CREATE ROLE " + quote(legacyUser)
                        + " LOGIN NOSUPERUSER NOINHERIT NOCREATEROLE NOCREATEDB NOBYPASSRLS PASSWORD '" + credential + "'");
                legacyCreated = true;
            }
            execute("CREATE SCHEMA " + namespace + " AUTHORIZATION " + quote(owner));
            protectedCreated = true;
            asOwner(owner, "GRANT USAGE, CREATE ON SCHEMA " + namespace + " TO " + quote(legacyUser));
            Path original = migration("20261031_120000_initial", "CREATE TABLE " + items + " (\"id\" INTEGER PRIMARY KEY);\n");
            byte[] bytes = Files.readAllBytes(original);
            Result initial = launch(legacy, oldLauncher, true, true, "migrate", "deploy", "--target-schema", protectedSchema, "--dry-run");
            assertEquals(0, initial.exit(), initial::output);
            Result applied = launch(legacy, oldLauncher, true, true,
                    "migrate", "deploy", "--target-schema", protectedSchema, "--approve-digest", digest(initial));
            assertEquals(0, applied.exit(), applied::output);
            MigrationHistory before = new PostgresqlMigrationHistory(source).read(protectedSchema);
            assertEquals(1, before.applied().size());

            execute("ALTER TABLE " + items + " OWNER TO " + quote(owner));
            execute("ALTER TABLE " + history + " OWNER TO " + quote(owner));
            asOwner(owner, "REVOKE CREATE ON SCHEMA " + namespace + " FROM " + quote(legacyUser)
                    + "; REVOKE ALL ON " + history + " FROM " + quote(legacyUser)
                    + "; GRANT SELECT ON " + history + " TO " + quote(legacyUser));
            try (Connection connection = legacy.getConnection();
                    PreparedStatement probe = connection.prepareStatement("""
                        SELECT pg_catalog.has_schema_privilege(?, 'CREATE'),
                               pg_catalog.has_table_privilege(?, 'INSERT'),
                               pg_catalog.has_table_privilege(?, 'SELECT'),
                               pg_catalog.pg_has_role(?, 'MEMBER')
                        """)) {
                probe.setString(1, protectedSchema);
                probe.setString(2, history);
                probe.setString(3, history);
                probe.setString(4, owner);
                try (ResultSet rows = probe.executeQuery()) {
                    assertTrue(rows.next());
                    assertFalse(rows.getBoolean(1));
                    assertFalse(rows.getBoolean(2));
                    assertTrue(rows.getBoolean(3));
                    if (isolatedRoles) { assertFalse(rows.getBoolean(4)); }
                }
            }

            migration("20261031_120001_next", "CREATE TABLE " + namespace + ".\"blocked\" (\"id\" INTEGER);\n");
            Result after = launch(legacy, oldLauncher, true, true,
                    "migrate", "deploy", "--target-schema", protectedSchema, "--dry-run");
            assertEquals(0, after.exit(), after::output);
            Result refused = launch(legacy, oldLauncher, true, true,
                    "migrate", "deploy", "--target-schema", protectedSchema, "--approve-digest", digest(after));
            assertEquals(2, refused.exit(), refused::output);
            assertTrue(refused.output().contains("PERMISSION"), refused::output);
            Result status = launch(legacy, oldLauncher, true, true, "migrate", "status", "--target-schema", protectedSchema);
            assertEquals(0, status.exit(), status::output);
            assertEquals(before, new PostgresqlMigrationHistory(source).read(protectedSchema));
            assertArrayEquals(bytes, Files.readAllBytes(original));
            try (Connection connection = source.getConnection();
                    PreparedStatement probe = connection.prepareStatement("SELECT pg_catalog.to_regclass(?) IS NULL")) {
                probe.setString(1, namespace + ".\"blocked\"");
                try (ResultSet rows = probe.executeQuery()) { assertTrue(rows.next()); assertTrue(rows.getBoolean(1)); }
            }
            if (isolatedRoles) {
                closeLegacyAccess(legacy, legacyUser);
                verifyReadOnlyIdentity(owner, "cutover_read_" + suffix, protectedSchema, history, oldLauncher);
            }
        } finally {
            try {
                if (protectedCreated) { asOwner(owner, "DROP SCHEMA " + namespace + " CASCADE"); }
            } finally {
                try {
                    if (legacyCreated) { execute("DROP ROLE " + quote(legacyUser)); }
                } finally {
                    if (ownerCreated) { execute("DROP ROLE " + quote(owner)); }
                }
            }
        }
    }

    private static void closeLegacyAccess(PGSimpleDataSource legacy, String role) throws Exception {
        try (Connection existing = legacy.getConnection();
                Statement session = existing.createStatement();
                ResultSet backend = session.executeQuery("SELECT pg_catalog.pg_backend_pid()")) {
            assertTrue(backend.next());
            int pid = backend.getInt(1);
            execute("ALTER ROLE " + quote(role) + " NOLOGIN");
            try (Connection control = source.getConnection();
                    PreparedStatement terminate = control.prepareStatement("SELECT pg_catalog.pg_terminate_backend(?, 5000)")) {
                terminate.setQueryTimeout(10);
                terminate.setInt(1, pid);
                try (ResultSet result = terminate.executeQuery()) {
                    assertTrue(result.next());
                    assertTrue(result.getBoolean(1));
                }
            }
            assertFalse(existing.isValid(5));
        }
        SQLException refused = assertThrows(SQLException.class, () -> {
            try (Connection forbidden = legacy.getConnection()) { assertFalse(forbidden.isValid(5)); }
        });
        assertEquals("28000", refused.getSQLState());
    }

    private void verifyReadOnlyIdentity(String owner, String role, String schema, String history, String launcher)
            throws Exception {
        String credential = UUID.randomUUID().toString();
        execute("CREATE ROLE " + quote(role)
                + " LOGIN NOSUPERUSER NOINHERIT NOCREATEROLE NOCREATEDB NOBYPASSRLS PASSWORD '" + credential + "'");
        try {
            asOwner(owner, "GRANT USAGE ON SCHEMA " + quote(schema) + " TO " + quote(role)
                    + "; GRANT SELECT ON " + history + " TO " + quote(role));
            PGSimpleDataSource reader = fixtureLogin(role, credential);
            Result status = launch(reader, launcher, true, true, "migrate", "status", "--target-schema", schema);
            assertEquals(0, status.exit(), status::output);
            try (Connection connection = reader.getConnection(); PreparedStatement probe = connection.prepareStatement("""
                    SELECT pg_catalog.has_schema_privilege(?, 'CREATE'),
                           pg_catalog.has_table_privilege(?, 'INSERT'),
                           pg_catalog.pg_has_role(?, 'MEMBER')
                    """)) {
                probe.setString(1, schema);
                probe.setString(2, history);
                probe.setString(3, owner);
                try (ResultSet rows = probe.executeQuery()) {
                    assertTrue(rows.next());
                    assertFalse(rows.getBoolean(1));
                    assertFalse(rows.getBoolean(2));
                    assertFalse(rows.getBoolean(3));
                }
            }
        } finally {
            asOwner(owner, "REVOKE USAGE ON SCHEMA " + quote(schema) + " FROM " + quote(role)
                    + "; REVOKE SELECT ON " + history + " FROM " + quote(role));
            execute("DROP ROLE " + quote(role));
        }
    }

    private static PGSimpleDataSource fixtureLogin(String role, String password) {
        PGSimpleDataSource login = new PGSimpleDataSource();
        login.setURL(source.getURL());
        login.setUser(role);
        login.setPassword(password);
        return login;
    }

    private static void asOwner(String owner, String sql) throws Exception {
        try (Connection connection = source.getConnection(); Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(20);
            statement.execute("SET ROLE " + quote(owner));
            statement.execute(sql);
        }
    }

    private static String quote(String value) { return "\"" + new dev.tuprel.sql.SqlIdentifier(value).name() + "\""; }

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
        assertEquals(0, launch(true, false, "migrate", "diff", "--help").exit());
        assertEquals(0, launch(false, true, "migrate", "dev", "--help").exit());
        for (String command : List.of("reset", "history")) { assertEquals(2, launch(true, false, "migrate", command, "--help").exit()); }
        assertFalse(history().initialised());
        assertEquals("TUPREL_DATABASE_URL=private_marker\nTUPREL_DATABASE_PASSWORD=private_marker\n", Files.readString(project.resolve(".env")));
    }

    @Test void developmentPreviewUsesManagedHistoryWithoutWritingFilesOrSql() throws Exception {
        Path sql = migration("20261031_120000_initial",
                "-- private_marker\nCREATE TABLE " + table("Item") + " (\"id\" INTEGER PRIMARY KEY);\n");
        byte[] original = Files.readAllBytes(sql);
        Path desired = project.resolve("tuprel/custom schema.tuprel");
        byte[] schemaBytes = "model Item { id Int @id label String? }\n".getBytes(StandardCharsets.UTF_8);
        Files.write(desired, schemaBytes);
        String[] previewArgs = {"migrate", "dev", "--name", "add_label", "--target-schema", schema,
                "--schema", "tuprel/custom schema.tuprel", "--confirm-managed-history"};
        Result missing = launch(true, true, previewArgs);
        assertEquals(2, missing.exit(), missing::output);
        assertTrue(missing.output().contains("REFERENCE_MISSING"));
        assertFalse(history().initialised());
        Result deployed = launch(true, false, "migrate", "deploy", "--target-schema", schema, "--approve-digest",
                digest(launch(true, false, "migrate", "deploy", "--target-schema", schema, "--dry-run")));
        assertEquals(0, deployed.exit(), deployed::output);
        MigrationHistory applied = history();

        Result preview = launch(true, true, previewArgs);
        assertEquals(0, preview.exit(), preview::output);
        assertTrue(preview.output().contains("REVIEW_REQUIRED"));
        assertTrue(preview.output().contains("ACCESS_EXCLUSIVE_LOCK"));
        assertTrue(preview.output().contains("ALTER TABLE " + table("Item") + " ADD COLUMN \"label\" \"pg_catalog\".\"text\";"), preview::output);
        assertFalse(preview.output().contains("Approval digest:"));
        assertFalse(preview.output().contains("private_marker"));
        assertEquals(preview.output(), launch(true, true, previewArgs).output());
        assertEquals(applied, history());
        assertArrayEquals(original, Files.readAllBytes(sql));
        assertArrayEquals(schemaBytes, Files.readAllBytes(desired));
        assertEquals(1, MigrationDirectory.discover(project.resolve("tuprel/migrations")).size());
        assertFalse(Files.exists(project.resolve("tuprel/.migrations.create.lock")));
        try (Connection connection = source.getConnection(); Statement query = connection.createStatement();
                ResultSet rows = query.executeQuery("SELECT * FROM " + table("Item") + " LIMIT 0")) {
            assertEquals(1, rows.getMetaData().getColumnCount());
        }
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
        return launch(source, System.getProperty("tuprel.cli.launcher"), configured, platformShell, arguments);
    }

    private Result launch(PGSimpleDataSource fixture, String launcherBase, boolean configured,
            boolean platformShell, String... arguments) throws Exception {
        boolean windows = System.getProperty("os.name").startsWith("Windows");
        String launcher = launcherBase + (windows ? ".bat" : "");
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
            environment.put("TUPREL_DATABASE_URL", "jdbc:postgresql://" + fixture.getServerNames()[0] + ":" + fixture.getPortNumbers()[0] + "/" + fixture.getDatabaseName());
            environment.put("TUPREL_DATABASE_USER", fixture.getUser()); environment.put("TUPREL_DATABASE_PASSWORD", fixture.getPassword());
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
            assertFalse(text.contains(fixture.getPassword()), "Packaged launcher exposed fixture credentials");
            assertFalse(text.contains("\u001b"), "Packaged launcher emitted ANSI colours");
            return new Result(process.exitValue(), text);
        } finally { process.destroyForcibly(); }
    }
    private record Result(int exit, String output) { }
}
