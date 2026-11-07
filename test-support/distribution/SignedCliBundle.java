import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
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
    private final String version;
    private final Map<String, byte[]> files;

    private SignedCliBundle(String version, Map<String, byte[]> files) {
        this.version = version;
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
        try (java.io.InputStream input = Files.newInputStream(archive, LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readNBytes(LIMIT + 1);
        }
        if (bytes.length > LIMIT || !Arrays.equals(manifest, manifest(expectedVersion, bytes))) {
            throw new IOException("CLI bundle identity, platform or checksum differs from the signed manifest");
        }
        return new SignedCliBundle(expectedVersion, contents(expectedVersion, bytes));
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
        Files.createDirectory(destination);
        Files.createDirectory(destination.resolve("bin"));
        Files.createDirectory(destination.resolve("lib"));
        for (Map.Entry<String, byte[]> entry : files.entrySet()) {
            Files.write(destination.resolve(entry.getKey()), entry.getValue(), StandardOpenOption.CREATE_NEW);
        }
        if (System.getProperty("os.name").equalsIgnoreCase("Linux")) {
            Files.setPosixFilePermissions(destination.resolve("bin/tuprel"),
                    PosixFilePermissions.fromString("rwx------"));
        }
        return destination;
    }

    void uninstall(Path privateRoot) throws IOException {
        Path destination = checkedRoot(privateRoot).resolve(version);
        checkedRoot(destination);
        for (String subdirectory : List.of("bin", "lib")) {
            Path directory = checkedRoot(destination.resolve(subdirectory));
            Set<String> expected = new HashSet<>();
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                if (!file.getKey().startsWith(subdirectory + "/")) continue;
                Path installed = destination.resolve(file.getKey());
                expected.add(installed.getFileName().toString());
                if (!Files.isRegularFile(installed, LinkOption.NOFOLLOW_LINKS)
                        || Files.size(installed) != file.getValue().length
                        || !Arrays.equals(Files.readAllBytes(installed), file.getValue())) {
                    throw new IOException("Installed file differs from its authenticated archive");
                }
            }
            try (java.util.stream.Stream<Path> entries = Files.list(directory)) {
                if (!entries.map(path -> path.getFileName().toString()).allMatch(expected::contains)) {
                    throw new IOException("Refusing to remove additional files");
                }
            }
        }
        try (java.util.stream.Stream<Path> entries = Files.list(destination)) {
            if (!entries.map(path -> path.getFileName().toString()).allMatch(Set.of("bin", "lib")::contains)) {
                throw new IOException("Refusing to remove additional directories");
            }
        }
        for (String file : files.keySet()) {
            Files.delete(destination.resolve(file));
        }
        Files.delete(destination.resolve("bin"));
        Files.delete(destination.resolve("lib"));
        Files.delete(destination);
    }

    private static Path checkedRoot(Path directory) throws IOException {
        if (!directory.isAbsolute() || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                || !directory.equals(directory.toRealPath())) {
            throw new IOException("Expected an absolute private staging directory without aliases");
        }
        return directory;
    }
}
