import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class SignedCliBundle {
    private static final int LIMIT = 32 * 1024 * 1024;
    private static final Pattern VERSION = Pattern.compile("[0-9]{1,4}\\.[0-9]{1,4}\\.[0-9]{1,4}-staging");
    private static final Pattern LIBRARY = Pattern.compile("lib/[A-Za-z0-9][A-Za-z0-9_.+-]{0,150}\\.jar");
    private static final Pattern WINDOWS_DEVICE = Pattern.compile("(?i)(?:CON|PRN|AUX|NUL|COM[0-9]|LPT[0-9])(?:\\..*)?");
    private static final Set<String> LAUNCHERS = Set.of("bin/tuprel", "bin/tuprel.bat");
    private static final String RECEIPT = ".bundle-sha256";
    private final String version;
    private final byte[] receipt;
    private final Map<String, byte[]> files;

    private SignedCliBundle(String version, String manifestSha256, Map<String, byte[]> files) {
        this.version = version;
        this.receipt = (manifestSha256 + "\n").getBytes(StandardCharsets.US_ASCII);
        this.files = Map.copyOf(files);
    }

    static SignedCliBundle verify(Path archive, byte[] manifest, byte[] signature, PublicKey trustedKey,
            String pinnedKeySha256, String expectedVersion) throws IOException, GeneralSecurityException {
        if (!VERSION.matcher(expectedVersion).matches() || manifest.length > 512 || signature.length != 64
                || !sha256(trustedKey.getEncoded()).equals(pinnedKeySha256)) {
            throw new IOException("Invalid version, signature envelope or trusted key");
        }
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(trustedKey);
        verifier.update(manifest);
        if (!verifier.verify(signature)) {
            throw new IOException("Invalid CLI bundle signature");
        }

        byte[] bytes;
        if (!Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Expected a regular staging archive");
        }
        try (java.io.InputStream input = Files.newInputStream(archive, LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readNBytes(LIMIT + 1);
        }
        if (bytes.length > LIMIT || !Arrays.equals(manifest, manifest(expectedVersion, bytes))) {
            throw new IOException("CLI bundle identity, platform or checksum differs from the signed manifest");
        }
        return new SignedCliBundle(expectedVersion, sha256(manifest), contents(expectedVersion, bytes));
    }

    static byte[] manifest(String version, byte[] bytes) throws IOException, GeneralSecurityException {
        String name = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        String platform = name.startsWith("windows") ? "windows" : name.equals("linux") ? "linux" : "";
        if (platform.isEmpty() || !Set.of("amd64", "x86_64").contains(arch)) {
            throw new IOException("Unverified staging platform");
        }
        return ("TUPREL-CLI-STAGING 1\n" + version + "\n" + platform + "-x86_64\n" + sha256(bytes) + "\n")
                .getBytes(StandardCharsets.US_ASCII);
    }

    static String sha256(byte[] bytes) throws GeneralSecurityException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static Map<String, byte[]> contents(String version, byte[] bytes) throws IOException {
        String root = "tuprel-" + version + "/";
        Map<String, byte[]> verified = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        int remaining = LIMIT;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (seen.size() >= 128 || !name.startsWith(root) || !seen.add(name.toLowerCase(Locale.ROOT))) {
                    throw new IOException("Unexpected, repeated or excessive ZIP entries");
                }
                String relative = name.substring(root.length());
                if (entry.isDirectory()) {
                    if (!Set.of("", "bin/", "lib/").contains(relative) || zip.read() != -1) {
                        throw new IOException("Unexpected ZIP directory");
                    }
                } else {
                    if (!LAUNCHERS.contains(relative) && (!LIBRARY.matcher(relative).matches()
                            || WINDOWS_DEVICE.matcher(relative.substring(4)).matches())) {
                        throw new IOException("Unexpected ZIP file path");
                    }
                    byte[] content = zip.readNBytes(remaining + 1);
                    remaining -= content.length;
                    if (remaining < 0) {
                        throw new IOException("ZIP contents exceed the staging limit");
                    }
                    verified.put(relative, content);
                }
            }
        }
        if (!verified.keySet().containsAll(LAUNCHERS) || !verified.containsKey("lib/tuprel-cli-" + version + ".jar")) {
            throw new IOException("Incomplete Tuprel CLI archive");
        }
        return verified;
    }

    Path install(Path privateRoot) throws IOException {
        Path root = checkedRoot(privateRoot);
        Path destination = root.resolve(version);
        try (FileChannel channel = openLock(root); FileLock lock = channel.lock()) {
            if (!lock.isValid()) throw new IOException("Staging lock is invalid");
            if (present(removalPath(root))) {
                throw new IOException("Resume the interrupted removal before installing this version");
            }
            if (present(destination)) {
                inspectVersion(destination, false);
                return destination;
            }
            // Each attempt writes to a fresh sibling. Only a complete tree becomes selectable.
            Path pending = Files.createTempDirectory(root, "." + version + "-pending-");
            Files.createDirectory(pending.resolve("bin"));
            Files.createDirectory(pending.resolve("lib"));
            for (Map.Entry<String, byte[]> entry : files.entrySet()) {
                Path output = pending.resolve(entry.getKey()).normalize();
                if (!output.startsWith(pending)) {
                    throw new IOException("ZIP file path escapes the private staging directory");
                }
                Files.write(output, entry.getValue(), StandardOpenOption.CREATE_NEW);
            }
            if (System.getProperty("os.name").equalsIgnoreCase("Linux")) {
                Files.setPosixFilePermissions(pending.resolve("bin/tuprel"),
                        PosixFilePermissions.fromString("rwx------"));
            }
            Files.write(pending.resolve(RECEIPT), receipt, StandardOpenOption.CREATE_NEW);
            inspectVersion(pending, false);
            return Files.move(pending, destination, StandardCopyOption.ATOMIC_MOVE);
        }
    }

    void uninstall(Path privateRoot) throws IOException {
        Path root = checkedRoot(privateRoot);
        Path destination = root.resolve(version);
        Path removing = removalPath(root);
        try (FileChannel channel = openLock(root); FileLock lock = channel.lock()) {
            if (!lock.isValid()) throw new IOException("Staging lock is invalid");
            if (present(destination)) {
                if (present(removing)) throw new IOException("Conflicting installation and removal directories");
                inspectVersion(destination, false);
                Files.move(destination, removing, StandardCopyOption.ATOMIC_MOVE);
            }
            if (present(removing)) {
                // Validate every surviving entry before deleting any of them.
                List<Path> verified = inspectVersion(removing, true);
                for (Path entry : verified) {
                    Files.delete(entry);
                }
                Files.delete(removing);
            }
        }
    }

    private Path removalPath(Path root) {
        return root.resolve("." + version + "-removing");
    }

    private static boolean present(Path path) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return true;
        if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) return false;
        throw new IOException("Cannot determine installation state");
    }

    private static FileChannel openLock(Path root) throws IOException {
        return FileChannel.open(root.resolve(".tuprel-staging.lock"), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
    }

    private List<Path> inspectVersion(Path directory, boolean allowMissing) throws IOException {
        checkedRoot(directory);
        List<Path> verified = new ArrayList<>();
        Path marker = directory.resolve(RECEIPT);
        boolean hasReceipt = present(marker);
        if (hasReceipt) {
            verifyInstalledBytes(marker, receipt);
        }
        int fileCount = 0;
        try (DirectoryStream<Path> folders = Files.newDirectoryStream(directory)) {
            for (Path folder : folders) {
                String name = folder.getFileName().toString();
                if (name.equals(RECEIPT)) continue;
                if (!hasReceipt || !Set.of("bin", "lib").contains(name)) {
                    throw new IOException("Installation contains an unknown directory or file");
                }
                checkedRoot(folder);
                try (DirectoryStream<Path> entries = Files.newDirectoryStream(folder)) {
                    for (Path entry : entries) {
                        byte[] expected = files.get(name + "/" + entry.getFileName());
                        if (expected == null) throw new IOException("Installation contains an unknown file");
                        verifyInstalledBytes(entry, expected);
                        verified.add(entry);
                        fileCount++;
                    }
                }
                verified.add(folder);
            }
        }
        if (!allowMissing && (!hasReceipt || fileCount != files.size())) {
            throw new IOException("Installation is missing authenticated files");
        }
        // Remove the receipt last. A receipt-free removal directory can only be empty.
        if (hasReceipt) verified.add(marker);
        return verified;
    }

    private static void verifyInstalledBytes(Path file, byte[] expected) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Installation contains a non-regular file or link");
        }
        try (java.io.InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            if (!Arrays.equals(expected, input.readNBytes(expected.length + 1))) {
                throw new IOException("Installed file differs from its authenticated archive");
            }
        }
    }

    private static Path checkedRoot(Path directory) throws IOException {
        if (!directory.isAbsolute() || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                || !directory.equals(directory.toRealPath())) {
            throw new IOException("Expected an absolute private staging directory without aliases");
        }
        return directory;
    }
}
