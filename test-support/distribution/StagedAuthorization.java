import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Verifies the existing signed staging authorization before interpreting entries. */
final class StagedAuthorization {
    private static final String HEADER = "TUPREL-STAGING-AUTHORIZATION 1";

    private StagedAuthorization() {}

    static PublicKey readPinnedKey(Path catalogue, String pin) throws IOException, GeneralSecurityException {
        PublicKey key = KeyFactory.getInstance("Ed25519").generatePublic(
                new X509EncodedKeySpec(VerifyCliBundle.read(catalogue.resolve("public-key.bin"), 128)));
        if (!SignedCliBundle.sha256(key.getEncoded()).equals(pin)) throw new IOException("Untrusted key");
        return key;
    }

    static Map<String, Grant> read(Path catalogue, PublicKey key) throws IOException, GeneralSecurityException {
        byte[] policy = VerifyCliBundle.read(catalogue.resolve("authorization"), 8192);
        byte[] signature = VerifyCliBundle.read(catalogue.resolve("authorization.ed25519"), 64);
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(key);
        verifier.update(policy);
        if (!verifier.verify(signature)) throw new IOException("Invalid staging authorization signature");

        String[] lines = new String(policy, StandardCharsets.US_ASCII).split("\n", -1);
        if (lines.length < 3 || !lines[0].equals(HEADER) || !lines[lines.length - 1].isEmpty()) {
            throw new IOException("Invalid staging authorization");
        }
        Map<String, Grant> result = new HashMap<>();
        for (int i = 1; i < lines.length - 1; i++) {
            String[] fields = lines[i].split(" ", -1);
            if (fields.length != 3 || !SignedCliBundle.VERSION.matcher(fields[0]).matches()
                    || !fields[1].matches("[0-9a-f]{64}")
                    || !List.of("allow", "rollback", "revoked").contains(fields[2])
                    || result.putIfAbsent(fields[0], new Grant(fields[1], fields[2])) != null) {
                throw new IOException("Invalid staging authorization entry");
            }
        }
        return Map.copyOf(result);
    }

    record Grant(String digest, String mode) {}
}
