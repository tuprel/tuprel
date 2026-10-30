package dev.tuprel.migrate;

import java.io.IOException;

/** Safe offline creation failure. Causes are for callers, never normal CLI output. */
public final class MigrationCreationException extends IOException {
    private static final long serialVersionUID = 1L;

    /** Stable creation failure categories; no raw name, SQL or filesystem path is included. */
    public enum Reason {
        /** Name does not satisfy the approved canonical format. */
        INVALID_NAME,
        /** The generated timestamp ID already exists, regardless of its name. */
        COLLISION,
        /** Clock value is invalid or precedes an existing migration. */
        CLOCK_ORDER,
        /** Another cooperating creator owns the lock. */
        BUSY,
        /** The new draft would exceed the catalog budget. */
        CAPACITY,
        /** A coordination path is not an empty canonical regular file. */
        UNSAFE_LOCK,
        /** Creation allocated a directory; inspect it before retrying. */
        PARTIAL_CREATION,
        /** An I/O or permissions failure prevented creation. */
        IO_ERROR
    }

    /**
     * Safe category independent of the original I/O message.
     * @serial Creation failure category.
     */
    private final Reason reason;

    MigrationCreationException(Reason reason) {
        this(reason, null);
    }

    MigrationCreationException(Reason reason, Throwable cause) {
        super("TUPREL-MIGRATE-CREATE-" + reason + ": " + hint(reason), cause);
        this.reason = reason;
    }

    /**
     * Returns the safe failure category.
     * @return stable failure category
     */
    public Reason reason() { return reason; }

    private static String hint(Reason reason) {
        return switch (reason) {
            case INVALID_NAME -> "Use 1-64 lowercase ASCII characters, starting with a letter; separate words with _ or -.";
            case COLLISION -> "This UTC second already has a migration. Inspect the catalog and retry at a later second.";
            case CLOCK_ORDER -> "Check the UTC clock; the new ID must follow existing IDs and use a year from 0001 to 9999.";
            case BUSY -> "Another creator is active. Wait for it to finish, then retry; do not delete the lock file.";
            case CAPACITY -> "The catalog is at its format-v1 limit. Review migration count and byte budgets.";
            case UNSAFE_LOCK -> "The sibling coordination file must be empty, regular and unlinked. Inspect it before retrying.";
            case PARTIAL_CREATION -> "Inspect the new migration directory and any partial migration.sql before retrying; no files were removed.";
            case IO_ERROR -> "Check catalog and parent permissions. Inspect the catalog before retrying.";
        };
    }
}
