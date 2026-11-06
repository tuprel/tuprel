package dev.tuprel.migrate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Internal trust pin. The installer controls the binary directory and supplies the trusted key. */
final class NativePublisherInstallation {
    private static final int MAX_BINARY_BYTES = 8 * 1024 * 1024;
    private static final int MAX_MANIFEST_BYTES = 160;
    private static final int SIGNATURE_BYTES = 64;
    private static final Pattern MANIFEST = Pattern.compile(
            "TUPREL-NATIVE-PUBLISHER 2\nos (linux|windows)\narch x86_64\nsha256 ([a-f0-9]{64})\n");
    private final Path executable;
    private final byte[] manifest;
    private final byte[] signature;
    private final PublicKey issuer;

    NativePublisherInstallation(Path executable, byte[] manifest, byte[] signature, PublicKey issuer) {
        if (manifest.length > MAX_MANIFEST_BYTES || signature.length != SIGNATURE_BYTES) {
            throw new IllegalArgumentException("Invalid manifest envelope");
        }
        this.executable = Objects.requireNonNull(executable, "executable");
        this.manifest = manifest.clone();
        this.signature = signature.clone();
        this.issuer = Objects.requireNonNull(issuer, "issuer");
    }

    static NativePublisherInstallation load(Path directory, PublicKey issuer) throws IOException {
        if (!directory.isAbsolute()) { throw refused(); }
        Path trusted = MigrationDirectory.validatedRoot(directory);
        String binary = platform().startsWith("windows") ? "publisher.exe" : "publisher";
        byte[] signature = MigrationDirectory.readRegular(trusted.resolve("manifest.ed25519"), SIGNATURE_BYTES);
        if (signature.length != SIGNATURE_BYTES) { throw refused(); }
        return new NativePublisherInstallation(trusted.resolve(binary),
                MigrationDirectory.readRegular(trusted.resolve("manifest.txt"), MAX_MANIFEST_BYTES), signature, issuer);
    }

    Path verify(Path project) throws IOException {
        Matcher match = MANIFEST.matcher(new String(manifest, StandardCharsets.US_ASCII));
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (!match.matches() || !platform().startsWith(match.group(1)) || (!arch.equals("amd64") && !arch.equals("x86_64"))
                || !authenticated() || !executable.isAbsolute() || executable.startsWith(project)
                || executable.getParent() == null) { throw refused(); }
        Path parent = MigrationDirectory.validatedRoot(executable.getParent());
        Path path = parent.resolve(executable.getFileName());
        BasicFileAttributes before = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!before.isRegularFile() || before.size() > MAX_BINARY_BYTES
                || !path.toString().equals(executable.toString()) || !path.toRealPath().toString().equals(path.toString())
                || !Files.isExecutable(path)) { throw refused(); }
        byte[] bytes;
        try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readNBytes(MAX_BINARY_BYTES + 1);
        }
        BasicFileAttributes after = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!after.isRegularFile() || bytes.length > MAX_BINARY_BYTES
                || bytes.length != before.size() || bytes.length != after.size()
                || !Objects.equals(before.fileKey(), after.fileKey()) || !before.lastModifiedTime().equals(after.lastModifiedTime())
                || !MigrationChecksum.of(bytes).value().equals(match.group(2))) { throw refused(); }
        return path;
    }

    private static String platform() { return System.getProperty("os.name", "").toLowerCase(Locale.ROOT); }

    private boolean authenticated() throws IOException {
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(issuer);
            verifier.update(manifest);
            return verifier.verify(signature);
        } catch (GeneralSecurityException failure) {
            throw refused();
        }
    }

    private static IOException refused() { return new IOException("Native publisher verification refused"); }
}
