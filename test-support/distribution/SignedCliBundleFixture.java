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
