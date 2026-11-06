package dev.tuprel.cli;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

/** Bounded UTF-8 schema input shared by migration comparison commands. */
final class MigrationSchemaInput {
    private static final int MAX_SCHEMA_BYTES = 1024 * 1024;

    private MigrationSchemaInput() { }

    static String read(Path root, Path requested) throws IOException {
        for (Path component : requested) { if (component.toString().equals("..")) { throw new IOException("Traversal"); } }
        Path path = root.resolve(requested).toAbsolutePath().normalize();
        Path current = path.getRoot();
        for (Path component : path) {
            current = current.resolve(component);
            BasicFileAttributes attributes = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink() || attributes.isOther()) { throw new IOException("Linked input"); }
        }
        BasicFileAttributes before = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!before.isRegularFile() || before.size() > MAX_SCHEMA_BYTES || !path.toRealPath().equals(path)) { throw new IOException("Invalid input"); }
        byte[] bytes;
        try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) { bytes = input.readNBytes(MAX_SCHEMA_BYTES + 1); }
        BasicFileAttributes after = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (bytes.length > MAX_SCHEMA_BYTES || bytes.length != before.size() || !after.isRegularFile() || !java.util.Objects.equals(before.fileKey(), after.fileKey())
                || before.size() != after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime())) { throw new IOException("Input changed"); }
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
    }
}
