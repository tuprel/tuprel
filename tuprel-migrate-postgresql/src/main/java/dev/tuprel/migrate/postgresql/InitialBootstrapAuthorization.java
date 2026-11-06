package dev.tuprel.migrate.postgresql;

import dev.tuprel.migrate.MigrationChecksum;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Internal versioned proof. Only the separate provisioner holds the signing key. */
final class InitialBootstrapAuthorization {
    private final Claims claims;
    private final byte[] signature;

    InitialBootstrapAuthorization(Claims claims, byte[] signature) {
        this.claims = Objects.requireNonNull(claims, "claims");
        if (signature.length != 64) { throw new IllegalArgumentException("Invalid Ed25519 signature size"); }
        this.signature = signature.clone();
    }

    Claims claims() { return claims; }

    boolean verifies(PublicKey key) throws GeneralSecurityException {
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(key);
        verifier.update(claims.signingBytes());
        return verifier.verify(signature);
    }

    @Override public String toString() { return "InitialBootstrapAuthorization[redacted]"; }

    record Claims(UUID audience, MigrationChecksum fingerprint, long cluster, long database, long namespace,
                  long ledger, UUID generation, UUID nonce, Instant expiresAt) {
        Claims {
            Objects.requireNonNull(audience, "audience");
            Objects.requireNonNull(fingerprint, "fingerprint");
            Objects.requireNonNull(generation, "generation");
            Objects.requireNonNull(nonce, "nonce");
            Objects.requireNonNull(expiresAt, "expiresAt");
        }

        byte[] signingBytes() {
            return PlanEncoding.digest(output -> {
                PlanEncoding.text(output, "TUPREL-PG-INITIAL-BOOTSTRAP");
                output.writeInt(1);
                PlanEncoding.text(output, audience.toString());
                PlanEncoding.text(output, fingerprint.value());
                output.writeLong(cluster);
                output.writeLong(database);
                output.writeLong(namespace);
                output.writeLong(ledger);
                PlanEncoding.text(output, generation.toString());
                PlanEncoding.text(output, nonce.toString());
                output.writeLong(expiresAt.getEpochSecond());
                output.writeInt(expiresAt.getNano());
            }).value().getBytes(StandardCharsets.US_ASCII);
        }

        @Override public String toString() { return "InitialBootstrapClaims[redacted]"; }
    }
}
