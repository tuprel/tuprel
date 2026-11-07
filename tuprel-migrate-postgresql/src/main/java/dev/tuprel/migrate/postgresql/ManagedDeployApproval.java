package dev.tuprel.migrate.postgresql;

import java.util.UUID;

/** Wire representation for a managed review. Legacy digests lack the physical domain and generation. */
final class ManagedDeployApproval {
    private static final String PREFIX = PhysicalDeployDigest.DOMAIN + "/" + PhysicalDeployDigest.VERSION + ":";

    private ManagedDeployApproval() { }

    static String format(PostgresqlPhysicalApproval.Approval approval) {
        return PREFIX + approval.generation() + ":" + approval.digest().value();
    }

    static PostgresqlPhysicalApproval.Approval parse(String token) {
        int separator = PREFIX.length() + 36;
        if (token == null || !token.startsWith(PREFIX) || token.length() != separator + 65 || token.charAt(separator) != ':') {
            throw invalid();
        }
        String generationText = token.substring(PREFIX.length(), separator);
        UUID generation = UUID.fromString(generationText);
        if (!generation.toString().equals(generationText)) { throw invalid(); }
        return new PostgresqlPhysicalApproval.Approval(generation, new PhysicalDeployDigest(token.substring(separator + 1)));
    }

    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid managed deploy approval"); }
}
