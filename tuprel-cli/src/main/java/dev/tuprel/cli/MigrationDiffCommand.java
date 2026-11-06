package dev.tuprel.cli;

import dev.tuprel.migrate.MigrationDirectory;
import dev.tuprel.migrate.MigrationFile;
import dev.tuprel.migrate.MigrationValidationException;
import dev.tuprel.migrate.postgresql.ddl.DdlParseResult;
import dev.tuprel.migrate.postgresql.ddl.PostgresqlDdlParser;
import dev.tuprel.migrate.postgresql.diff.PostgresqlSchemaProjector;
import dev.tuprel.migrate.postgresql.diff.SchemaDiff;
import dev.tuprel.migrate.postgresql.diff.SchemaProjection;
import dev.tuprel.migrate.postgresql.diff.SchemaSnapshot;
import dev.tuprel.schema.SchemaCompilation;
import dev.tuprel.schema.SchemaCompiler;
import dev.tuprel.schema.SchemaDiagnostic;
import dev.tuprel.schema.SourceText;
import dev.tuprel.sql.SqlIdentifier;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/** Offline orchestration only; schema interpretation and comparison stay in their modules. */
final class MigrationDiffCommand {
    private static final int MAX_SCHEMA_BYTES = 1024 * 1024;

    int run(String[] arguments, PrintWriter out, PrintWriter err, Path projectRoot) {
        if (arguments.length == 3 && (arguments[2].equals("--help") || arguments[2].equals("-h"))) { help(out); return 0; }
        String target = null;
        String schemaFile = null;
        for (int index = 2; index < arguments.length; index++) {
            if (index + 1 >= arguments.length || arguments[index + 1].startsWith("-")) { return usage(err); }
            if (arguments[index].equals("--target-schema") && target == null) { target = arguments[++index]; }
            else if (arguments[index].equals("--schema") && schemaFile == null) { schemaFile = arguments[++index]; }
            else { return usage(err); }
        }
        if (target == null) { return usage(err); }
        if (schemaFile == null) { schemaFile = "tuprel/schema.tuprel"; }
        try {
            SqlIdentifier schema = new SqlIdentifier(target);
            if (schemaFile.chars().anyMatch(character -> character < 32 || character == 127)) { return usage(err); }
            Path root = projectRoot.toRealPath();
            SourceText source = SourceText.of("schema.tuprel", readSchema(root, Path.of(schemaFile)));
            out.println("Source: all local migration files in tuprel/migrations (execution order).");
            out.println("Target: " + schemaFile);
            out.println("Offline comparison only: no database/history observed; physical drift not checked.");
            SchemaCompilation compiled = new SchemaCompiler().compile(source);
            if (!compiled.isValid()) {
                for (SchemaDiagnostic diagnostic : compiled.diagnostics()) {
                    err.println("TUPREL-DIFF-SCHEMA: " + diagnostic.code() + " at " + diagnostic.span().start().line() + ":" + diagnostic.span().start().column());
                }
                err.println("Correct the schema using tuprel validate; no source text is included here.");
                return 1;
            }
            SchemaProjection desired = new PostgresqlSchemaProjector().project(schema, compiled.validatedSchema().orElseThrow());
            if (desired.snapshot().isEmpty()) {
                for (SchemaProjection.Diagnostic diagnostic : desired.diagnostics()) {
                    err.println("TUPREL-DIFF-TARGET: " + diagnostic.kind() + " at " + diagnostic.start().line() + ":" + diagnostic.start().column());
                }
                err.println("Target is not completely representable by the approved DDL subset. No partial SQL preview.");
                return 2;
            }
            List<DdlParseResult> scripts = new ArrayList<>();
            PostgresqlDdlParser parser = new PostgresqlDdlParser();
            for (MigrationFile file : MigrationDirectory.discover(root.resolve("tuprel/migrations"))) {
                DdlParseResult parsed = parser.parse(file);
                if (!parsed.isSupported()) {
                    DdlParseResult.Diagnostic diagnostic = parsed.diagnostic().orElseThrow();
                    err.println("TUPREL-DIFF-SOURCE: " + file.id().value() + " " + diagnostic.kind()
                            + " at " + diagnostic.line() + ":" + diagnostic.column());
                    err.println("Migration SQL is unknown, destructive or incomplete. Review that artifact; no partial SQL preview.");
                    return 2;
                }
                scripts.add(parsed);
            }
            SchemaSnapshot before;
            try { before = SchemaSnapshot.fromParsed(schema, scripts); }
            catch (IllegalArgumentException failure) {
                err.println("TUPREL-DIFF-SOURCE: Replay is incomplete, conflicting, out of scope or over budget. Inspect migration order and prerequisites.");
                return 2;
            }
            SchemaDiff diff = SchemaDiff.compare(before, desired.snapshot().orElseThrow());
            out.println("Comparison: " + diff.state());
            for (SchemaDiff.Change change : diff.changes()) {
                out.println(change.kind() + " " + change.table().name() + change.member().map(member -> "." + member.name()).orElse(""));
            }
            out.println("Risks: " + diff.risks());
            if (diff.state() == SchemaDiff.State.BLOCKED) {
                err.println("TUPREL-DIFF-BLOCKED: Destructive/unsupported changes or limits block the complete SQL preview. Manual review required.");
                return 2;
            }
            if (diff.state() == SchemaDiff.State.NO_CHANGES) { return 0; }
            out.println("SQL proposal only; not a migration artifact, approval digest or execution permission:");
            out.print(diff.preview());
            out.flush();
            return 1;
        } catch (MigrationValidationException failure) {
            err.println(failure.getMessage());
            err.println("Inspect the local v1 catalog; use tuprel init only for a new project.");
            return 2;
        } catch (IOException | IllegalArgumentException | SecurityException failure) {
            err.println("TUPREL-DIFF-INPUT: Check --target-schema, --schema, UTF-8, permissions and the 1 MiB schema limit. Links, aliases and traversal are refused.");
            return 2;
        }
    }

