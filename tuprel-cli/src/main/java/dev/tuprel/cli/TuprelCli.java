package dev.tuprel.cli;

import dev.tuprel.codegen.GeneratedSourceWriter;
import dev.tuprel.codegen.GenerationDiagnostic;
import dev.tuprel.codegen.JavaCodeGenerator;
import dev.tuprel.codegen.JavaGenerationResult;
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

/** Command-line adapter for schema validation, formatting and Java generation. */
public final class TuprelCli {
    private static final Path DEFAULT_SCHEMA = Path.of("tuprel", "schema.tuprel");
    private static final Path GENERATED_SOURCES = Path.of("build", "generated", "sources", "tuprel", "main");

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
            case "generate" -> generate(arguments, out, err, Path.of(""));
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

    static int generate(String[] arguments, PrintWriter out, PrintWriter err, Path projectRoot) {
        boolean checkOnly = false;
        Path path = null;
        for (String argument : Arrays.copyOfRange(arguments, 1, arguments.length)) {
            if (argument.equals("--check") && !checkOnly) {
                checkOnly = true;
            } else if (!argument.startsWith("--") && path == null) {
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
        SchemaCompilation compilation = new SchemaCompiler().compile(SourceText.of(path.toString(), text));
        printDiagnostics(compilation.diagnostics(), err);
        if (!compilation.isValid()) {
            return 1;
        }
        JavaGenerationResult result = new JavaCodeGenerator().generate(compilation.validatedSchema().orElseThrow());
        if (!result.isSuccess()) {
            for (GenerationDiagnostic diagnostic : result.diagnostics()) {
                err.println(diagnostic.code() + ": " + diagnostic.message()
                        + " (" + diagnostic.span().start().line() + ":"
                        + diagnostic.span().start().column() + ")");
            }
            return 1;
        }
        try {
            GeneratedSourceWriter writer = new GeneratedSourceWriter();
            Path outputRoot = projectRoot.resolve(GENERATED_SOURCES);
            if (checkOnly) {
                if (!writer.isCurrent(outputRoot, result.sources().orElseThrow())) {
                    err.println("Generated Java sources are out of date: " + GENERATED_SOURCES);
                    return 1;
                }
                out.println("Generated Java sources are current: " + GENERATED_SOURCES);
            } else {
                writer.write(outputRoot, result.sources().orElseThrow());
                out.println("Generated Java sources: " + GENERATED_SOURCES);
            }
            return 0;
        } catch (IOException exception) {
            err.println("Could not verify or write generated Java sources: " + exception.getMessage());
            return 2;
        }
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
        err.println("       tuprel generate [--check] [schema.tuprel]");
    }
}
