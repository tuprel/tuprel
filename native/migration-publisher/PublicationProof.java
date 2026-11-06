package dev.tuprel.migrate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;

/** Java-to-native integration on the verified filesystem supplied by the runner. */
public final class PublicationProof {
    private static final byte[] PRIOR = "-- existing bytes\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SQL = "-- reviewed migration é\n".getBytes(StandardCharsets.UTF_8);
    private static final String PRIOR_NAME = "20250101_000000_prior";
    private static final MigrationFile CANDIDATE = MigrationFile.fromDirectory("20260101_000000_candidate", SQL);
    private static final UUID GENERATION = UUID.randomUUID();

    public static void main(String[] args) throws Exception {
        Path binary = Path.of(args[0]).toRealPath();
        Path volume = Path.of(args[1]).toRealPath();
        byte[] manifest = manifest(binary);
        KeyPair key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(key.getPrivate());
        signer.update(manifest);
        byte[] signed = signer.sign();
        Path distribution = Files.createTempDirectory(binary.getParent(), "installed").toRealPath();
        Files.copy(binary, distribution.resolve(binary.getFileName()), StandardCopyOption.COPY_ATTRIBUTES);
        Files.write(distribution.resolve("manifest.txt"), manifest);
        Files.write(distribution.resolve("manifest.ed25519"), signed);
        NativePublisherInstallation installation = NativePublisherInstallation.load(distribution, key.getPublic());
        NativeMigrationPublisher publisher = new NativeMigrationPublisher(installation, GENERATION);

        Path valid = fixture(volume);
        expect(NativeMigrationPublisher.Outcome.PUBLISHED, publisher.publish(valid, CANDIDATE));
        expect(NativeMigrationPublisher.Outcome.PUBLISHED, publisher.reconcile(valid));
        expect(NativeMigrationPublisher.Outcome.PUBLICATION_UNKNOWN,
                new NativeMigrationPublisher(installation, UUID.randomUUID()).reconcile(valid));
        expect(true, publisher.acknowledge(valid, NativeMigrationPublisher.Outcome.PUBLISHED));
        expect(false, Files.exists(NativePublicationReceipt.path(valid)));
        intact(valid);

        Path interruptedBefore = fixture(volume);
        NativeMigrationPublisher before = interrupted(installation, false);
        expect(NativeMigrationPublisher.Outcome.PUBLICATION_UNKNOWN, before.publish(interruptedBefore, CANDIDATE));
        expect(false, Files.exists(interruptedBefore.resolve(CANDIDATE.directoryName())));
        expect(NativeMigrationPublisher.Outcome.NOT_PUBLISHED, publisher.reconcile(interruptedBefore));
        expect(false, publisher.acknowledge(interruptedBefore, NativeMigrationPublisher.Outcome.PUBLISHED));
        expect(true, publisher.acknowledge(interruptedBefore, NativeMigrationPublisher.Outcome.NOT_PUBLISHED));
        expect(false, Files.exists(NativePublicationReceipt.path(interruptedBefore)));

        Path interruptedAfter = fixture(volume);
        NativeMigrationPublisher after = interrupted(installation, true);
        expect(NativeMigrationPublisher.Outcome.PUBLICATION_UNKNOWN, after.publish(interruptedAfter, CANDIDATE));
        intact(interruptedAfter);
        byte[] receipt = Files.readAllBytes(NativePublicationReceipt.path(interruptedAfter));
        expect(NativeMigrationPublisher.Outcome.PUBLICATION_UNKNOWN, publisher.publish(interruptedAfter, CANDIDATE));
        try {
            new MigrationCreator().create(interruptedAfter, "manual");
            throw new AssertionError("Unreconciled publication allowed a second writer");
        } catch (MigrationCreationException blocked) {
            expect(MigrationCreationException.Reason.UNRESOLVED_PUBLICATION, blocked.reason());
        }
        expect(NativeMigrationPublisher.Outcome.PUBLISHED, publisher.reconcile(interruptedAfter));
        expect(true, Arrays.equals(receipt, Files.readAllBytes(NativePublicationReceipt.path(interruptedAfter))));
        Files.writeString(interruptedAfter.resolve(CANDIDATE.directoryName()).resolve("migration.sql"), "-- changed\n");
        expect(NativeMigrationPublisher.Outcome.PUBLICATION_UNKNOWN, publisher.reconcile(interruptedAfter));
        expect(false, publisher.acknowledge(interruptedAfter, NativeMigrationPublisher.Outcome.PUBLISHED));
        expect("-- changed\n", Files.readString(interruptedAfter.resolve(CANDIDATE.directoryName()).resolve("migration.sql")));
        expect(true, Arrays.equals(receipt, Files.readAllBytes(NativePublicationReceipt.path(interruptedAfter))));

        Path replaced = fixture(volume);
        expect(NativeMigrationPublisher.Outcome.PUBLISHED, publisher.publish(replaced, CANDIDATE));
        Path destination = replaced.resolve(CANDIDATE.directoryName());
        Files.move(destination, replaced.getParent().resolve("retained-original"));
        Files.write(Files.createDirectory(destination).resolve("migration.sql"), SQL);
        expect(NativeMigrationPublisher.Outcome.PUBLICATION_UNKNOWN, publisher.reconcile(replaced));
        intact(replaced);

        Path collision = fixture(volume);
        NativeMigrationPublisher raced = new NativeMigrationPublisher(installation, GENERATION, renamed -> {
            if (!renamed) {
                Path destination = Files.createDirectory(collision.resolve(CANDIDATE.directoryName()));
                Files.write(destination.resolve("migration.sql"), PRIOR);
            }
        });
        expect(NativeMigrationPublisher.Outcome.COLLISION, raced.publish(collision, CANDIDATE));
        expect(NativeMigrationPublisher.Outcome.PUBLICATION_UNKNOWN, publisher.reconcile(collision));
        expect(true, Arrays.equals(PRIOR, Files.readAllBytes(collision.resolve(CANDIDATE.directoryName()).resolve("migration.sql"))));

        byte[] wrong = manifest.clone();
        int digit = wrong.length - 2;
        wrong[digit] = (byte) (wrong[digit] == '0' ? '1' : '0');
        Path refused = fixture(volume);
        rejects(refused, new NativePublisherInstallation(binary, wrong, signed, key.getPublic()));
        Path replacement = Files.createTempDirectory(binary.getParent(), "substituted").resolve(binary.getFileName());
        Files.copy(binary, replacement, StandardCopyOption.COPY_ATTRIBUTES);
        Files.write(replacement, new byte[]{0}, java.nio.file.StandardOpenOption.APPEND);
        rejects(refused, new NativePublisherInstallation(replacement, manifest, signed, key.getPublic()));
        Path nested = Files.copy(binary, refused.getParent().getParent().resolve(binary.getFileName()),
                StandardCopyOption.COPY_ATTRIBUTES);
        rejects(refused, new NativePublisherInstallation(nested, manifest, signed, key.getPublic()));
        System.out.println("Java/native proof passed: exact bytes, receipt replay, collision, interruption before/after GO, tampering");
    }

