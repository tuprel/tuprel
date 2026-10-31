package dev.tuprel.cli;

import dev.tuprel.migrate.MigrationDirectory;
import dev.tuprel.migrate.MigrationHistoryException;
import dev.tuprel.migrate.MigrationHistoryReader;
import dev.tuprel.migrate.MigrationStatus;
import dev.tuprel.migrate.MigrationValidationException;
import dev.tuprel.migrate.postgresql.MigrationDatabaseSettings;
import dev.tuprel.migrate.postgresql.PostgresqlMigrationHistory;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** Presentation/orchestration only; no history logic or SQL in the CLI. */
final class MigrationStatusCommand {
    private final Supplier<Map<String, String>> environment;
    private final ReaderFactory factory;

    MigrationStatusCommand() {
        this(System::getenv, env -> new PostgresqlMigrationHistory(MigrationDatabaseSettings.fromEnvironment(env).dataSource()));
    }
    MigrationStatusCommand(Supplier<Map<String, String>> environment, ReaderFactory factory) {
        this.environment = environment; this.factory = factory;
    }

    int run(String[] arguments, PrintWriter out, PrintWriter err, Path project) {
        if (arguments.length == 3 && (arguments[2].equals("--help") || arguments[2].equals("-h"))) {
            help(out); return 0;
        }
        if (arguments.length != 4 || !arguments[2].equals("--target-schema") || arguments[3].startsWith("-")) {
            err.println("TUPREL-CLI-USAGE: Run tuprel migrate status --target-schema <name>. Use --help for configuration.");
            return 2;
        }
        try {
            MigrationStatus status = MigrationStatus.compare(
                    MigrationDirectory.discover(project.toRealPath().resolve("tuprel/migrations")),
                    factory.create(environment.get()).read(arguments[3]));
            out.println("History: " + (status.initialised() ? "INITIALISED" : "UNINITIALISED"));
            out.printf("%-15s %-24s %s%n", "UTC ID", "NAME", "STATE");
            for (MigrationStatus.Entry entry : status.entries()) {
                String flags = Arrays.stream(MigrationStatus.State.values()).filter(entry.states()::contains)
                        .map(Enum::name).collect(Collectors.joining(", "));
                out.printf("%-15s %-24s %s%n", entry.id().value(), entry.name(), flags);
            }
            long applied = status.entries().stream().filter(entry -> entry.states().contains(MigrationStatus.State.APPLIED)).count();
            long pending = status.entries().stream().filter(entry -> entry.states().contains(MigrationStatus.State.PENDING)).count();
            out.println("Applied: " + applied + "; pending: " + pending + ". No database or local files written.");
            out.println("Drift: DRIFT_NOT_CHECKED (physical database schema was not compared).");
            if (status.hasProblems()) { err.println("TUPREL-MIGRATE-STATUS-INTEGRITY: Inspect divergent files and history; no repair was attempted."); }
            return status.hasProblems() ? 1 : 0;
        } catch (MigrationHistoryException | MigrationValidationException exception) {
            err.println(exception.getMessage());
        } catch (IOException | SecurityException exception) {
            err.println("TUPREL-MIGRATE-STATUS-READ: Inspect the local v1 catalog and project permissions.");
        }
        err.println("DRIFT_NOT_CHECKED; no status snapshot confirmed.");
        return 2;
    }

    static void help(PrintWriter out) {
        out.println("Usage: tuprel migrate status --target-schema <name>");
        out.println("Read tuprel/migrations and the explicit schema's existing PostgreSQL history in REPEATABLE READ, READ ONLY.");
        out.println("Required environment: TUPREL_DATABASE_URL, TUPREL_DATABASE_USER, TUPREL_DATABASE_PASSWORD. Dotenv is never loaded.");
        out.println("URL: jdbc:postgresql://host:port/database; only sslmode=verify-full (or disable on loopback) is accepted.");
        out.println("Remote TLS defaults to verify-full. Timeouts: connect/login 5s, query 10s, socket 30s.");
        out.println("Requires an existing target schema and USAGE/SELECT permissions; never creates history or applies SQL.");
        out.println("DRIFT_NOT_CHECKED: file/history integrity does not prove physical schema equivalence.");
        out.println("Exit codes: 0 normal/pending/uninitialised; 1 integrity/order divergence; 2 usage, configuration, I/O or database failure.");
        out.println("--help, -h: help without file/database/environment access. No prompts or ANSI colours.");
    }

    @FunctionalInterface interface ReaderFactory {
        MigrationHistoryReader create(Map<String, String> environment) throws MigrationHistoryException;
    }
}
