import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
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
        if (args[0].equals("install")) {
            KeyPair fixtureKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            byte[] publicKey = fixtureKey.getPublic().getEncoded();
            Files.write(privateRoot.resolve("public-key.bin"), publicKey);
            String keyId = SignedCliBundle.sha256(publicKey);
            signBundle(privateRoot, firstZip, FIRST, fixtureKey);
            signBundle(privateRoot, secondZip, SECOND, fixtureKey);
            assertRejectsUntrustedInputs(privateRoot, firstZip, secondZip, fixtureKey, keyId);
            SignedCliBundle first = verified(privateRoot, firstZip, fixtureKey.getPublic(), keyId, FIRST);
            Path interrupted = Files.createTempDirectory(privateRoot, "." + FIRST + "-pending-");
            Path partial = Files.writeString(interrupted.resolve("partial"), "interrupted attempt");
            first.install(privateRoot);
            if (!Files.readString(partial).equals("interrupted attempt")) {
                throw new AssertionError("A new install must leave interrupted attempts available for inspection");
            }
            try {
                first.install(privateRoot);
                throw new AssertionError("Replacing an existing version must fail");
            } catch (FileAlreadyExistsException expected) {
                // The old installation is used again after staging the next version.
            }
            verified(privateRoot, secondZip, fixtureKey.getPublic(), keyId, SECOND).install(privateRoot);
            System.out.println(keyId);
        } else if (args[0].equals("uninstall")) {
            PublicKey trustedKey = KeyFactory.getInstance("Ed25519").generatePublic(
                    new X509EncodedKeySpec(Files.readAllBytes(privateRoot.resolve("public-key.bin"))));
            SignedCliBundle first = verified(privateRoot, firstZip, trustedKey, args[4], FIRST);
            SignedCliBundle second = verified(privateRoot, secondZip, trustedKey, args[4], SECOND);
            Path extra = Files.writeString(privateRoot.resolve(FIRST + "/bin/local-file"), "preserve");
            reject(() -> first.uninstall(privateRoot));
            if (!Files.readString(extra).equals("preserve")) throw new AssertionError("Unexpected file was removed");
            Files.delete(extra);
            Path launcher = privateRoot.resolve(FIRST + "/bin/tuprel");
            byte[] original = Files.readAllBytes(launcher);
            Files.writeString(launcher, "tampered launcher");
            reject(() -> first.uninstall(privateRoot));
            if (!Files.readString(launcher).equals("tampered launcher")) throw new AssertionError("Changed file was removed");
            Files.write(launcher, original);
            first.uninstall(privateRoot);
            if (Files.exists(privateRoot.resolve(FIRST)) || !Files.isDirectory(privateRoot.resolve(SECOND))) {
                throw new AssertionError("Uninstall must remove only the selected version");
            }
            second.uninstall(privateRoot);
        } else {
            throw new IllegalArgumentException("Expected install or uninstall");
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
        Path traversal = directory.resolve("traversal.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(traversal))) {
            zip.putNextEntry(new ZipEntry("tuprel-" + FIRST + "/bin/../../outside"));
            zip.write(1);
        }
        byte[] hostile = SignedCliBundle.manifest(FIRST, Files.readAllBytes(traversal));
        reject(() -> SignedCliBundle.verify(traversal, hostile, sign(fixtureKey, hostile), publicKey, keyId, FIRST));
        Files.delete(traversal);
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
