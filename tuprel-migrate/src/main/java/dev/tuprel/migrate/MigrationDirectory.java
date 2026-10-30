package dev.tuprel.migrate;

import static dev.tuprel.migrate.MigrationValidationException.Reason;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Read-only, bounded discovery of format-v1 migrations in a developer-controlled workspace. */
public final class MigrationDirectory {
    /** Required root marker; the marker also preserves an empty directory in Git. */
    public static final String FORMAT_FILE = ".tuprel-migrations";
    /** Exact ASCII/UTF-8 marker for format v1, including its final LF. */
    public static final String FORMAT_HEADER = "TUPREL-MIGRATIONS 1\n";
    /** Format-v1 maximum number of migrations. */
    public static final int MAX_MIGRATIONS = 1000;
    /** Format-v1 cumulative SQL budget (16 MiB). */
    public static final int MAX_TOTAL_SQL_BYTES = 16 * 1024 * 1024;

    private MigrationDirectory() {}

    /**
     * Returns immutable snapshots in ID order. Rejects missing roots, traversal,
     * links/aliases, duplicate IDs, extra entries and noncanonical SQL bytes.
     * Does not parse SQL, consult history, approve scripts or connect to a database.
     * Callers must exclude concurrent filesystem writers; this is not an OS sandbox.
     *
     * @param root existing canonical directory on the default local filesystem
     * @return immutable migration snapshots in ID order
     * @throws MigrationValidationException if the catalog cannot be safely read
     */
    public static List<MigrationFile> discover(Path root) throws MigrationValidationException {
        Objects.requireNonNull(root, "root");
        if (!FileSystems.getDefault().equals(root.getFileSystem())) { throw failure(Reason.INVALID_ROOT); }
        try {
            Path absolute = root.toAbsolutePath();
            for (Path part : absolute) {
                if (part.toString().equals("..")) { throw failure(Reason.INVALID_ROOT); }
            }
            Path canonical = absolute.normalize();
            List<Path> ancestors = new ArrayList<>();
            for (Path ancestor = canonical; ancestor != null; ancestor = ancestor.getParent()) {
                ancestors.add(ancestor);
            }
            for (Path ancestor : ancestors.reversed()) {
                BasicFileAttributes attributes = attributes(ancestor);
                if (!attributes.isDirectory() || attributes.isSymbolicLink() || attributes.isOther()) {
                    throw failure(Reason.INVALID_ROOT);
                }
            }
            if (!canonical.toRealPath().equals(canonical)) { throw failure(Reason.INVALID_ROOT); }
            Path marker = canonical.resolve(FORMAT_FILE);
            if (!Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) { throw failure(Reason.UNSUPPORTED_FORMAT); }
            byte[] header = readRegular(marker, 64);
            if (!Arrays.equals(header, FORMAT_HEADER.getBytes(StandardCharsets.UTF_8))) {
                throw failure(Reason.UNSUPPORTED_FORMAT);
            }
            List<MigrationFile> migrations = new ArrayList<>();
            Set<MigrationId> ids = new HashSet<>();
            long total = 0;
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(canonical)) {
                for (Path entry : entries) {
                    if (entry.getFileName().toString().equals(FORMAT_FILE)) { continue; }
                    if (migrations.size() >= MAX_MIGRATIONS) { throw failure(Reason.RESOURCE_LIMIT); }
                    BasicFileAttributes attributes = attributes(entry);
                    if (!attributes.isDirectory() || attributes.isSymbolicLink() || attributes.isOther()
                            || !entry.toRealPath().equals(entry)) { throw failure(Reason.INVALID_ENTRY); }
                    try {
                        MigrationFile.validateDirectoryName(entry.getFileName().toString());
                    } catch (IllegalArgumentException exception) {
                        throw failure(Reason.INVALID_ENTRY);
                    }
                    Path sql = entry.resolve("migration.sql");
                    try (DirectoryStream<Path> children = Files.newDirectoryStream(entry)) {
                        int count = 0;
                        for (Path child : children) {
                            if (!child.getFileName().toString().equals("migration.sql")) {
                                throw failure(Reason.INVALID_ENTRY);
                            }
                            count++;
                        }
                        if (count != 1) { throw failure(Reason.INVALID_ENTRY); }
                    }
                    byte[] bytes = readRegular(sql, MigrationFile.MAX_SQL_BYTES);
                    total += bytes.length;
                    if (total > MAX_TOTAL_SQL_BYTES) { throw failure(Reason.RESOURCE_LIMIT); }
                    MigrationFile migration;
                    try {
                        migration = MigrationFile.fromDirectory(entry.getFileName().toString(), bytes);
                    } catch (IllegalArgumentException exception) {
                        throw failure(Reason.INVALID_SQL);
                    }
                    if (!ids.add(migration.id())) { throw failure(Reason.DUPLICATE_ID); }
                    migrations.add(migration);
                }
            }
            migrations.sort(Comparator.comparing(MigrationFile::id));
            return List.copyOf(migrations);
        } catch (MigrationValidationException exception) {
            throw exception;
        } catch (DirectoryIteratorException exception) {
            throw new MigrationValidationException(exception.getCause());
        } catch (IOException exception) {
            throw new MigrationValidationException(exception);
        }
    }

    private static byte[] readRegular(Path path, int limit) throws IOException {
        BasicFileAttributes before = attributes(path);
        if (!before.isRegularFile() || before.isSymbolicLink() || before.isOther()
                || !path.toRealPath().equals(path)) { throw failure(Reason.INVALID_ENTRY); }
        if (before.size() > limit) { throw failure(Reason.RESOURCE_LIMIT); }
        byte[] bytes;
        try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readNBytes(limit + 1);
        }
        if (bytes.length > limit) { throw failure(Reason.RESOURCE_LIMIT); }
        BasicFileAttributes after = attributes(path);
        if (!after.isRegularFile() || !Objects.equals(before.fileKey(), after.fileKey())
                || before.size() != bytes.length || after.size() != bytes.length
                || !before.lastModifiedTime().equals(after.lastModifiedTime())) {
            throw failure(Reason.CHANGED_DURING_READ);
        }
        return bytes;
    }

    private static BasicFileAttributes attributes(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }

    private static MigrationValidationException failure(Reason reason) {
        return new MigrationValidationException(reason);
    }
}
