package dev.tuprel.migrate;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Lowercase SHA-256 of exact file bytes; no newline or Unicode normalization.
 * @param value 64 lowercase hexadecimal characters
 */
public record MigrationChecksum(String value) {
    /**
     * Validates an externally stored checksum without accepting alternate spellings.
     * @param value 64 lowercase hexadecimal characters
     */
    public MigrationChecksum {
        Objects.requireNonNull(value, "value");
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Migration checksum must be 64 lowercase hexadecimal characters");
        }
    }

    /**
     * Hashes bytes without retaining or modifying the supplied array.
     * @param bytes exact input bytes
     * @return their SHA-256 checksum
     */
    public static MigrationChecksum of(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        try {
            return new MigrationChecksum(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", exception);
        }
    }
}
