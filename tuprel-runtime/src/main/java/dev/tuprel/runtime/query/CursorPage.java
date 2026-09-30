package dev.tuprel.runtime.query;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One page of cursor pagination.
 *
 * @param items rows of this page, in query order
 * @param nextCursor cursor for the following page; empty on the last page
 * @param <T> row type
 */
public record CursorPage<T>(List<T> items, Optional<Cursor> nextCursor) {
    public CursorPage {
        items = List.copyOf(items);
        Objects.requireNonNull(nextCursor, "nextCursor");
    }

    /** Whether another page follows. */
    public boolean hasNext() {
        return nextCursor.isPresent();
    }
}
