package dev.tuprel.migrate.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ManagedDeployApprovalTest {
    private static final UUID GENERATION = UUID.fromString("12345678-0123-abcd-4567-123456789abc");
    private static final String DIGEST = "0123456789abcdef".repeat(4);

    @Test void requiresThePhysicalVersionAndExactCanonicalGeneration() {
        PostgresqlPhysicalApproval.Approval approval =
                new PostgresqlPhysicalApproval.Approval(GENERATION, new PhysicalDeployDigest(DIGEST));
        String token = "TUPREL-PG-PHYSICAL-DEPLOY/1:" + GENERATION + ":" + DIGEST;
        assertEquals(token, ManagedDeployApproval.format(approval));
        assertEquals(approval, ManagedDeployApproval.parse(token));
        assertThrows(IllegalArgumentException.class, () -> ManagedDeployApproval.parse(DIGEST));
        assertThrows(IllegalArgumentException.class, () -> ManagedDeployApproval.parse(token.replace("/1:", "/2:")));
        assertThrows(IllegalArgumentException.class, () -> ManagedDeployApproval.parse(token.replace("abcd", "ABCD")));
        assertThrows(IllegalArgumentException.class, () -> ManagedDeployApproval.parse(token + "\n"));
        assertThrows(IllegalArgumentException.class, () -> ManagedDeployApproval.parse(null));
    }
}
