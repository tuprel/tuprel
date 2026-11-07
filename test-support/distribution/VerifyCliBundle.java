import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;

/** Verifies a staging bundle using a public key and an independently supplied fingerprint. */
public final class VerifyCliBundle {
    private VerifyCliBundle() {}

    public static void main(String[] args) {
        if (args.length != 6 || !args[5].matches("[0-9a-f]{64}")) {
            System.err.println("Usage: verify-cli <version> <archive> <manifest> <signature> <public-key-der> <key-sha256>");
            System.exit(2);
        }
        try {
            PublicKey key = KeyFactory.getInstance("Ed25519")
                    .generatePublic(new X509EncodedKeySpec(read(Path.of(args[4]), 128)));
            SignedCliBundle.verify(Path.of(args[1]), read(Path.of(args[2]), 512),
                    read(Path.of(args[3]), 64), key, args[5], args[0]);
            System.out.println("Signed Tuprel staging bundle verified");
        } catch (IOException | GeneralSecurityException | InvalidPathException e) {
            System.err.println("Tuprel staging bundle verification failed");
            System.exit(1);
        }
    }

    private static byte[] read(Path path, int limit) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Expected a regular verification input");
        }
        try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(limit + 1);
            if (bytes.length == 0 || bytes.length > limit) {
                throw new IOException("Verification input exceeds its size bounds");
            }
            return bytes;
        }
    }
}
