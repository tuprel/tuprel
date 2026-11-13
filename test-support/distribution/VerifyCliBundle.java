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

/** Verifies and applies a signed bundle within an OS-validated private staging root. */
public final class VerifyCliBundle {
    private VerifyCliBundle() {}

    public static void main(String[] args) {
        if ((args.length != 6 && args.length != 9) || !args[5].matches("[0-9a-f]{64}")
                || (args.length == 9 && !args[6].equals("install") && !args[6].equals("remove"))) {
            System.err.println("Usage: verify-cli <version> <archive> <manifest> <signature>"
                    + " <public-key-der> <key-sha256> [install|remove <private-staging-root> <trusted-native-library>]");
            System.exit(2);
        }
        try {
            PublicKey key = KeyFactory.getInstance("Ed25519")
                    .generatePublic(new X509EncodedKeySpec(read(Path.of(args[4]), 128)));
            SignedCliBundle bundle = SignedCliBundle.verify(Path.of(args[1]), read(Path.of(args[2]), 512),
                    read(Path.of(args[3]), 64), key, args[5], args[0]);
            if (args.length == 6) {
                System.out.println("Signed Tuprel staging bundle verified");
            } else {
                StagingRoot.load(Path.of(args[8]));
                if (args[6].equals("install")) {
                    bundle.install(Path.of(args[7]));
                    System.out.println("Signed Tuprel staging version installed");
                } else {
                    bundle.uninstall(Path.of(args[7]));
                    System.out.println("Signed Tuprel staging version removed");
                }
            }
        } catch (IOException | GeneralSecurityException | InvalidPathException e) {
            System.err.println("Tuprel staging operation refused or incomplete. Inspect the private staging root.");
            System.exit(1);
        }
    }

    static byte[] read(Path path, int limit) throws IOException {
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
