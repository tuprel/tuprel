package dev.tuprel.cli;

import dev.tuprel.codegen.GeneratedSourceWriter;
import dev.tuprel.codegen.GenerationDiagnostic;
import dev.tuprel.codegen.JavaCodeGenerator;
import dev.tuprel.codegen.JavaGenerationResult;
import dev.tuprel.schema.SchemaCompilation;
import dev.tuprel.schema.SchemaCompiler;
import dev.tuprel.schema.SchemaDiagnostic;
import dev.tuprel.schema.SchemaFormatResult;
import dev.tuprel.schema.SchemaFormatter;
import dev.tuprel.schema.SourceText;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Objects;

/** Scriptable CLI adapter; schema/codegen/migration rules remain in their modules. */
public final class TuprelCli {
    private static final Path DEFAULT_SCHEMA = Path.of("tuprel", "schema.tuprel");
    private static final Path GENERATED_SOURCES = Path.of("build", "generated", "sources", "tuprel", "main");

    private TuprelCli() {}

    /**
     * Runs against the current working directory. Returns 0 for success, 1 for
     * invalid/outdated schema or output, and 2 for usage, I/O or safety refusal.
     * Writers and arguments must be non-null; no interactive input is consumed.
     * @param arguments command and options
     * @param out successful output
     * @param err safe diagnostics
     * @return stable process exit code
     */
    public static int run(String[] arguments, PrintWriter out, PrintWriter err) {
        return run(arguments, out, err, Path.of(""));
    }

    static int run(String[] arguments, PrintWriter out, PrintWriter err, Path projectRoot) {
        Objects.requireNonNull(arguments, "arguments");
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(err, "err");
        Objects.requireNonNull(projectRoot, "projectRoot");
        if (arguments.length == 0) { return usage(err); }
        if (arguments.length == 1 && isHelp(arguments[0])) {
            printUsage(out);
            return 0;
        }
        if (arguments.length == 1 && arguments[0].equals("--version")) {
            String version = TuprelCli.class.getPackage().getImplementationVersion();
            out.println("tuprel " + (version == null ? "development" : version));
            return 0;
        }
        String command = arguments[0];
        if (command.equals("migrate")) {
            return new MigrationCommands().run(arguments, out, err, projectRoot);
        }
        if (!List.of("init", "validate", "format", "generate").contains(command)) { return usage(err); }
        if (arguments.length == 2 && isHelp(arguments[1])) {
            printCommandHelp(command, out);
            return 0;
        }
        try {
            Path root = projectRoot.toRealPath();
            if (command.equals("init")) {
                if (arguments.length > 2 || (arguments.length == 2 && !arguments[1].equals("--dry-run"))) {
                    return usage(err);
                }
                try {
                    ProjectInitializer.initialize(root, arguments.length == 2, out);
                    return 0;
                } catch (IOException exception) {
                    err.println("TUPREL-CLI-INIT: Could not initialise. Check permissions and ensure tuprel/ does not exist.");
                    err.println("Inspect any partial tuprel/ files before retrying; no existing files are overwritten.");
                    return 2;
                }
            }
            Options options;
            try {
                options = parse(arguments, !command.equals("validate"));
            } catch (IllegalArgumentException exception) {
                return usage(err);
            }
            Path path = root.resolve(options.schema()).normalize();
            String text;
            try {
                text = Files.readString(path, StandardCharsets.UTF_8);
            } catch (IOException exception) {
                err.println("TUPREL-CLI-READ: Could not read schema. Check --schema, UTF-8 encoding and file permissions.");
                return 2;
            }
            SourceText source = SourceText.of("schema.tuprel", text);
            return switch (command) {
                case "validate" -> validate(source, out, err);
                case "format" -> format(source, path, options, out, err);
                case "generate" -> generateSources(source, root, options, out, err);
                default -> throw new IllegalStateException("Unreachable command");
            };
        } catch (IOException | InvalidPathException | SecurityException | DirectoryIteratorException exception) {
            err.println("TUPREL-CLI-PATH: Invalid or inaccessible path. Check --schema and project permissions.");
            return 2;
        }
    }

