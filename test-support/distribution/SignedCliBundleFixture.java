import java.io.IOException;
import java.nio.file.StandardCopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class SignedCliBundleFixture {
    private static final String FIRST = "0.0.0-staging";
    private static final String SECOND = "0.0.1-staging";

    private SignedCliBundleFixture() {}

    public static void main(String[] args) throws Exception {
        if (args[0].equals("interrupt-activation")) {
            StagedActivation.CommitStage stop = StagedActivation.CommitStage.valueOf(args[1]);
            StagedActivation.execute(java.util.Arrays.copyOfRange(args, 2, args.length), stage -> {
                if (stage == stop) Runtime.getRuntime().halt(42);
            });
            throw new AssertionError("Activation did not reach its interruption checkpoint");
        }
        Path privateRoot = Path.of(args[1]).toRealPath();
        Path firstZip = Path.of(args[2]);
        Path secondZip = Path.of(args[3]);
        if (args[0].equals("prepare")) {
            KeyPair fixtureKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            byte[] publicKey = fixtureKey.getPublic().getEncoded();
            Files.write(privateRoot.resolve("public-key.bin"), publicKey);
            String keyId = SignedCliBundle.sha256(publicKey);
            signBundle(privateRoot, firstZip, FIRST, fixtureKey);
            signBundle(privateRoot, secondZip, SECOND, fixtureKey);
            Files.copy(firstZip, privateRoot.resolve(FIRST + ".zip"));
            Files.copy(secondZip, privateRoot.resolve(SECOND + ".zip"));
            String firstDigest = SignedCliBundle.sha256(Files.readAllBytes(privateRoot.resolve(FIRST + ".manifest")));
            String secondDigest = SignedCliBundle.sha256(Files.readAllBytes(privateRoot.resolve(SECOND + ".manifest")));
            byte[] policy = authorization(firstDigest, secondDigest, "rollback");
            Files.write(privateRoot.resolve("authorization"), policy);
            Files.write(privateRoot.resolve("authorization.ed25519"), sign(fixtureKey, policy));
            byte[] revoked = authorization(firstDigest, secondDigest, "revoked");
            Files.write(privateRoot.resolve("revoked-authorization"), revoked);
            Files.write(privateRoot.resolve("revoked-authorization.ed25519"), sign(fixtureKey, revoked));
            byte[] java21 = java21Archive(Files.readAllBytes(secondZip));
            Files.write(privateRoot.resolve("java21.zip"), java21);
            byte[] java21Manifest = SignedCliBundle.manifest(SECOND, java21);
            Files.write(privateRoot.resolve("java21.manifest"), java21Manifest);
            Files.write(privateRoot.resolve("java21.ed25519"), sign(fixtureKey, java21Manifest));
            byte[] java21Policy = authorization(firstDigest, SignedCliBundle.sha256(java21Manifest), "rollback");
            Files.write(privateRoot.resolve("java21-authorization"), java21Policy);
            Files.write(privateRoot.resolve("java21-authorization.ed25519"), sign(fixtureKey, java21Policy));
            assertRejectsUntrustedInputs(privateRoot, firstZip, secondZip, fixtureKey, keyId);
            byte[] archive = Files.readAllBytes(firstZip);
            Path alternate = Files.write(privateRoot.resolve("alternate.zip"),
                    java.util.Arrays.copyOf(archive, archive.length + 1));
            byte[] alternateManifest = SignedCliBundle.manifest(FIRST, Files.readAllBytes(alternate));
            Files.write(privateRoot.resolve("alternate.manifest"), alternateManifest);
            Files.write(privateRoot.resolve("alternate.ed25519"), sign(fixtureKey, alternateManifest));
            Path interrupted = Files.createTempDirectory(privateRoot, "." + FIRST + "-pending-");
            Files.writeString(interrupted.resolve("partial"), "interrupted attempt");
            System.out.println(keyId);
        } else if (args[0].equals("prepare-legacy")) {
            StagingRoot.load(Path.of(args[5]));
            PublicKey key = publicKey(privateRoot);
            SignedCliBundle legacy = java21Bundle(privateRoot, key, args[4]);
            Path old = child(privateRoot, "legacy installation");
            legacy.install(old);
        } else if (args[0].equals("test-runtime")) {
            StagingRoot.load(Path.of(args[5]));
            PublicKey key = publicKey(privateRoot);
            SignedCliBundle legacy = java21Bundle(privateRoot, key, args[4]);
            Path target = child(privateRoot, "runtime target");
            Path catalogue = child(privateRoot, "runtime catalogue");
            for (String suffix : new String[]{".zip", ".manifest", ".ed25519"}) {
                Files.copy(privateRoot.resolve(FIRST + suffix), catalogue.resolve(FIRST + suffix));
                Files.copy(privateRoot.resolve("java21" + suffix), catalogue.resolve(SECOND + suffix));
            }
            Files.copy(privateRoot.resolve("public-key.bin"), catalogue.resolve("public-key.bin"));
            Files.copy(privateRoot.resolve("java21-authorization"), catalogue.resolve("authorization"));
            Files.copy(privateRoot.resolve("java21-authorization.ed25519"), catalogue.resolve("authorization.ed25519"));
            verified(privateRoot, firstZip, key, args[4], FIRST).install(target);
            String[] control = {target.toString(), args[5], catalogue.toString(), args[4], "activate", FIRST};
            if (StagedActivation.execute(control, stage -> {}) != 0) throw new AssertionError("Initial activation failed");
            byte[] previous = Files.readAllBytes(target.resolve(".tuprel-active"));
            control[5] = SECOND;
            if (Runtime.version().feature() < 21) {
                rejectRuntime(() -> legacy.install(target));
                rejectRuntime(() -> StagedActivation.execute(control, stage -> {}));
                if (!java.util.Arrays.equals(previous, Files.readAllBytes(target.resolve(".tuprel-active")))
                        || Files.exists(target.resolve(SECOND))) {
                    throw new AssertionError("Incompatible update changed the previous installation");
                }
                // Model an installation created by the trusted Java 21 producer, without executing its classes.
                copyLegacy(privateRoot.resolve("legacy installation"), target, legacy);
            } else {
                legacy.install(target);
                if (StagedActivation.execute(control, stage -> {}) != 0) throw new AssertionError("Java21 activation failed");
                control[4] = "rollback";
                control[5] = FIRST;
                if (StagedActivation.execute(control, stage -> {}) != 0) throw new AssertionError("Explicit rollback failed");
            }
            legacy.uninstall(target);
            if (Files.exists(target.resolve(SECOND))) throw new AssertionError("Authenticated legacy removal failed");
            String[] launch = {target.toString(), args[5], catalogue.toString(), args[4], "launch", "--version"};
            if (StagedActivation.execute(launch, stage -> {}) != 0) throw new AssertionError("Compatible launcher failed");
            System.out.println("Runtime transition, retained active version and legacy removal passed on Java "
                    + Runtime.version().feature());
        } else if (args[0].equals("test-refusal")) {
            StagingRoot.load(Path.of(args[5]));
            PublicKey trustedKey = KeyFactory.getInstance("Ed25519").generatePublic(
                    new X509EncodedKeySpec(Files.readAllBytes(privateRoot.resolve("public-key.bin"))));
            SignedCliBundle first = verified(privateRoot, firstZip, trustedKey, args[4], FIRST);
            SignedCliBundle alternate = SignedCliBundle.verify(privateRoot.resolve("alternate.zip"),
                    Files.readAllBytes(privateRoot.resolve("alternate.manifest")),
                    Files.readAllBytes(privateRoot.resolve("alternate.ed25519")), trustedKey, args[4], FIRST);
            reject(() -> alternate.install(privateRoot));
            reject(() -> alternate.uninstall(privateRoot));
            Path extra = Files.writeString(privateRoot.resolve(FIRST + "/bin/local-file"), "preserve");
            reject(() -> first.install(privateRoot));
            reject(() -> first.uninstall(privateRoot));
            if (!Files.readString(extra).equals("preserve")) throw new AssertionError("Unexpected file was removed");
            Files.delete(extra);
            Path launcher = privateRoot.resolve(FIRST + "/bin/tuprel");
            byte[] original = Files.readAllBytes(launcher);
            Files.writeString(launcher, "tampered launcher");
            reject(() -> first.install(privateRoot));
            reject(() -> first.uninstall(privateRoot));
            if (!Files.readString(launcher).equals("tampered launcher")) throw new AssertionError("Changed file was removed");
            Files.write(launcher, original);
        } else if (args[0].equals("interrupt-removal")) {
            Path removing = privateRoot.resolve("." + FIRST + "-removing");
            Files.move(privateRoot.resolve(FIRST), removing, StandardCopyOption.ATOMIC_MOVE);
            Files.delete(removing.resolve("bin/tuprel"));
            Runtime.getRuntime().halt(42);
        } else {
            throw new IllegalArgumentException("Unknown signed-bundle fixture operation");
        }
    }

    private static void assertRejectsUntrustedInputs(Path directory, Path firstZip, Path secondZip,
            KeyPair fixtureKey, String keyId) throws Exception {
        PublicKey publicKey = fixtureKey.getPublic();
        byte[] manifest = Files.readAllBytes(directory.resolve(FIRST + ".manifest"));
        byte[] signature = Files.readAllBytes(directory.resolve(FIRST + ".ed25519"));
        reject(() -> SignedCliBundle.verify(firstZip, manifest, signature, publicKey, "invalid", FIRST));
        reject(() -> SignedCliBundle.verify(firstZip, manifest, signature, publicKey, keyId, SECOND));
        reject(() -> SignedCliBundle.verify(secondZip, manifest, signature, publicKey, keyId, FIRST));
        byte[] corrupt = signature.clone();
        corrupt[0] ^= 1;
        reject(() -> SignedCliBundle.verify(firstZip, manifest, corrupt, publicKey, keyId, FIRST));
        byte[] alteredManifest = manifest.clone();
        alteredManifest[alteredManifest.length - 2] ^= 1;
        reject(() -> SignedCliBundle.verify(firstZip, alteredManifest, signature, publicKey, keyId, FIRST));
        Path traversal = directory.resolve("traversal.zip");
        for (String entry : java.util.List.of("bin/../../outside", "lib/../outside.jar", "lib/nested\\..\\outside.jar")) {
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(traversal))) {
                zip.putNextEntry(new ZipEntry("tuprel-" + FIRST + "/" + entry));
                zip.write(1);
            }
            byte[] hostile = SignedCliBundle.manifest(FIRST, Files.readAllBytes(traversal));
            reject(() -> SignedCliBundle.verify(traversal, hostile, sign(fixtureKey, hostile), publicKey, keyId, FIRST));
            Files.delete(traversal);
        }
    }

    private static PublicKey publicKey(Path root) throws Exception {
        return KeyFactory.getInstance("Ed25519").generatePublic(
                new X509EncodedKeySpec(Files.readAllBytes(root.resolve("public-key.bin"))));
    }

    private static SignedCliBundle java21Bundle(Path root, PublicKey key, String pin) throws Exception {
        return SignedCliBundle.verify(root.resolve("java21.zip"), Files.readAllBytes(root.resolve("java21.manifest")),
                Files.readAllBytes(root.resolve("java21.ed25519")), key, pin, SECOND);
    }

    private static Path child(Path parent, String name) throws Exception {
        try (StagingRoot guarded = new StagingRoot(parent)) {
            return parent.resolve(guarded.createDirectory(guarded.anchor(), name, false).getFileName());
        }
    }

    private static void copyLegacy(Path source, Path target, SignedCliBundle legacy) throws Exception {
        try (StagingRoot from = new StagingRoot(source); StagingRoot to = new StagingRoot(target)) {
            Path installed = from.anchor().resolve(SECOND);
            java.util.List<String> files = new java.util.ArrayList<>(legacy.checkedLibraries(from, installed));
            files.addAll(java.util.List.of("bin/tuprel", "bin/tuprel.bat", ".bundle-sha256"));
            Path destination = to.createDirectory(to.anchor(), SECOND, false);
            to.createDirectory(destination, "bin", false);
            to.createDirectory(destination, "lib", false);
            for (String name : files) {
                from.verifyFile(installed.resolve(name));
                to.write(destination.resolve(name), Files.readAllBytes(installed.resolve(name)), name.equals("bin/tuprel"));
            }
        }
    }

    private static byte[] java21Archive(byte[] original) throws Exception {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        boolean changed = false;
        try (java.util.zip.ZipInputStream input = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(original));
                ZipOutputStream zip = new ZipOutputStream(output)) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                byte[] bytes = input.readAllBytes();
                if (entry.getName().contains("/lib/tuprel-codegen-java") && entry.getName().endsWith(".jar")) {
                    java.io.ByteArrayOutputStream library = new java.io.ByteArrayOutputStream();
                    try (java.util.zip.ZipInputStream classes = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(bytes));
                            ZipOutputStream jar = new ZipOutputStream(library)) {
                        ZipEntry file;
                        while ((file = classes.getNextEntry()) != null) {
                            byte[] content = classes.readAllBytes();
                            if (file.getName().equals("dev/tuprel/codegen/JavaCodeGenerator.class")) {
                                if (content.length < 8) throw new AssertionError("Missing fixture class header");
                                content[6] = 0;
                                content[7] = 65;
                                changed = true;
                            }
                            jar.putNextEntry(new ZipEntry(file.getName()));
                            jar.write(content);
                            jar.closeEntry();
                        }
                    }
                    bytes = library.toByteArray();
                }
                zip.putNextEntry(new ZipEntry(entry.getName()));
                zip.write(bytes);
                zip.closeEntry();
            }
        }
        if (!changed) throw new AssertionError("Expected the codegen class for the Java21 negative fixture");
        return output.toByteArray();
    }

    private static void rejectRuntime(FixtureOperation operation) throws Exception {
        try {
            operation.run();
            throw new AssertionError("Accepted Java21 bytes in a Java17 runtime");
        } catch (IOException expected) {
            if (!"CLI library is incompatible with the selected JVM".equals(expected.getMessage())) throw expected;
        }
    }

    private static byte[] authorization(String firstDigest, String secondDigest, String firstMode) {
        return ("TUPREL-STAGING-AUTHORIZATION 1\n" + FIRST + " " + firstDigest + " " + firstMode
                + "\n" + SECOND + " " + secondDigest + " allow\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    }

    private static void signBundle(Path directory, Path archive, String version, KeyPair fixtureKey) throws Exception {
        byte[] manifest = SignedCliBundle.manifest(version, Files.readAllBytes(archive));
        Files.write(directory.resolve(version + ".manifest"), manifest);
        Files.write(directory.resolve(version + ".ed25519"), sign(fixtureKey, manifest));
    }

    private static SignedCliBundle verified(Path directory, Path archive, PublicKey key,
            String keyId, String version) throws Exception {
        return SignedCliBundle.verify(archive, Files.readAllBytes(directory.resolve(version + ".manifest")),
                Files.readAllBytes(directory.resolve(version + ".ed25519")), key, keyId, version);
    }

    private static byte[] sign(KeyPair fixtureKey, byte[] manifest) throws Exception {
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(fixtureKey.getPrivate());
        signer.update(manifest);
        return signer.sign();
    }

    private static void reject(FixtureOperation operation) throws Exception {
        try {
            operation.run();
            throw new AssertionError("Unsafe operation succeeded");
        } catch (IOException | java.security.GeneralSecurityException expected) {
            // Rejected before installation or removal.
        }
    }

    @FunctionalInterface private interface FixtureOperation {
        void run() throws Exception;
    }
}
