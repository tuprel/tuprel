package dev.tuprel.cli;

import dev.tuprel.migrate.MigrationCreationException;
import dev.tuprel.migrate.MigrationCreator;
import dev.tuprel.migrate.MigrationFile;
import dev.tuprel.migrate.MigrationValidationException;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;

/** Argument handling only; catalog and creation rules belong to tuprel-migrate. */
final class MigrationCommands {
    private final MigrationCreator creator;

    MigrationCommands() { this(new MigrationCreator()); }

    MigrationCommands(MigrationCreator creator) { this.creator = creator; }

    int run(String[] arguments, PrintWriter out, PrintWriter err, Path projectRoot) {
        if (arguments.length >= 2 && arguments[1].equals("status")) {
            return new MigrationStatusCommand().run(arguments, out, err, projectRoot);
        }
        if (arguments.length >= 2 && arguments[1].equals("deploy")) {
            return new MigrationDeployCommand().run(arguments, out, err, projectRoot);
        }
        if (arguments.length == 2 && isHelp(arguments[1])) {
            help(out);
            return 0;
        }
        if (arguments.length == 3 && arguments[1].equals("create") && isHelp(arguments[2])) {
            createHelp(out);
            return 0;
        }
        if (arguments.length < 4 || !arguments[1].equals("create")) { return usage(err); }
        String name = null;
        boolean dryRun = false;
        for (int index = 2; index < arguments.length; index++) {
            if (arguments[index].equals("--name") && name == null && index + 1 < arguments.length
                    && !arguments[index + 1].startsWith("-")) {
                name = arguments[++index];
            } else if (arguments[index].equals("--dry-run") && !dryRun) {
                dryRun = true;
            } else {
                return usage(err);
            }
        }
        if (name == null) { return usage(err); }
        try {
            Path root = projectRoot.toRealPath().resolve("tuprel").resolve("migrations");
            MigrationFile file = dryRun ? creator.preview(root, name) : creator.create(root, name);
            out.println((dryRun ? "Would create draft: " : "Created draft: ")
                    + "tuprel/migrations/" + file.directoryName() + "/migration.sql");
            out.println(dryRun ? "No files written; this UTC ID is not reserved."
                    : "Edit and review migration.sql manually. No SQL was applied or approved.");
            return 0;
        } catch (MigrationCreationException | MigrationValidationException exception) {
            err.println(exception.getMessage());
            if (exception instanceof MigrationValidationException) {
                err.println("Inspect tuprel/migrations and its v1 marker. For a new project, run tuprel init first.");
            }
            return 2;
        } catch (IOException | SecurityException exception) {
            err.println("TUPREL-MIGRATE-CREATE-IO_ERROR: Check project permissions and inspect the catalog before retrying.");
            err.println("For a new project, run tuprel init first; an existing v1 catalog is required.");
            return 2;
        }
    }

    static void help(PrintWriter out) {
        out.println("Usage: tuprel migrate <command> [options]");
        out.println("Commands: create (offline drafts), status (read-only history), deploy (reviewed transactional migrations)");
        out.println("Run tuprel migrate <command> --help. Help never connects to a database.");
    }

    private static void createHelp(PrintWriter out) {
        out.println("Usage: tuprel migrate create --name <name> [--dry-run]");
        out.println("Create tuprel/migrations/<UTC-ID>_<name>/migration.sql in an existing format-v1 catalog.");
        out.println("Name: 1-64 lowercase ASCII characters, starting with a letter; words separated by _ or -.");
        out.println("Names are not silently normalized. IDs use UTC seconds; collisions are refused.");
        out.println("--dry-run validates and previews without writing files or reserving an ID.");
        out.println("Creation retains an empty tuprel/.migrations.create.lock for cooperating creators; do not delete it while active.");
        out.println("The draft contains comments only; edit/review it manually. No SQL is applied or approved.");
        out.println("--help, -h: show this help. Exit codes: 0 success; 2 usage, I/O or safety refusal.");
    }

    private static boolean isHelp(String value) { return value.equals("--help") || value.equals("-h"); }

    private static int usage(PrintWriter err) {
        err.println("TUPREL-CLI-USAGE: Unsupported migration command or options. Run tuprel migrate --help.");
        return 2;
    }
}
