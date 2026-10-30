package dev.tuprel.migrate;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Immutable snapshot of a migration file. Format validity and checksum do not
 * imply valid SQL, transactional safety or approval to execute it.
 */
public final class MigrationFile {
    /** Maximum SQL file size in format v1 (one MiB). */
    public static final int MAX_SQL_BYTES = 1024 * 1024;
    private final MigrationId id;
    private final String name;
    private final String sql;
    private final MigrationChecksum checksum;

    /**
     * Copies a UTF-8/LF snapshot; rejects BOM, NUL, CR, blank files and missing
     * final LF. Names use lowercase ASCII words separated by underscores/hyphens.
     * @param id canonical UTC identity
     * @param name portable migration name
     * @param bytes exact UTF-8/LF file bytes, defensively copied
     */
    public MigrationFile(MigrationId id, String name, byte[] bytes) {
        this.id = Objects.requireNonNull(id, "id");
        this.name = Objects.requireNonNull(name, "name");
        Objects.requireNonNull(bytes, "bytes");
        validateName(name);
        if (bytes.length == 0 || bytes.length > MAX_SQL_BYTES) {
            throw new IllegalArgumentException("Migration SQL must contain 1-1048576 bytes");
        }
        byte[] snapshot = bytes.clone();
        try {
            sql = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(snapshot)).toString();
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("Migration SQL must be valid UTF-8");
        }
        if (sql.startsWith("\uFEFF") || sql.indexOf('\r') >= 0 || sql.indexOf('\0') >= 0
                || sql.isBlank() || !sql.endsWith("\n")) {
            throw new IllegalArgumentException("Migration SQL requires UTF-8 without BOM, LF lines and final LF; no NUL");
        }
        checksum = MigrationChecksum.of(snapshot);
    }

    /**
     * Builds a snapshot from a single directory basename, never a relative path.
     * @param directoryName canonical ID and name separated by underscore
     * @param bytes exact UTF-8/LF file bytes
     * @return immutable file snapshot
     */
    public static MigrationFile fromDirectory(String directoryName, byte[] bytes) {
        validateDirectoryName(directoryName);
        return new MigrationFile(new MigrationId(directoryName.substring(0, 15)), directoryName.substring(16), bytes);
    }

    static void validateDirectoryName(String directoryName) {
        Objects.requireNonNull(directoryName, "directoryName");
        if (directoryName.length() < 17 || directoryName.charAt(15) != '_') {
            throw new IllegalArgumentException("Migration directory must be uuuuMMdd_HHmmss_name");
        }
        new MigrationId(directoryName.substring(0, 15));
        validateName(directoryName.substring(16));
    }

    private static void validateName(String name) {
        if (name.length() > 64 || !name.matches("[a-z][a-z0-9]*(?:[_-][a-z0-9]+)*")) {
            throw new IllegalArgumentException("Migration name must be 1-64 portable lowercase ASCII characters");
        }
    }

    /**
     * Identifier used for ordering and uniqueness.
     * @return canonical UTC ID
     */
    public MigrationId id() { return id; }

    /**
     * Portable, validated developer-supplied name.
     * @return migration name
     */
    public String name() { return name; }

    /**
     * Exact unreviewed SQL snapshot.
     * @return SQL content, without any safety classification
     */
    public String sql() { return sql; }

    /**
     * SHA-256 of the exact accepted bytes.
     * @return exact checksum
     */
    public MigrationChecksum checksum() { return checksum; }

    /**
     * Canonical directory basename.
     * @return ID and name separated by underscore
     */
    public String directoryName() { return id.value() + "_" + name; }

    /** Deliberately excludes SQL, which may contain sensitive values. */
    @Override
    public String toString() {
        return "MigrationFile[" + directoryName() + ", sha256=" + checksum.value() + "]";
    }
}
