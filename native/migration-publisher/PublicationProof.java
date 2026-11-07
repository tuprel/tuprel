package dev.tuprel.migrate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Java-to-native integration on the verified filesystem supplied by the runner. */
public final class PublicationProof {
    private static final byte[] PRIOR = "-- existing bytes\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SQL = "-- reviewed migration é\n".getBytes(StandardCharsets.UTF_8);
    private static final String PRIOR_NAME = "20250101_000000_prior";
    private static final MigrationFile CANDIDATE = MigrationFile.fromDirectory("20260101_000000_candidate", SQL);
    private static final UUID GENERATION = UUID.randomUUID();

    public static void main(String[] args) throws Exception {
        if (args.length == 6 && args[0].equals("--halt")) {
            haltDuringPublication(args);
            return;
        }
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

        Path haltedBefore = fixture(volume);
        runAbruptExit(distribution, key, haltedBefore, false);
        expect(NativeMigrationPublisher.Outcome.NOT_PUBLISHED, reconcileAfterExit(publisher, haltedBefore));
        expect(false, Files.exists(haltedBefore.resolve(CANDIDATE.directoryName())));
        Path haltedAfter = fixture(volume);
        runAbruptExit(distribution, key, haltedAfter, true);
        expect(NativeMigrationPublisher.Outcome.PUBLISHED, reconcileAfterExit(publisher, haltedAfter));
        intact(haltedAfter);

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
                Path competingDirectory = Files.createDirectory(collision.resolve(CANDIDATE.directoryName()));
                Files.write(competingDirectory.resolve("migration.sql"), PRIOR);
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
        if (Files.getFileStore(volume).type().equals("ext4")) {
            verifyPublicationInputs(distribution, key, volume);
        }
        System.out.println("Java/native proof passed: exact bytes, receipt replay, collision, abrupt process exit, tampering");
    }

    private static void verifyPublicationInputs(Path distribution, KeyPair key, Path volume) throws IOException {
        Path root = fixture(volume);
        Path source = Files.writeString(root.resolveSibling("schema.tuprel"), "model Item { id Int @id }\n");
        NativeMigrationPublication publication = new NativeMigrationPublication(root, distribution, key.getPublic(), GENERATION,
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
        NativeMigrationPublication.Source snapshot = publication.readSource(source);
        NativeMigrationPublication.Baseline baseline = publication.readBaseline();
        Files.writeString(source, "model Changed { id Int @id }\n");
        expect(NativeMigrationPublication.State.REFUSED, publication.publish(snapshot, baseline, "candidate", CANDIDATE.sql(),
                () -> { throw new AssertionError("Changed source reached the database gate"); }).state());
        expect(false, publication.hasUnresolvedAttempt());
        Files.writeString(source, snapshot.text());
        expect(NativeMigrationPublication.State.REFUSED, publication.publish(snapshot, baseline, "candidate", CANDIDATE.sql(),
                () -> { throw new IOException("Fixture database gate refused"); }).state());
        expect(false, publication.hasUnresolvedAttempt());
        expect(false, Files.exists(root.resolve(CANDIDATE.directoryName())));
        Path oldSql = root.resolve(PRIOR_NAME).resolve("migration.sql");
        Path newSql = Files.write(root.resolve(PRIOR_NAME).resolve("replacement"), PRIOR);
        Files.move(newSql, oldSql, StandardCopyOption.REPLACE_EXISTING);
        expect(NativeMigrationPublication.State.REFUSED, publication.publish(snapshot, baseline, "candidate", CANDIDATE.sql(),
                () -> { throw new AssertionError("Replaced entry reached the database gate"); }).state());
        expect(false, Files.exists(root.resolve(CANDIDATE.directoryName())));
        expect(NativeMigrationPublication.State.PUBLISHED,
                publication.publish(snapshot, publication.readBaseline(), "candidate", CANDIDATE.sql(), () -> {}).state());
        intact(root);
    }

    private static void runAbruptExit(Path distribution, KeyPair key, Path root, boolean after) throws Exception {
        boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows");
        Path java = Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java");
        String pin = Base64.getEncoder().encodeToString(key.getPublic().getEncoded());
        Process child = new ProcessBuilder(java.toString(), "-cp", System.getProperty("java.class.path"),
                PublicationProof.class.getName(), "--halt", distribution.toString(), pin, root.toString(),
                GENERATION.toString(), Boolean.toString(after)).inheritIO().start();
        try {
            expect(true, child.waitFor(30, TimeUnit.SECONDS));
            expect(73, child.exitValue());
        } finally {
            if (child.isAlive()) { child.destroyForcibly(); }
        }
    }

    private static void haltDuringPublication(String[] args) throws Exception {
        byte[] pin = Base64.getDecoder().decode(args[2]);
        NativePublisherInstallation installation = NativePublisherInstallation.load(Path.of(args[1]),
                KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(pin)));
        boolean after = Boolean.parseBoolean(args[5]);
        NativeMigrationPublisher publisher = new NativeMigrationPublisher(installation, UUID.fromString(args[4]), renamed -> {
            if (renamed == after) { Runtime.getRuntime().halt(73); }
        });
        throw new AssertionError("Interruption checkpoint was not reached: " + publisher.publish(Path.of(args[3]), CANDIDATE));
    }

    private static NativeMigrationPublisher.Outcome reconcileAfterExit(NativeMigrationPublisher publisher, Path root)
            throws InterruptedException {
        // The native child may still be releasing its handles after the JVM exits.
        for (int attempt = 0; attempt < 20; attempt++) {
            NativeMigrationPublisher.Outcome outcome = publisher.reconcile(root);
            if (outcome != NativeMigrationPublisher.Outcome.PUBLICATION_UNKNOWN) { return outcome; }
            Thread.sleep(50);
        }
        return NativeMigrationPublisher.Outcome.PUBLICATION_UNKNOWN;
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
