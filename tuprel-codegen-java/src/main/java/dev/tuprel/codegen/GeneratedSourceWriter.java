package dev.tuprel.codegen;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Writes only Tuprel-owned generated files beneath one output root. */
public final class GeneratedSourceWriter {
    private static final String MANIFEST = ".tuprel-generated";

    /** Whether generated files exactly match the desired sources and ownership manifest. */
    public boolean isCurrent(Path outputRoot, GeneratedJavaSources sources) throws IOException {
        State state = inspect(outputRoot, sources, false);
        if (state.modified() || !state.current().equals(state.desired())) {
            return false;
        }
        for (Map.Entry<String, String> entry : state.desired().entrySet()) {
            Path target = state.root().resolve(entry.getKey());
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                    || !entry.getValue().equals(hash(Files.readAllBytes(target)))) {
                return false;
            }
        }
        return Files.isRegularFile(state.root().resolve(MANIFEST), LinkOption.NOFOLLOW_LINKS);
    }

    /** Regenerates owned files and removes only obsolete, unmodified owned files. */
    public void write(Path outputRoot, GeneratedJavaSources sources) throws IOException {
        State state = inspect(outputRoot, sources, true);
        for (String old : state.current().keySet()) {
            if (!state.desired().containsKey(old)) {
                Files.delete(state.root().resolve(old));
            }
        }
        Files.createDirectories(state.root());
        for (Map.Entry<String, String> entry : sources.files().entrySet()) {
            Path target = state.root().resolve(entry.getKey());
            Files.createDirectories(target.getParent());
            byte[] desired = entry.getValue().getBytes(StandardCharsets.UTF_8);
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                    || !entry.getValue().equals(Files.readString(target, StandardCharsets.UTF_8))) {
                Files.write(target, desired);
            }
        }
        StringBuilder manifest = new StringBuilder("# Tuprel generated files; do not edit.\n");
        state.desired().forEach((path, checksum) ->
                manifest.append(path).append('\t').append(checksum).append('\n'));
        Files.writeString(state.root().resolve(MANIFEST), manifest.toString(), StandardCharsets.UTF_8);
    }

    private static State inspect(Path outputRoot, GeneratedJavaSources sources, boolean failOnEdit)
            throws IOException {
        Objects.requireNonNull(outputRoot, "outputRoot");
        Objects.requireNonNull(sources, "sources");
        Path root = outputRoot.toAbsolutePath().normalize();
        checkAncestors(root);
        Set<String> folded = new HashSet<>();
        Map<String, String> desired = new TreeMap<>();
        for (Map.Entry<String, String> entry : sources.files().entrySet()) {
            validateRelative(entry.getKey(), root, folded);
            desired.put(entry.getKey(), hash(entry.getValue().getBytes(StandardCharsets.UTF_8)));
        }
        Map<String, String> current = readManifest(root);
        boolean modified = false;
        for (Map.Entry<String, String> entry : current.entrySet()) {
            Path target = root.resolve(entry.getKey());
            checkAncestors(target);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                    && !entry.getValue().equals(hash(Files.readAllBytes(target)))) {
                if (failOnEdit) {
                    throw new IOException("Generated file was edited: " + entry.getKey());
                }
                modified = true;
            }
        }
        for (String path : desired.keySet()) {
            Path target = root.resolve(path);
            checkAncestors(target);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !current.containsKey(path)) {
                throw new IOException("Refusing to overwrite a file not owned by Tuprel: " + path);
            }
        }
        return new State(root, current, desired, modified);
    }

    private static Map<String, String> readManifest(Path root) throws IOException {
        Path manifest = root.resolve(MANIFEST);
        if (!Files.exists(manifest, LinkOption.NOFOLLOW_LINKS)) {
            return Map.of();
        }
        if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Generated-file manifest is not a regular file");
        }
        Map<String, String> previous = new TreeMap<>();
        Set<String> folded = new HashSet<>();
        List<String> lines = Files.readAllLines(manifest, StandardCharsets.UTF_8);
        for (String line : lines) {
            if (line.startsWith("#") || line.isEmpty()) {
                continue;
            }
            String[] parts = line.split("\t", -1);
            if (parts.length != 2 || !parts[1].matches("[0-9a-f]{64}")) {
                throw new IOException("Invalid generated-file manifest entry");
            }
            validateRelative(parts[0], root, folded);
            if (previous.put(parts[0], parts[1]) != null) {
                throw new IOException("Duplicate generated-file manifest entry");
            }
        }
        return previous;
    }

    private static void validateRelative(String value, Path root, Set<String> folded)
            throws IOException {
        if (value.isEmpty() || value.equals(MANIFEST) || value.startsWith("/")
                || value.contains("\\") || value.contains(":")
                || !value.matches("[A-Za-z_][A-Za-z0-9_]*(/[A-Za-z_][A-Za-z0-9_]*)*/[A-Za-z_][A-Za-z0-9_]*\\.java")) {
            throw new IOException("Unsafe generated source path: " + value);
        }
        Path relative = Path.of(value);
        if (relative.isAbsolute()) {
            throw new IOException("Absolute generated source path: " + value);
        }
        for (Path part : relative) {
            if (part.toString().equals("..") || part.toString().equals(".")) {
                throw new IOException("Traversal in generated source path: " + value);
            }
            String name = part.toString().replaceFirst("\\..*$", "").toUpperCase(Locale.ROOT);
            if (name.matches("CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]")) {
                throw new IOException("Reserved filesystem name in generated source path: " + value);
            }
        }
        if (!root.resolve(relative).normalize().startsWith(root)
                || !folded.add(value.toLowerCase(Locale.ROOT))) {
            throw new IOException("Generated source path escapes or collides: " + value);
        }
    }

    private static void checkAncestors(Path path) throws IOException {
        List<Path> ancestors = new ArrayList<>();
        Path current = path;
        while (current != null) {
            ancestors.add(current);
            current = current.getParent();
        }
        for (Path ancestor : ancestors) {
            if (Files.isSymbolicLink(ancestor)) {
                throw new IOException("Symlink in generated output path: " + ancestor);
            }
        }
    }

    private static String hash(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(Character.forDigit((value >>> 4) & 15, 16));
                hex.append(Character.forDigit(value & 15, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", exception);
        }
    }

    private record State(Path root, Map<String, String> current, Map<String, String> desired,
            boolean modified) {}
}
