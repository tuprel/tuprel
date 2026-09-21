package dev.tuprel.cli;

import dev.tuprel.schema.DiagnosticRenderer;
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
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/** Initial command-line adapter for the Phase 1 schema operations. */
public final class TuprelCli {
    private static final Path DEFAULT_SCHEMA = Path.of("tuprel", "schema.tuprel");

    private TuprelCli() {}

    /** Runs the CLI and returns the stable RFC-001 exit code. */
    public static int run(String[] arguments, PrintWriter out, PrintWriter err) {
        if (arguments == null || out == null || err == null || arguments.length == 0) {
            printUsage(err);
            return 2;
        }
        String command = arguments[0];
        return switch (command) {
            case "validate" -> validate(arguments, out, err);
            case "format" -> format(arguments, out, err);
            default -> {
                printUsage(err);
                yield 2;
            }
        };
    }

    /** Process entry point used by the Gradle application distribution. */
    public static void main(String[] arguments) {
        int exitCode =
                run(
                        arguments,
                        new PrintWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8), true),
                        new PrintWriter(new OutputStreamWriter(System.err, StandardCharsets.UTF_8), true));
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    private static int validate(String[] arguments, PrintWriter out, PrintWriter err) {
        if (arguments.length > 2) {
            printUsage(err);
            return 2;
        }
        Path path = arguments.length == 2 ? Path.of(arguments[1]) : DEFAULT_SCHEMA;
        String text = read(path, err);
        if (text == null) {
            return 2;
        }
        SchemaCompilation compilation = new SchemaCompiler().compile(SourceText.of(path.toString(), text));
        printDiagnostics(compilation.diagnostics(), err);
        if (!compilation.isValid()) {
            return 1;
        }
        out.println("Schema is valid: " + path);
        return 0;
    }

    private static int format(String[] arguments, PrintWriter out, PrintWriter err) {
        boolean checkOnly = false;
        Path path = null;
        for (String argument : Arrays.copyOfRange(arguments, 1, arguments.length)) {
            if (argument.equals("--check") && !checkOnly) {
                checkOnly = true;
            } else if (path == null) {
                path = Path.of(argument);
            } else {
                printUsage(err);
                return 2;
            }
        }

        if (path == null) {
            path = DEFAULT_SCHEMA;
        }

        String text = read(path, err);
        if (text == null) {
            return 2;
        }
        SchemaFormatResult result =
                new SchemaFormatter().format(SourceText.of(path.toString(), text));
        printDiagnostics(result.diagnostics(), err);
        if (!result.isSuccess()) {
            return 1;
        }
        String formatted = result.formattedText().orElseThrow();
        if (checkOnly) {
            if (!text.equals(formatted)) {
                err.println("Schema is not formatted: " + path);
                return 1;
            }
            out.println("Schema is formatted: " + path);
            return 0;
        }
        if (!text.equals(formatted)) {
            try {
                Files.writeString(path, formatted, StandardCharsets.UTF_8);
            } catch (IOException exception) {
                err.println("Could not write schema '" + path + "': " + exception.getMessage());
                return 2;
            }
        }
        out.println("Schema formatted: " + path);
        return 0;
    }

    private static String read(Path path, PrintWriter err) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            err.println("Could not read schema '" + path + "': " + exception.getMessage());
            return null;
        }
    }

    private static void printDiagnostics(List<SchemaDiagnostic> diagnostics, PrintWriter err) {
        DiagnosticRenderer renderer = new DiagnosticRenderer();
        for (SchemaDiagnostic diagnostic : diagnostics) {
            err.println(renderer.render(diagnostic));
        }
    }

    private static void printUsage(PrintWriter err) {
        err.println("Usage: tuprel validate [schema.tuprel]");
        err.println("       tuprel format [--check] [schema.tuprel]");
    }
}
