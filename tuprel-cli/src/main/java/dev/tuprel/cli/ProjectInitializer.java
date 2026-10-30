package dev.tuprel.cli;

import dev.tuprel.migrate.MigrationDirectory;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Fixed, exclusive scaffolding in an existing developer-controlled project. */
final class ProjectInitializer {
    private static final String SCHEMA = """
            generator java {
                package = "dev.example.tuprel"
            }

            model User {
                id UUID @id
                email String @unique
            }
            """;

    private ProjectInitializer() {}

    static void initialize(Path projectRoot, boolean dryRun, PrintWriter out) throws IOException {
        Path project = projectRoot.toRealPath();
        if (!Files.isDirectory(project)) { throw new IOException("Project root is not a directory"); }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(project)) {
            for (Path entry : entries) {
                if (entry.getFileName().toString().equalsIgnoreCase("tuprel")) {
                    throw new IOException("An existing Tuprel path must be inspected first");
                }
            }
        }
        if (dryRun) {
            out.println("Would create tuprel/schema.tuprel and tuprel/migrations/.tuprel-migrations.");
            return;
        }
        Path directory = Files.createDirectory(project.resolve("tuprel"));
        verifyDirectory(directory);
        writeNew(directory.resolve("schema.tuprel"), SCHEMA);
        verifyDirectory(directory);
        Path migrations = Files.createDirectory(directory.resolve("migrations"));
        verifyDirectory(migrations);
        writeNew(migrations.resolve(MigrationDirectory.FORMAT_FILE), MigrationDirectory.FORMAT_HEADER);
        out.println("Created tuprel/schema.tuprel and an empty format-v1 migration directory.");
        out.println("Next: tuprel validate; tuprel generate. Set the generator package to your Java package.");
        out.println("Add the generated source directory and required Tuprel libraries to your build manually.");
    }

    private static void writeNew(Path file, String content) throws IOException {
        Files.writeString(file, content, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
    }

    private static void verifyDirectory(Path directory) throws IOException {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                || !directory.toRealPath().equals(directory)) {
            throw new IOException("Initialisation path changed; inspect partial files before retrying");
        }
    }
}