    private static String readSchema(Path root, Path requested) throws IOException {
        for (Path component : requested) { if (component.toString().equals("..")) { throw new IOException("Traversal"); } }
        Path path = root.resolve(requested).toAbsolutePath().normalize();
        Path current = path.getRoot();
        for (Path component : path) {
            current = current.resolve(component);
            BasicFileAttributes attributes = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink() || attributes.isOther()) { throw new IOException("Linked input"); }
        }
        BasicFileAttributes before = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!before.isRegularFile() || before.size() > MAX_SCHEMA_BYTES || !path.toRealPath().equals(path)) { throw new IOException("Invalid input"); }
        byte[] bytes;
        try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) { bytes = input.readNBytes(MAX_SCHEMA_BYTES + 1); }
        BasicFileAttributes after = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (bytes.length > MAX_SCHEMA_BYTES || bytes.length != before.size() || !after.isRegularFile() || !java.util.Objects.equals(before.fileKey(), after.fileKey())
                || before.size() != after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime())) { throw new IOException("Input changed"); }
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
    }

    private static void help(PrintWriter out) {
        out.println("Usage: tuprel migrate diff --target-schema <name> [--schema <path>]");
        out.println("Compare all local tuprel/migrations with schema.tuprel (default tuprel/schema.tuprel), offline.");
        out.println("No database, history, drift check, file writes, SQL execution or approval digest.");
        out.println("Unknown/destructive differences block the whole preview. No rename inference.");
        out.println("--schema: UTF-8, at most 1 MiB; no links/aliases/traversal. Exclude concurrent filesystem writers.");
        out.println("No --dry-run is needed: this command is always read-only. --help, -h: no I/O.");
        out.println("Exit codes: 0 logical equality; 1 previewable differences or invalid schema; 2 usage, I/O or blocked comparison.");
    }

    private static int usage(PrintWriter err) { err.println("TUPREL-CLI-USAGE: Run tuprel migrate diff --help."); return 2; }
}
