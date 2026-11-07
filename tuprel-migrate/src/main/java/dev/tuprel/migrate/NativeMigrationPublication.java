package dev.tuprel.migrate;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.PublicKey;
import java.time.Clock;
import java.time.DateTimeException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Filesystem bridge for the managed PostgreSQL adapter. It has no database or execution authority. */
public final class NativeMigrationPublication {
    private static final int MAX_SCHEMA_BYTES = 1024 * 1024;
    private final NativeMigrationPublisher publisher;
    private final Path catalog;
    private final Clock clock;

    /** Result of a local publication or reconciliation. */
    public enum State {
        /** The complete destination was observed with exact bytes and identities. */ PUBLISHED,
        /** Validation refused the operation before attempting publication. */ REFUSED,
        /** The destination already existed and the native helper did not replace it. */ COLLISION,
        /** Publication could not be confirmed. Preserve the receipt and reconcile explicitly. */ PUBLICATION_UNKNOWN,
        /** Reconciliation confirmed the original staging directory and no destination. */ NOT_PUBLISHED
    }

    /**
     * Preflights the Linux fixture profile and authenticates the native helper. The caller must
     * control the installation, trusted key and workspace throughout the operation.
     * @param catalog format-v1 migration directory
     * @param installation operator-controlled directory outside the project
     * @param issuer public key pinned by the operator
     * @param generation the verified provisioning generation
     * @param clock UTC timestamp source
     * @throws IOException if the catalog, helper or filesystem cannot be authenticated
     */
    public NativeMigrationPublication(Path catalog, Path installation, PublicKey issuer, UUID generation, Clock clock)
            throws IOException {
        this.catalog = NativeMigrationPublisher.catalog(catalog);
        if (!System.getProperty("os.name", "").equals("Linux")
                || !Files.getFileStore(this.catalog).type().equals("ext4")) { throw refused(); }
        NativePublisherInstallation nativeInstall = NativePublisherInstallation.load(installation, issuer);
        nativeInstall.verify(project());
        this.publisher = new NativeMigrationPublisher(nativeInstall, generation);
        this.clock = clock;
    }

    /**
     * Takes a bounded, strict UTF-8 snapshot of an unlinked source inside this project.
     * @param requested project-relative or confined absolute source path
     * @return source snapshot for compilation and later revalidation
     * @throws IOException if the path, identity or bytes cannot be verified
     */
    public Source readSource(Path requested) throws IOException {
        Path path = project().resolve(requested);
        if (!path.startsWith(project()) || path.getParent() == null || !path.equals(path.normalize())) { throw refused(); }
        MigrationDirectory.validatedRoot(path.getParent());
        List<Object> keys = identities(path);
        byte[] bytes = MigrationDirectory.readRegular(path, MAX_SCHEMA_BYTES);
        if (!keys.equals(identities(path))) { throw refused(); }
        String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        return new Source(path, keys, text);
    }

    /**
     * Captures the current catalog, including format marker and entry identities.
     * @return ordered exact migration snapshots and their local identity evidence
     * @throws IOException if the catalog cannot be read consistently
     */
    public Baseline readBaseline() throws IOException {
        List<Object> rootKeys = identities(catalog.resolve(MigrationDirectory.FORMAT_FILE));
        List<MigrationFile> files = MigrationDirectory.discover(catalog);
        List<Object> keys = new ArrayList<>(rootKeys);
        for (MigrationFile file : files) {
            keys.addAll(identities(catalog.resolve(file.directoryName()).resolve("migration.sql")));
        }
        MigrationChecksum checksum = NativeMigrationPublisher.digest(files);
        if (!rootKeys.equals(identities(catalog.resolve(MigrationDirectory.FORMAT_FILE)))
                || !checksum.equals(NativeMigrationPublisher.digest(MigrationDirectory.discover(catalog)))) { throw refused(); }
        return new Baseline(catalog, files, List.copyOf(keys), checksum);
    }

