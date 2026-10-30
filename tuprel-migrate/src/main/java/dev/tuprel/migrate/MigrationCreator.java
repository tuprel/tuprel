package dev.tuprel.migrate;

import static dev.tuprel.migrate.MigrationCreationException.Reason;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Creates comment-only format-v1 drafts on a developer-controlled local filesystem.
 * No SQL parsing, approval, database access or application is performed. Cooperating
 * creators serialize through a persistent empty sibling file; never unlink it while
 * a creator may be active. Other editors/readers must exclude concurrent writes.
 */
public final class MigrationCreator {
    private static final String DRAFT = "-- Add reviewed SQL below. Creating this draft does not apply or approve it.\n";
    private static final DateTimeFormatter ID_FORMAT = DateTimeFormatter.ofPattern("uuuuMMdd_HHmmss", Locale.ROOT)
            .withZone(ZoneOffset.UTC);
    // Do not open a second channel to the lock inode within the JVM: on some
    // systems closing that channel can release the first channel's process lock.
    private static final ReentrantLock JVM_LOCK = new ReentrantLock();
    private final Clock clock;
    private final DraftWriter writer;

    /** Creates drafts using the system UTC clock. */
    public MigrationCreator() { this(Clock.systemUTC()); }

    /**
     * Creates drafts using an explicit clock, always formatted in UTC.
     * @param clock source of timestamp IDs; never automatically advanced on collision
     */
    public MigrationCreator(Clock clock) {
        this(clock, (path, bytes) -> Files.write(path, bytes, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS));
    }

