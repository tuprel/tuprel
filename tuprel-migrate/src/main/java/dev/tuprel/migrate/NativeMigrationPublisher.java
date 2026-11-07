package dev.tuprel.migrate;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Internal publication over the existing catalog lock. Installation and workspace are operator-controlled. */
final class NativeMigrationPublisher {
    enum Outcome { PUBLISHED, REFUSED, COLLISION, PUBLICATION_UNKNOWN, NOT_PUBLISHED }

    private final NativePublisherInstallation installation;
    private final UUID generation;
    private final InterruptionPoint interruption;

    NativeMigrationPublisher(NativePublisherInstallation installation, UUID generation) {
        this(installation, generation, renamed -> {});
    }

    NativeMigrationPublisher(NativePublisherInstallation installation, UUID generation, InterruptionPoint interruption) {
        this.installation = Objects.requireNonNull(installation, "installation");
        this.generation = Objects.requireNonNull(generation, "generation");
        this.interruption = Objects.requireNonNull(interruption, "interruption");
    }

    Outcome publish(Path root, MigrationFile candidate) {
        return publish(root, candidate, Optional.empty(), () -> {});
    }

    Outcome publish(Path root, MigrationFile candidate, Optional<MigrationChecksum> expected, BeforeRename check) {
        Outcome outcome = Outcome.REFUSED;
        try {
            Path canonical = catalog(root);
            installation.verify(canonical.getParent().getParent());
            MigrationCreator.CatalogLock lock = MigrationCreator.acquire(canonical);
            try (lock) {
                outcome = publishLocked(canonical, candidate, expected, check);
            }
        } catch (IOException | SecurityException | IllegalArgumentException | OverlappingFileLockException failure) {
            return outcome;
        }
        return outcome;
    }

