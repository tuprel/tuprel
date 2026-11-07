package dev.tuprel.migrate.postgresql;

import com.sun.security.auth.module.UnixSystem;
import dev.tuprel.migrate.MigrationChecksum;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.FileSystems;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.CodeSource;
import java.util.Map;
import java.util.Objects;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;

/** Provisioned by the operator in the trusted supervisor, outside the requesting project. */
record ManagedExecutorPin(Path jar, MigrationChecksum checksum, String artifactVersion, String domain, int version,
                          long supervisorUid) {
    private static final int MAX_JAR_BYTES = 16 * 1024 * 1024;
    private static final String ARTIFACT = "dev.tuprel:tuprel-migrate-postgresql";

    ManagedExecutorPin {
        Objects.requireNonNull(jar, "jar");
        Objects.requireNonNull(checksum, "checksum");
        Objects.requireNonNull(artifactVersion, "artifactVersion");
        Objects.requireNonNull(domain, "domain");
        if (supervisorUid < 0 || supervisorUid > 0xffff_ffffL) { throw new IllegalArgumentException("Invalid supervisor UID"); }
    }

    boolean verifiesRunningExecutor() {
        if (!domain.equals(PhysicalDeployDigest.DOMAIN) || version != PhysicalDeployDigest.VERSION || !jar.isAbsolute()) {
            return false;
        }
        try {
            if (!jar.equals(origin(MigrationRunner.class)) || !jar.equals(origin(ManagedCredentialIssuer.class))
                    || !jar.toRealPath().toString().equals(jar.toString())) { return false; }
            BasicFileAttributes before = Files.readAttributes(jar, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!before.isRegularFile() || before.size() < 1 || before.size() > MAX_JAR_BYTES) { return false; }
            byte[] bytes;
            try (InputStream input = Files.newInputStream(jar, LinkOption.NOFOLLOW_LINKS)) {
                bytes = input.readNBytes(MAX_JAR_BYTES + 1);
            }
            BasicFileAttributes after = Files.readAttributes(jar, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return after.isRegularFile() && bytes.length == before.size() && bytes.length == after.size()
                    && Objects.equals(before.fileKey(), after.fileKey()) && before.lastModifiedTime().equals(after.lastModifiedTime())
                    && checksum.equals(MigrationChecksum.of(bytes)) && metadataMatches(bytes);
        } catch (IOException | URISyntaxException | IllegalArgumentException | SecurityException failure) { return false; }
    }

    /** The supervisor's OS account must be distinct from project writers and generic jobs. */
    boolean protectedInstallation() { return protectedFile(jar); }

    boolean protectedFile(Path file) {
        if (!file.isAbsolute() || !file.equals(file.normalize())
                || !FileSystems.getDefault().supportedFileAttributeViews().contains("unix")) { return false; }
        try {
            long uid = new UnixSystem().getUid();
            if (uid != supervisorUid) { return false; }
            for (Path entry = file; entry != null; entry = entry.getParent()) {
                Map<String, Object> attributes = Files.readAttributes(entry, "unix:uid,mode,isDirectory,isRegularFile,nlink",
                        LinkOption.NOFOLLOW_LINKS);
                int owner = (Integer) attributes.get("uid");
                int mode = (Integer) attributes.get("mode");
                boolean expectedType = entry.equals(file)
                        ? Boolean.TRUE.equals(attributes.get("isRegularFile")) && Integer.valueOf(1).equals(attributes.get("nlink"))
                        : Boolean.TRUE.equals(attributes.get("isDirectory"));
                if (!expectedType || (owner != 0 && Integer.toUnsignedLong(owner) != uid) || (mode & 0022) != 0) {
                    return false;
                }
            }
            return true;
        } catch (IOException | RuntimeException | LinkageError failure) { return false; }
    }

    private boolean metadataMatches(byte[] bytes) throws IOException {
        try (JarInputStream artifact = new JarInputStream(new ByteArrayInputStream(bytes))) {
            Manifest manifest = artifact.getManifest();
            return manifest != null && ARTIFACT.equals(manifest.getMainAttributes().getValue("Tuprel-Managed-Artifact"))
                    && artifactVersion.equals(manifest.getMainAttributes().getValue("Implementation-Version"));
        }
    }

    private static Path origin(Class<?> type) throws URISyntaxException {
        CodeSource source = type.getProtectionDomain().getCodeSource();
        if (source == null || !"file".equals(source.getLocation().getProtocol())) {
            throw new IllegalArgumentException("Executor has no local artifact");
        }
        return Path.of(source.getLocation().toURI());
    }
}