    MigrationCreator(Clock clock, DraftWriter writer) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.writer = Objects.requireNonNull(writer, "writer");
    }

    /**
     * Validates and previews a draft without creating any file or reserving its ID.
     * A later create call revalidates under the lock and may produce a different ID.
     * Permissions are preflight checks; only a real write can establish writability.
     * @param root existing format-v1 catalog, with canonical unlinked ancestors
     * @param name canonical name; never silently normalized
     * @return immutable comment-only draft
     * @throws IOException for invalid files, collision, capacity, permissions or unsafe paths
     */
    public MigrationFile preview(Path root, String name) throws IOException {
        validateName(name);
        Path canonical = MigrationDirectory.validatedRoot(root);
        MigrationDirectory.verifyFormat(canonical);
        checkLockPath(lockPath(canonical));
        checkWritable(canonical);
        return plan(canonical, name);
    }

    /**
     * Exclusively creates a directory and migration.sql, never overwriting files.
     * Uses a nonblocking OS lock on the empty sibling {@code .<catalog>.create.lock}.
     * The file remains after unlock to preserve inode identity. A failure after
     * directory allocation preserves partial files for manual inspection.
     * @param root existing format-v1 catalog, with canonical unlinked ancestors
     * @param name canonical name; never silently normalized
     * @return immutable snapshot of the created draft
     * @throws IOException for invalid files, collision, lock contention, permissions or partial creation
     */
    public MigrationFile create(Path root, String name) throws IOException {
        validateName(name);
        Path canonical = MigrationDirectory.validatedRoot(root);
        MigrationDirectory.verifyFormat(canonical);
        if (!JVM_LOCK.tryLock()) { throw new MigrationCreationException(Reason.BUSY); }
        boolean allocated = false;
        try {
            Path lockPath = lockPath(canonical);
            checkLockPath(lockPath);
            try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE,
                    StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                    FileLock lock = channel.tryLock(0, 1, false)) {
                if (lock == null) { throw new MigrationCreationException(Reason.BUSY); }
                checkLockPath(lockPath);
                checkWritable(canonical);
                MigrationFile draft = plan(canonical, name);
                Path directory = canonical.resolve(draft.directoryName());
                Files.createDirectory(directory);
                allocated = true;
                MigrationDirectory.validatedRoot(directory);
                writer.write(directory.resolve("migration.sql"), draft.sql().getBytes(StandardCharsets.UTF_8));
                // Reuse the bounded reader to verify exactly what is left on disk.
                MigrationFile written = MigrationDirectory.discover(canonical).stream()
                        .filter(file -> file.id().equals(draft.id())).findFirst()
                        .orElseThrow(() -> new IOException("Draft missing after write"));
                if (!written.checksum().equals(draft.checksum())) { throw new IOException("Draft changed during write"); }
                return written;
            }
        } catch (OverlappingFileLockException exception) {
            throw new MigrationCreationException(Reason.BUSY, exception);
        } catch (SecurityException exception) {
            throw new MigrationCreationException(allocated ? Reason.PARTIAL_CREATION : Reason.IO_ERROR, exception);
        } catch (IOException exception) {
            if (allocated) { throw new MigrationCreationException(Reason.PARTIAL_CREATION, exception); }
            if (exception instanceof MigrationCreationException || exception instanceof MigrationValidationException) {
                throw exception;
            }
            throw new MigrationCreationException(Reason.IO_ERROR, exception);
        } finally {
            JVM_LOCK.unlock();
        }
    }

    private MigrationFile plan(Path root, String name) throws IOException {
        List<MigrationFile> existing = MigrationDirectory.discover(root);
        MigrationId id;
        try {
            id = new MigrationId(ID_FORMAT.format(clock.instant()));
        } catch (IllegalArgumentException | DateTimeException exception) {
            throw new MigrationCreationException(Reason.CLOCK_ORDER);
        }
        if (existing.stream().anyMatch(file -> file.id().equals(id))) {
            throw new MigrationCreationException(Reason.COLLISION);
        }
        if (!existing.isEmpty() && id.compareTo(existing.getLast().id()) < 0) {
            throw new MigrationCreationException(Reason.CLOCK_ORDER);
        }
        byte[] bytes = DRAFT.getBytes(StandardCharsets.UTF_8);
        long total = existing.stream().mapToLong(file -> file.sql().getBytes(StandardCharsets.UTF_8).length).sum();
        if (existing.size() >= MigrationDirectory.MAX_MIGRATIONS || total + bytes.length > MigrationDirectory.MAX_TOTAL_SQL_BYTES) {
            throw new MigrationCreationException(Reason.CAPACITY);
        }
        return new MigrationFile(id, name, bytes);
    }

    private static void validateName(String name) throws MigrationCreationException {
        Objects.requireNonNull(name, "name");
        try {
            MigrationFile.validateName(name);
        } catch (IllegalArgumentException exception) {
            throw new MigrationCreationException(Reason.INVALID_NAME);
        }
    }

    private static Path lockPath(Path root) throws MigrationCreationException {
        if (root.getParent() == null || root.getFileName() == null) {
            throw new MigrationCreationException(Reason.UNSAFE_LOCK);
        }
        return root.resolveSibling("." + root.getFileName() + ".create.lock");
    }

    private static void checkLockPath(Path lock) throws IOException {
        if (!Files.exists(lock, LinkOption.NOFOLLOW_LINKS)) { return; }
        BasicFileAttributes attributes = Files.readAttributes(lock, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.isSymbolicLink() || attributes.isOther()
                || attributes.size() != 0 || !lock.toRealPath().equals(lock)) {
            throw new MigrationCreationException(Reason.UNSAFE_LOCK);
        }
    }

    private static void checkWritable(Path root) throws MigrationCreationException {
        Path lock = lockPath(root);
        if (!Files.isWritable(root) || !Files.isWritable(root.getParent())
                || (Files.exists(lock, LinkOption.NOFOLLOW_LINKS) && !Files.isWritable(lock))) {
            throw new MigrationCreationException(Reason.IO_ERROR);
        }
    }

    @FunctionalInterface
    interface DraftWriter {
        void write(Path path, byte[] bytes) throws IOException;
    }
}