    /** Inspects an existing receipt without advancing or removing it. No GO frame is sent. */
    Outcome reconcile(Path root) {
        try {
            Path canonical = catalog(root);
            Path executable = installation.verify(canonical.getParent().getParent());
            NativePublicationReceipt receipt = NativePublicationReceipt.read(NativePublicationReceipt.path(canonical));
            return observe(canonical, executable, receipt);
        } catch (IOException | SecurityException | IllegalArgumentException failure) { return Outcome.PUBLICATION_UNKNOWN; }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return Outcome.PUBLICATION_UNKNOWN; }
    }

    /** Explicitly retires a receipt only if a new observation confirms the requested result. */
    boolean acknowledge(Path root, Outcome expected) {
        if (expected != Outcome.PUBLISHED && expected != Outcome.NOT_PUBLISHED) { return false; }
        try {
            Path canonical = catalog(root);
            Path executable = installation.verify(canonical.getParent().getParent());
            MigrationCreator.CatalogLock lock = MigrationCreator.acquire(canonical);
            try (lock) {
                Path journal = NativePublicationReceipt.path(canonical);
                NativePublicationReceipt receipt = NativePublicationReceipt.read(journal);
                if (observe(canonical, executable, receipt) != expected
                        || !receipt.equals(NativePublicationReceipt.read(journal))) { return false; }
                Files.delete(journal);
                return true;
            }
        } catch (IOException | SecurityException | IllegalArgumentException | OverlappingFileLockException failure) { return false; }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return false; }
    }

    private Outcome publishLocked(Path root, MigrationFile candidate, Optional<MigrationChecksum> expected, BeforeRename check)
            throws IOException {
        Path journal = NativePublicationReceipt.path(root);
        if (!Files.notExists(journal, LinkOption.NOFOLLOW_LINKS)) { return Outcome.PUBLICATION_UNKNOWN; }
        List<MigrationFile> existing = MigrationDirectory.discover(root);
        MigrationChecksum baseline = digest(existing);
        if (expected.isPresent() && !expected.orElseThrow().equals(baseline)) { return Outcome.REFUSED; }
        byte[] sql = candidate.sql().getBytes(StandardCharsets.UTF_8);
        if (!existing.isEmpty() && existing.getLast().id().compareTo(candidate.id()) >= 0) { return Outcome.REFUSED; }
        if (existing.size() >= MigrationDirectory.MAX_MIGRATIONS || sql.length + existing.stream()
                .mapToLong(file -> file.sql().getBytes(StandardCharsets.UTF_8).length).sum()
                > MigrationDirectory.MAX_TOTAL_SQL_BYTES) { return Outcome.REFUSED; }
        String stageName = ".migration-stage-" + UUID.randomUUID().toString().replace("-", "");
        Path parent = root.getParent();
        Path stage = parent.resolve(stageName);
        if (Files.getFileStore(parent).supportsFileAttributeView("posix")) {
            Files.createDirectory(stage, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        } else { Files.createDirectory(stage); }
        forceNew(stage.resolve("migration.sql"), sql);
        if (!staged(stage, candidate.checksum())) { return Outcome.REFUSED; }
        Path executable = installation.verify(parent.getParent());
        boolean recorded = false;
        try (NativeRenameProcess process = NativeRenameProcess.start(executable, parent, stageName, candidate.directoryName())) {
            NativePublicationReceipt receipt = new NativePublicationReceipt(generation, stageName, candidate.directoryName(),
                    candidate.checksum(), baseline, process.identity("READY"));
            check.verify();
            if (!staged(stage, receipt.checksum()) || !digest(MigrationDirectory.discover(root)).equals(receipt.baseline())) {
                return Outcome.REFUSED;
            }
            recorded = true;
            forceNew(journal, receipt.bytes());
            interruption.check(false);
            if (!staged(stage, receipt.checksum())) { return Outcome.PUBLICATION_UNKNOWN; }
            process.go();
            NativeRenameProcess.Completion completion = process.renameResult();
            if (completion == NativeRenameProcess.Completion.COLLISION) { return Outcome.COLLISION; }
            if (completion != NativeRenameProcess.Completion.MOVED) { return Outcome.PUBLICATION_UNKNOWN; }
            interruption.check(true);
            if (observe(root, installation.verify(parent.getParent()), receipt) != Outcome.PUBLISHED
                    || !receipt.equals(NativePublicationReceipt.read(journal))) { return Outcome.PUBLICATION_UNKNOWN; }
            return Outcome.PUBLISHED;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return recorded ? Outcome.PUBLICATION_UNKNOWN : Outcome.REFUSED;
        } catch (IOException | RuntimeException failure) {
            return recorded ? Outcome.PUBLICATION_UNKNOWN : Outcome.REFUSED;
        }
    }

    private Outcome observe(Path root, Path executable, NativePublicationReceipt receipt)
            throws IOException, InterruptedException {
        if (!generation.equals(receipt.generation())) { return Outcome.PUBLICATION_UNKNOWN; }
        List<MigrationFile> observed = MigrationDirectory.discover(root);
        List<MigrationFile> previous = observed.stream().filter(file -> !file.directoryName().equals(receipt.destination())).toList();
        if (!digest(previous).equals(receipt.baseline())) { return Outcome.PUBLICATION_UNKNOWN; }
        boolean candidateMatches = observed.stream().anyMatch(file -> file.directoryName().equals(receipt.destination())
                && file.checksum().equals(receipt.checksum()));
        Path stage = root.getParent().resolve(receipt.stage());
        try (NativeRenameProcess proof = NativeRenameProcess.start(executable, root.getParent(), receipt.stage(), receipt.destination())) {
            if (candidateMatches && receipt.identity().equals(proof.identity("PRESENT")) && proof.complete(13, "OBSERVED")) {
                return digest(MigrationDirectory.discover(root)).equals(digest(observed)) ? Outcome.PUBLISHED : Outcome.PUBLICATION_UNKNOWN;
            }
            if (observed.size() == previous.size() && receipt.identity().equals(proof.identity("READY"))
                    && staged(stage, receipt.checksum())
                    && digest(MigrationDirectory.discover(root)).equals(receipt.baseline())) { return Outcome.NOT_PUBLISHED; }
            return Outcome.PUBLICATION_UNKNOWN;
        }
    }

    static Path catalog(Path root) throws IOException {
        Path canonical = MigrationDirectory.validatedRoot(root);
        if (canonical.getParent() == null || canonical.getParent().getParent() == null
                || !canonical.getFileName().toString().equals("migrations")
                || !canonical.getParent().getFileName().toString().equals("tuprel")) {
            throw new IOException("Invalid publication root");
        }
        MigrationDirectory.verifyFormat(canonical);
        return canonical;
    }


    private static boolean staged(Path stage, MigrationChecksum expected) throws IOException {
        MigrationDirectory.validatedRoot(stage);
        try (DirectoryStream<Path> files = Files.newDirectoryStream(stage)) {
            java.util.Iterator<Path> entries = files.iterator();
            if (!entries.hasNext() || !entries.next().getFileName().toString().equals("migration.sql")
                    || entries.hasNext()) { return false; }
        }
        return MigrationChecksum.of(MigrationDirectory.readRegular(stage.resolve("migration.sql"), MigrationFile.MAX_SQL_BYTES)).equals(expected);
    }

    private static void forceNew(Path file, byte[] bytes) throws IOException {
        Set<OpenOption> options = Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        FileAttribute<?>[] permissions = Files.getFileStore(file.getParent()).supportsFileAttributeView("posix")
                ? new FileAttribute<?>[]{PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))}
                : new FileAttribute<?>[0];
        try (FileChannel channel = FileChannel.open(file, options, permissions)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) { channel.write(buffer); }
            channel.force(true);
        }
    }

    static MigrationChecksum digest(List<MigrationFile> catalog) {
        StringBuilder canonical = new StringBuilder("TUPREL-PUBLICATION-BASELINE 1\n");
        for (MigrationFile file : catalog) {
            canonical.append(file.directoryName()).append(' ').append(file.checksum().value()).append('\n');
        }
        return MigrationChecksum.of(canonical.toString().getBytes(StandardCharsets.US_ASCII));
    }

    @FunctionalInterface
    interface InterruptionPoint {
        void check(boolean renamed) throws IOException;
    }

    @FunctionalInterface
    interface BeforeRename {
        void verify() throws IOException;
    }
}
