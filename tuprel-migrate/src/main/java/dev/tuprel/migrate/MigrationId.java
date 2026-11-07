package dev.tuprel.migrate;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.Objects;
import java.util.Locale;

/**
 * A UTC calendar identifier in the portable {@code uuuuMMdd_HHmmss} format.
 * @param value canonical UTC calendar text
 */
public record MigrationId(String value) implements Comparable<MigrationId> {
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("uuuuMMdd_HHmmss", Locale.ROOT)
            .withResolverStyle(ResolverStyle.STRICT);

    /**
     * Rejects invalid dates, year zero, offsets and non-ASCII representations.
     * @param value canonical UTC calendar text
     */
    public MigrationId {
        Objects.requireNonNull(value, "value");
        if (!value.matches("[0-9]{8}_[0-9]{6}") || value.startsWith("0000")) {
            throw invalid();
        }
        try {
            LocalDateTime.parse(value, FORMAT);
        } catch (DateTimeException exception) {
            throw invalid();
        }
    }

    /** Calendar order is identical to lexical order for this fixed-width format. */
    @Override
    public int compareTo(MigrationId other) {
        return value.compareTo(other.value);
    }

    static MigrationId at(Instant instant) {
        return new MigrationId(FORMAT.withZone(ZoneOffset.UTC).format(instant));
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Migration ID must be a valid UTC uuuuMMdd_HHmmss date");
    }
}