    private static void rejects(Path root, NativePublisherInstallation installation) throws IOException {
        NativeMigrationPublisher publisher = new NativeMigrationPublisher(installation, GENERATION);
        expect(NativeMigrationPublisher.Outcome.REFUSED, publisher.publish(root, CANDIDATE));
        expect(false, Files.exists(NativePublicationReceipt.path(root)));
        expect(1, MigrationDirectory.discover(root).size());
        try (java.util.stream.Stream<Path> entries = Files.list(root.getParent())) {
            expect(1L, entries.count());
        }
    }

    private static NativeMigrationPublisher interrupted(NativePublisherInstallation installation, boolean after) {
        return new NativeMigrationPublisher(installation, GENERATION, renamed -> {
            if (renamed == after) { throw new IOException("Deliberate lost confirmation"); }
        });
    }

    private static Path fixture(Path volume) throws IOException {
        Path project = Files.createTempDirectory(volume, "publication proof ").toRealPath();
        Path root = Files.createDirectories(project.resolve("tuprel/migrations"));
        Files.writeString(root.resolve(MigrationDirectory.FORMAT_FILE), MigrationDirectory.FORMAT_HEADER);
        Files.write(Files.createDirectory(root.resolve(PRIOR_NAME)).resolve("migration.sql"), PRIOR);
        return root;
    }

    private static void intact(Path root) throws IOException {
        expect(true, Arrays.equals(PRIOR, Files.readAllBytes(root.resolve(PRIOR_NAME).resolve("migration.sql"))));
        expect(true, Arrays.equals(SQL, Files.readAllBytes(root.resolve(CANDIDATE.directoryName()).resolve("migration.sql"))));
    }

    private static byte[] manifest(Path binary) throws IOException {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows") ? "windows" : "linux";
        String digest = MigrationChecksum.of(Files.readAllBytes(binary)).value();
        return ("TUPREL-NATIVE-PUBLISHER 2\nos " + os + "\narch x86_64\nsha256 " + digest + "\n")
                .getBytes(StandardCharsets.US_ASCII);
    }

    private static void expect(Object expected, Object observed) {
        if (!expected.equals(observed)) { throw new AssertionError("Expected " + expected + ", observed " + observed); }
    }
}
