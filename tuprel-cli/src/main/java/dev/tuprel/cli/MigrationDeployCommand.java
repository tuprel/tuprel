package dev.tuprel.cli;

import dev.tuprel.migrate.AppliedMigration;
import dev.tuprel.migrate.MigrationHistoryException;
import dev.tuprel.migrate.postgresql.MigrationDatabaseSettings;
import dev.tuprel.migrate.postgresql.MigrationExecutionResult;
import dev.tuprel.migrate.postgresql.MigrationInspection;
import dev.tuprel.migrate.postgresql.MigrationInspectionException;
import dev.tuprel.migrate.postgresql.MigrationPlan;
import dev.tuprel.migrate.postgresql.MigrationPlanDigest;
import dev.tuprel.migrate.postgresql.PostgresqlMigrationRunner;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** Argument handling and presentation only; approval, SQL and lifecycle belong to the adapter. */
final class MigrationDeployCommand {
    private final Supplier<Map<String, String>> environment;
    MigrationDeployCommand() { this(System::getenv); }
    MigrationDeployCommand(Supplier<Map<String, String>> environment) { this.environment = environment; }

    int run(String[] arguments, PrintWriter out, PrintWriter err, Path project) {
        if (arguments.length == 3 && (arguments[2].equals("--help") || arguments[2].equals("-h"))) {
            help(out); return 0;
        }
        String schema = null;
        String digest = null;
        boolean dryRun = false;
        for (int index = 2; index < arguments.length; index++) {
            if (arguments[index].equals("--target-schema") && schema == null && index + 1 < arguments.length
                    && !arguments[index + 1].startsWith("-")) {
                schema = arguments[++index];
            } else if (arguments[index].equals("--approve-digest") && digest == null && index + 1 < arguments.length
                    && !arguments[index + 1].startsWith("-")) {
                digest = arguments[++index];
            } else if (arguments[index].equals("--dry-run") && !dryRun) {
                dryRun = true;
            } else { return usage(err); }
        }
        if (schema == null || (dryRun == (digest != null))) { return usage(err); }
        MigrationPlanDigest approval = null;
        if (!dryRun) {
            try { approval = new MigrationPlanDigest(digest); }
            catch (IllegalArgumentException exception) { return usage(err); }
        }
        try {
            MigrationDatabaseSettings settings = MigrationDatabaseSettings.fromEnvironment(environment.get());
            PostgresqlMigrationRunner runner = new PostgresqlMigrationRunner(settings.dataSource(), settings);
            Path directory = project.toRealPath().resolve("tuprel/migrations");
            if (dryRun) { return preview(runner.inspect(schema, directory), out, err); }
            return result(runner.deploy(schema, directory, approval), out, err);
        } catch (MigrationHistoryException exception) {
            diagnostic(MigrationExecutionResult.Failure.CONFIGURATION, err);
        } catch (MigrationInspectionException exception) {
            diagnostic(exception.reason(), err);
            err.println("No plan snapshot confirmed. No migration SQL or local files written by this dry-run.");
        } catch (IOException | SecurityException exception) {
            diagnostic(MigrationExecutionResult.Failure.FILES, err);
        }
        return 2;
    }

    static int preview(MigrationInspection inspection, PrintWriter out, PrintWriter err) {
        MigrationPlan plan = inspection.plan();
        out.println("Plan: " + plan.state());
        out.println("History: " + (inspection.history().initialised() ? "INITIALISED" : "UNINITIALISED")
                + "; applied: " + inspection.history().applied().size() + "; pending: " + plan.scripts().size());
        String risks = Arrays.stream(MigrationPlan.Risk.values()).filter(plan.risks()::contains).map(Enum::name).collect(Collectors.joining(", "));
        out.println("Risks: " + risks);
        out.println("Drift: DRIFT_NOT_CHECKED (physical schema equivalence was not verified).");
        if (plan.state() == MigrationPlan.State.BLOCKED) {
            for (MigrationPlan.Script script : plan.scripts()) {
                script.diagnostic().ifPresent(value -> out.println("Migration " + script.id().value() + ": " + value.kind()
                        + " at line " + value.line() + ", column " + value.column() + "."));
            }
            diagnostic(MigrationExecutionResult.Failure.BLOCKED, err);
            out.println("No SQL preview or approval command for a blocked plan. No migration SQL or local files written.");
            return 2;
        }
        if (plan.state() == MigrationPlan.State.REVIEW_REQUIRED) {
            out.println("SQL preview (canonical; review every statement and risk):");
            out.print(plan.preview());
            out.println("Review the DDL lock costs before applying.");
            if (plan.risks().contains(MigrationPlan.Risk.INDEX_WRITE_BLOCKING)) {
                out.println("Ordinary index builds block writes and have unmeasured resource costs.");
            }
        } else { out.println("No pending migrations. This does not prove absence of physical drift."); }
        out.println("Approval digest: " + plan.digest().value());
        out.println("No migration SQL or local files written. Dry-run does not approve execution or reserve the plan.");
        out.println("After review, run migrate deploy with --target-schema <name> --approve-digest <digest>.");
        out.println("State may change; deployment independently revalidates under the session lock.");
        return 0;
    }