    /**
     * Process entry point used by the existing Gradle application distribution.
     * @param arguments command and options
     */
    public static void main(String[] arguments) {
        int exitCode = run(arguments,
                new PrintWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8), true),
                new PrintWriter(new OutputStreamWriter(System.err, StandardCharsets.UTF_8), true));
        if (exitCode != 0) { System.exit(exitCode); }
    }

    // Retains the package-private entry used by the original generation contract tests.
    static int generate(String[] arguments, PrintWriter out, PrintWriter err, Path projectRoot) {
        return run(arguments, out, err, projectRoot);
    }

    private static int validate(SourceText source, PrintWriter out, PrintWriter err) {
        SchemaCompilation compilation = new SchemaCompiler().compile(source);
        printDiagnostics(compilation.diagnostics(), err);
        if (!compilation.isValid()) { return 1; }
        out.println("Schema is valid.");
        return 0;
    }

    private static int format(SourceText source, Path path, Options options, PrintWriter out, PrintWriter err) {
        SchemaFormatResult result = new SchemaFormatter().format(source);
        printDiagnostics(result.diagnostics(), err);
        if (!result.isSuccess()) { return 1; }
        String formatted = result.formattedText().orElseThrow();
        boolean changed = !source.text().equals(formatted);
        if (options.check()) {
            if (changed) {
                err.println("Schema is not formatted. Run tuprel format with the same --schema option.");
                return 1;
            }
            out.println("Schema is formatted.");
            return 0;
        }
        if (options.dryRun()) {
            out.println(changed ? "Would format schema; no files written." : "Schema is already formatted; no files written.");
            return 0;
        }
        if (changed) {
            try {
                if (Files.isSymbolicLink(path)) { throw new IOException("Linked schema path"); }
                Path target = path.getParent().toRealPath().resolve(path.getFileName());
                Files.writeString(target, formatted, StandardCharsets.UTF_8,
                        StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS);
            } catch (IOException exception) {
                err.println("TUPREL-CLI-WRITE: Could not safely write schema. Check permissions and avoid symbolic links.");
                return 2;
            }
        }
        out.println("Schema formatted.");
        return 0;
    }

    private static int generateSources(SourceText source, Path root, Options options, PrintWriter out, PrintWriter err) {
        SchemaCompilation compilation = new SchemaCompiler().compile(source);
        printDiagnostics(compilation.diagnostics(), err);
        if (!compilation.isValid()) { return 1; }
        JavaGenerationResult result = new JavaCodeGenerator().generate(compilation.validatedSchema().orElseThrow());
        if (!result.isSuccess()) {
            for (GenerationDiagnostic diagnostic : result.diagnostics()) {
                err.println(diagnostic.code() + ": Java generation failed at " + diagnostic.span().start().line()
                        + ":" + diagnostic.span().start().column() + ". Check generator settings and supported schema features.");
            }
            return 1;
        }
        try {
            GeneratedSourceWriter writer = new GeneratedSourceWriter();
            Path output = root.resolve(GENERATED_SOURCES);
            if (options.check()) {
                if (!writer.isCurrent(output, result.sources().orElseThrow())) {
                    err.println("Generated Java sources are out of date. Run tuprel generate with the same --schema option.");
                    return 1;
                }
                out.println("Generated Java sources are current: " + GENERATED_SOURCES);
            } else if (options.dryRun()) {
                boolean changed = writer.wouldChange(output, result.sources().orElseThrow());
                out.println(changed ? "Would update generated Java sources; no files written."
                        : "Generated Java sources are current; no files written.");
            } else {
                writer.write(output, result.sources().orElseThrow());
                out.println("Generated Java sources: " + GENERATED_SOURCES);
            }
            return 0;
        } catch (IOException exception) {
            err.println("TUPREL-CLI-GENERATE: Could not safely verify or write generated Java sources.");
            err.println("Check permissions, symlinks and Tuprel ownership. Move edited/unowned files aside after review; retry.");
            return 2;
        }
    }

    private static Options parse(String[] arguments, boolean writable) {
        Path schema = null;
        boolean check = false;
        boolean dryRun = false;
        boolean literal = false;
        for (int index = 1; index < arguments.length; index++) {
            String argument = arguments[index];
            if (!literal && argument.equals("--")) {
                literal = true;
            } else if (!literal && argument.equals("--schema")) {
                if (schema != null || ++index >= arguments.length || arguments[index].startsWith("-")) {
                    throw new IllegalArgumentException("Invalid schema option");
                }
                schema = Path.of(arguments[index]);
            } else if (!literal && writable && argument.equals("--check") && !check) {
                check = true;
            } else if (!literal && writable && argument.equals("--dry-run") && !dryRun) {
                dryRun = true;
            } else if ((literal || !argument.startsWith("-")) && schema == null) {
                schema = Path.of(argument);
            } else {
                throw new IllegalArgumentException("Unsupported or duplicate option");
            }
        }
        if (check && dryRun) { throw new IllegalArgumentException("Check and dry-run are mutually exclusive"); }
        return new Options(schema == null ? DEFAULT_SCHEMA : schema, check, dryRun);
    }

    private static void printDiagnostics(List<SchemaDiagnostic> diagnostics, PrintWriter err) {
        for (SchemaDiagnostic diagnostic : diagnostics) {
            String hint = diagnostic.code().equals("TUPREL-SCHEMA-SEM-004")
                    ? "Use a declared model/enum or a supported scalar type."
                    : "Check schema syntax, declarations and attributes; correct diagnostics before retrying.";
            err.println(diagnostic.code() + ": Schema validation failed at " + diagnostic.span().start().line()
                    + ":" + diagnostic.span().start().column() + ". " + hint);
        }
    }

    private static boolean isHelp(String value) { return value.equals("--help") || value.equals("-h"); }

    private static int usage(PrintWriter err) {
        err.println("TUPREL-CLI-USAGE: Unsupported command or options. Run tuprel --help.");
        printUsage(err);
        return 2;
    }

    private static void printUsage(PrintWriter out) {
        out.println("Usage: tuprel <command> [options]");
        out.println("       tuprel --help | --version");
        out.println("Commands: init, validate, format, generate, migrate");
        out.println("Run tuprel <command> --help for options. Java 21 or newer is required.");
        out.println("Exit codes: 0 success; 1 invalid/outdated content; 2 usage, I/O or safety refusal.");
    }

    private static void printCommandHelp(String command, PrintWriter out) {
        if (command.equals("init")) {
            out.println("Usage: tuprel init [--dry-run]");
            out.println("Create tuprel/schema.tuprel and an empty versioned migration directory in this project.");
            out.println("Refuses any existing tuprel path. Does not modify builds, install dependencies or access a database.");
        } else {
            String flags = command.equals("validate") ? "" : " [--check | --dry-run]";
            out.println("Usage: tuprel " + command + flags + " [--schema <file> | schema.tuprel]");
            out.println("Default schema: tuprel/schema.tuprel. Positional paths remain supported; quote paths with spaces.");
            if (!command.equals("validate")) {
                out.println("--check returns 1 for differences; --dry-run previews changes without writing (0 when valid).");
            }
            if (command.equals("generate")) {
                out.println("Output: build/generated/sources/tuprel/main. Only unedited Tuprel-owned files are replaced.");
            }
        }
        out.println("--help, -h: show this help. No prompts, ANSI colours or environment/credential values are printed.");
    }

    private record Options(Path schema, boolean check, boolean dryRun) {}
}
