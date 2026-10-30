package dev.tuprel.migrate;

import java.io.IOException;
import java.util.Objects;

/** A safe catalog diagnostic: messages never contain SQL, paths or file contents. */
public final class MigrationValidationException extends IOException {
    private static final long serialVersionUID = 1L;

    /** Stable file-validation categories; these do not describe database states. */
    public enum Reason {
        /** Invalid, linked, aliased or non-directory root. */
        INVALID_ROOT,
        /** Missing or unknown format marker. */
        UNSUPPORTED_FORMAT,
        /** Unexpected, non-regular, linked or invalidly named entry. */
        INVALID_ENTRY,
        /** Two directory names contain the same migration ID. */
        DUPLICATE_ID,
        /** SQL bytes violate the file format, not an SQL grammar. */
        INVALID_SQL,
        /** Count or byte budget exceeded. */
        RESOURCE_LIMIT,
        /** A file changed while it was being read. */
        CHANGED_DURING_READ,
        /** Underlying filesystem I/O failed. */
        IO_ERROR
    }

    /**
     * Validation category stored with the exception.
     * @serial stable file-validation category
     */
    private final Reason reason;

    MigrationValidationException(Reason reason) {
        super("TUPREL-MIGRATE-FILES-" + reason.name() + ": inspect migration format, paths and file permissions");
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    MigrationValidationException(IOException cause) {
        this(Reason.IO_ERROR);
        initCause(cause);
    }

    /**
     * Machine-readable reason, independent of wording.
     * @return validation reason
     */
    public Reason reason() { return reason; }
}
