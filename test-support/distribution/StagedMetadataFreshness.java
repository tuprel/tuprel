import java.io.IOException;
import java.time.Duration;
import java.time.Instant;

/** Freshness rules for a staging experiment, independent of the metadata encoding. */
final class StagedMetadataFreshness {
    private static final Duration MAX_VALIDITY = Duration.ofHours(24);

    private StagedMetadataFreshness() {}

    /**
     * Uses a single time sample for the update. The caller must have independently authenticated
     * the sample and verified that it belongs to this attempt. A wall clock alone is insufficient.
     */
    static void verify(Instant authenticatedTime, Instant checkpointTime, Instant issued, Instant expires)
            throws IOException {
        if (authenticatedTime == null || issued == null || expires == null) {
            throw new IOException("Authenticated time and metadata validity are required");
        }
        if (checkpointTime != null && authenticatedTime.isBefore(checkpointTime)) {
            throw new IOException("Authenticated time predates the security checkpoint");
        }
        Duration validity = Duration.between(issued, expires);
        if (validity.isNegative() || validity.isZero() || validity.compareTo(MAX_VALIDITY) > 0
                || authenticatedTime.isBefore(issued) || !authenticatedTime.isBefore(expires)) {
            throw new IOException("Metadata is outside its permitted validity window");
        }
    }
}
