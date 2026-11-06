package dev.tuprel.cli;

import dev.tuprel.migrate.MigrationFile;
import dev.tuprel.migrate.MigrationHistoryException;
import dev.tuprel.migrate.postgresql.MigrationDatabaseSettings;
import dev.tuprel.migrate.postgresql.MigrationDevPlan;
import dev.tuprel.migrate.postgresql.PostgresqlDriftInspector.ReferenceControl;
import dev.tuprel.migrate.postgresql.PostgresqlMigrationDevPlanner;
import dev.tuprel.schema.SchemaCompilation;
import dev.tuprel.schema.SchemaCompiler;
import dev.tuprel.schema.SchemaDiagnostic;
import dev.tuprel.schema.SourceText;
import dev.tuprel.sql.SqlIdentifier;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Supplier;

/** CLI argument, schema input and presentation handling for read-only development previews. */
final class MigrationDevCommand {
    private final Supplier<Map<String, String>> environment;

    MigrationDevCommand() { this(System::getenv); }
    MigrationDevCommand(Supplier<Map<String, String>> environment) { this.environment = environment; }

    int run(String[] args, PrintWriter out, PrintWriter err, Path projectRoot) {
        if (args.length == 3 && (args[2].equals("--help") || args[2].equals("-h"))) {
            help(out);
            return 0;
        }
        String target = null;
        String name = null;
        String schemaFile = null;
        boolean confirmed = false;
        for (int i = 2; i < args.length; i++) {
            if (args[i].equals("--confirm-managed-history") && !confirmed) {
                confirmed = true;
                continue;
            }
            if (i + 1 >= args.length || args[i + 1].startsWith("-")) { return usage(err); }
            if (args[i].equals("--target-schema") && target == null) { target = args[++i]; }
            else if (args[i].equals("--name") && name == null) { name = args[++i]; }
            else if (args[i].equals("--schema") && schemaFile == null) { schemaFile = args[++i]; }
            else { return usage(err); }
        }
        if (!confirmed || target == null || name == null) { return usage(err); }
        if (schemaFile == null) { schemaFile = "tuprel/schema.tuprel"; }
        try {
            SqlIdentifier schema = new SqlIdentifier(target);
            MigrationFile.validateName(name);
            if (schemaFile.chars().anyMatch(character -> character < 32 || character == 127)) { return usage(err); }
            Path root = projectRoot.toRealPath();
            String input = MigrationSchemaInput.read(root, Path.of(schemaFile));
            SchemaCompilation compiled = new SchemaCompiler().compile(SourceText.of("schema.tuprel", input));
            if (!compiled.isValid()) {
                for (SchemaDiagnostic diagnostic : compiled.diagnostics()) {
                    err.println("TUPREL-MIGRATE-DEV-SCHEMA: " + diagnostic.code() + " at "
                            + diagnostic.span().start().line() + ":" + diagnostic.span().start().column());
                }
                return 1;
            }
            MigrationDatabaseSettings settings = MigrationDatabaseSettings.fromEnvironment(environment.get());
            MigrationDevPlan plan = new PostgresqlMigrationDevPlanner(settings).preview(schema, root.resolve("tuprel/migrations"),
                    compiled.validatedSchema().orElseThrow(), name, ReferenceControl.MANAGED_HISTORY);
            return present(plan, out, err);
        } catch (MigrationHistoryException failure) {
            err.println("TUPREL-MIGRATE-DEV-CONFIGURATION: Check the database environment and TLS configuration.");
        } catch (IOException | IllegalArgumentException | SecurityException failure) {
            err.println("TUPREL-MIGRATE-DEV-INPUT: Check the name, target and bounded UTF-8 schema path.");
        }
        return 2;
    }

    private static int present(MigrationDevPlan plan, PrintWriter out, PrintWriter err) {
        out.println("Plan: " + plan.state());
        plan.observation().ifPresent(report -> out.println("Reference: " + report));
        if (plan.state() == MigrationDevPlan.State.BLOCKED) {
            err.println("TUPREL-MIGRATE-DEV-BLOCKED: " + plan.diagnostic().orElseThrow()
                    + ". Resolve the refusal before retrying. No SQL preview.");
            return 2;
        }
        out.println("Sources: verified applied prefix in tuprel/migrations, PostgreSQL catalog and desired Tuprel schema.");
        if (plan.state() == MigrationDevPlan.State.REVIEW_REQUIRED) {
            out.println("Risks: " + plan.risks().stream().sorted().toList());
            out.println("SQL proposal for review. No artifact or execution approval:");
            out.print(plan.sql().orElseThrow());
        }
        out.flush();
        return 0;
    }

    private static void help(PrintWriter out) {
        out.println("Usage: tuprel migrate dev --name <name> --target-schema <name> --confirm-managed-history [--schema <path>]");
        out.println("Preview supported additions against verified applied history; defaults to tuprel/schema.tuprel.");
        out.println("Confirm managed history only when its provenance is trusted and external DDL is excluded for this invocation.");
        out.println("Uses the migration database environment and a read-only transaction. Every local artifact must already be applied.");
        out.println("Always preview-only: no file creation, DDL or execution approval. Help is offline.");
        out.println("Exit codes: 0 preview/no changes; 1 invalid schema; 2 usage, I/O or safety refusal.");
    }

    private static int usage(PrintWriter err) {
        err.println("TUPREL-CLI-USAGE: Run tuprel migrate dev --help.");
        return 2;
    }
}