    /**
     * Publishes one candidate over the exact verified baseline. The callback reobserves
     * the database under its advisory lock before the helper receives permission to rename.
     * @param source original schema snapshot used to generate the candidate
     * @param reference exact ordered file snapshots used for planning
     * @param name format-v1 migration name
     * @param sql canonical proposal bytes represented as UTF-8 text
     * @param revalidate caller-owned read-only revalidation
     * @return publication result, with the exact file only when confirmed
     */
    public Result publish(Source source, Baseline reference, String name, String sql, Revalidation revalidate) {
        if (!catalog.equals(reference.catalog)) { return new Result(State.REFUSED, Optional.empty()); }
        MigrationFile candidate;
        try { candidate = new MigrationFile(MigrationId.at(clock.instant()), name, sql.getBytes(StandardCharsets.UTF_8)); }
        catch (IllegalArgumentException | DateTimeException failure) { return new Result(State.REFUSED, Optional.empty()); }
        State state = State.valueOf(publisher.publish(catalog, candidate, Optional.of(reference.checksum), () -> {
            verifySource(source);
            Baseline latest = readBaseline();
            if (!reference.keys.equals(latest.keys) || !reference.checksum.equals(latest.checksum)) { throw refused(); }
            revalidate.verify();
            verifySource(source);
        }).name());
        return new Result(state, state == State.PUBLISHED ? Optional.of(candidate) : Optional.empty());
    }

    /** {@return read-only reconciliation of the recorded attempt} */
    public State reconcile() { return State.valueOf(publisher.reconcile(catalog).name()); }

    /** {@return true unless the absence of a publication receipt is established} */
    public boolean hasUnresolvedAttempt() {
        return !Files.notExists(NativePublicationReceipt.path(catalog), LinkOption.NOFOLLOW_LINKS);
    }

    /**
     * Retires a receipt only after rechecking the operator's explicit resolution.
     * @param confirmed the result to acknowledge
     * @return whether the receipt was retired
     */
    public boolean acknowledge(State confirmed) {
        return publisher.acknowledge(catalog, NativeMigrationPublisher.Outcome.valueOf(confirmed.name()));
    }

    private Path project() { return catalog.getParent().getParent(); }

    private void verifySource(Source snapshot) throws IOException {
        Source current = readSource(snapshot.path);
        if (!current.keys.equals(snapshot.keys) || !current.text.equals(snapshot.text)) { throw refused(); }
    }

    private List<Object> identities(Path path) throws IOException {
        List<Object> keys = new ArrayList<>();
        for (Path entry = path; entry != null && entry.startsWith(project()); entry = entry.getParent()) {
            BasicFileAttributes attributes = Files.readAttributes(entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            boolean expected = entry.equals(path) ? attributes.isRegularFile() : attributes.isDirectory();
            if (!expected || attributes.fileKey() == null) { throw refused(); }
            keys.add(attributes.fileKey());
        }
        return List.copyOf(keys);
    }

    private static IOException refused() { return new IOException("Managed publication input refused"); }

    /** An immutable source snapshot. The path and filesystem identity remain internal. */
    public static final class Source {
        private final Path path;
        private final List<Object> keys;
        private final String text;

        private Source(Path path, List<Object> keys, String text) {
            this.path = path;
            this.keys = keys;
            this.text = text;
        }

        /** {@return strict UTF-8 source text} */
        public String text() { return text; }
    }

    /** A validated format-v1 catalog snapshot. Its filesystem identity remains internal. */
    public static final class Baseline {
        private final Path catalog;
        private final List<MigrationFile> files;
        private final List<Object> keys;
        private final MigrationChecksum checksum;

        private Baseline(Path catalog, List<MigrationFile> files, List<Object> keys, MigrationChecksum checksum) {
            this.catalog = catalog;
            this.files = files;
            this.keys = keys;
            this.checksum = checksum;
        }

        /** {@return immutable migration snapshots in format-v1 order} */
        public List<MigrationFile> files() { return files; }
    }

    /**
     * Local publication evidence for the calling adapter.
     * @param state observed filesystem result
     * @param file exact format-v1 snapshot when publication is confirmed
     */
    public record Result(State state, Optional<MigrationFile> file) { }

    /** Read-only callback invoked under the catalog lock before creating the receipt and sending GO. */
    @FunctionalInterface
    public interface Revalidation {
        /**
         * Reestablishes the verified reference under the caller's database lock.
         * @throws IOException when revalidation fails
         */
        void verify() throws IOException;
    }
}