    static int result(MigrationExecutionResult result, PrintWriter out, PrintWriter err) {
        out.println("Deployment: " + result.outcome());
        out.println("Confirmed this invocation: " + result.confirmed().size());
        for (AppliedMigration applied : result.confirmed()) { out.println("APPLIED " + applied.id().value() + " " + applied.name()); }
        out.println("Drift: DRIFT_NOT_CHECKED (physical schema equivalence was not verified).");
        if (result.outcome() == MigrationExecutionResult.Outcome.APPLIED || result.outcome() == MigrationExecutionResult.Outcome.NO_CHANGES) { return 0; }
        diagnostic(result.failure(), err);
        if (result.outcome() == MigrationExecutionResult.Outcome.OUTCOME_UNKNOWN) {
            err.println("OUTCOME_UNKNOWN: Stop. Do not assume rollback or repeat the confirmed prefix.");
            err.println("Reconcile current history and artifacts with migrate status and migrate deploy --dry-run, using the explicit --target-schema.");
            err.println("Dry-run inspects on a fresh session under lock. Review the new plan before another explicit invocation; no automatic retry occurred.");
        } else if (!result.confirmed().isEmpty()) { err.println("Earlier confirmed migrations remain applied. No global rollback or automatic retry occurred."); }
        return 2;
    }

    private static void diagnostic(MigrationExecutionResult.Failure failure, PrintWriter err) {
        err.println("TUPREL-MIGRATE-DEPLOY-" + failure + ": " + switch (failure) {
            case CONFIGURATION -> "Set TUPREL_DATABASE_URL, TUPREL_DATABASE_USER, TUPREL_DATABASE_PASSWORD and an existing explicit target schema. Check supported URL/TLS settings.";
            case SESSION -> "Provide idle dedicated connections with finite timeouts and physical discard; transaction-pooling is unsupported.";
            case LOCK_BUSY -> "Target migration lock is busy. Inspect ongoing database work; no forced unlock or retry was attempted.";
            case INTERRUPTED -> "Invocation interrupted. Inspect the confirmed prefix and current history before another invocation.";
            case FILES -> "Inspect tuprel/migrations, its format-v1 marker, paths and permissions. Use tuprel init only for a new project.";
            case HISTORY -> "Inspect history structure, permissions and integrity against the reviewed files; no repair was attempted.";
            case APPROVAL -> "The observed plan differs from the approved digest. Run --dry-run and review the current SQL, risks and digest.";
            case BLOCKED -> "The complete plan is blocked. Inspect SQL, catalog integrity and supported limits; approval cannot bypass unknown or destructive work.";
            case CATALOG -> "Target metadata is outside the admitted table/index/history subset. Inspect the migration guide; no repair was attempted.";
            case PERMISSION -> "Check target schema USAGE/CREATE and known history SELECT/INSERT permissions. No privilege changes were attempted.";
            case TIMEOUT -> "A query or relation lock timed out. Inspect active work and confirmed history before another reviewed invocation.";
            case STATEMENT -> "A migration statement failed and rollback was confirmed. Inspect the reviewed SQL and target objects; no SQL or driver text is logged.";
            case CONNECTION -> "The connection could not establish an outcome. Check connectivity and inspect current history; no automatic retry occurred.";
            case COMMIT -> "Commit acknowledgement could not establish an outcome. Inspect newly observed history before further work.";
            case ROLLBACK -> "Rollback could not be confirmed. Inspect newly observed history before further work.";
            case CLEANUP -> "Session cleanup failed and physical discard was required. Confirmed commits remain valid; inspect connections and current history.";
            case NONE -> "Unexpected execution result. Inspect current history before further work.";
        });
    }

    static void help(PrintWriter out) {
        out.println("Usage: tuprel migrate deploy --target-schema <name> (--dry-run | --approve-digest <sha256>)");
        out.println("Inspect or apply existing reviewed format-v1 files from tuprel/migrations. Never generates SQL from schema.tuprel.");
        out.println("--dry-run: fresh read-only inspection under a session advisory lock; no bootstrap, migration SQL or file writes.");
        out.println("--approve-digest: exact lowercase SHA-256 reviewed with the full preview/risks; independently revalidated under lock.");
        out.println("The flags are exclusive. No --yes, prompts, stdin approval, reset or unknown/destructive SQL bypass.");
        out.println("Required environment: TUPREL_DATABASE_URL, TUPREL_DATABASE_USER, TUPREL_DATABASE_PASSWORD. Dotenv is never loaded.");
        out.println("Use an explicit existing target schema and supported JDBC URL/TLS configuration; remote TLS defaults to verify-full.");
        out.println("Session lock polling 30s; DDL lock timeout 10s; statement timeout 20s; driver socket timeout 30s. No automatic retries.");
        out.println("Each migration commits DDL/history atomically. Later failures preserve earlier confirmed migrations; OUTCOME_UNKNOWN requires fresh reconciliation.");
        out.println("DRIFT_NOT_CHECKED: this does not prove physical schema equivalence. No prompts or ANSI colours.");
        out.println("Exit codes: 0 success/valid dry-run; 2 usage, I/O, blocked/stale approval, database failure or uncertain outcome.");
        out.println("--help, -h: show this help without environment, filesystem or database access.");
        out.println("Documentation: https://github.com/tuprel/tuprel/blob/main/docs/guides/migrations.md");
    }

    private static int usage(PrintWriter err) {
        err.println("TUPREL-CLI-USAGE: Run tuprel migrate deploy --target-schema <name> with exactly one of --dry-run or --approve-digest <sha256>. Use --help.");
        return 2;
    }
}
