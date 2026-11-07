package dev.tuprel.migrate.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.tuprel.sql.SqlIdentifier;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;

class ManagedMigrationCommandsTest {
    @Test void refusesAmbiguousAndLegacyInvocationsBeforeObtainingAnIssuer() {
        ManagedMigrationCommands commands = new ManagedMigrationCommands(Path.of("project"), new SqlIdentifier("sales"),
                Path.of("installation"), null, null, () -> { throw new AssertionError("Issuer accessed"); }, Clock.systemUTC());
        PrintWriter output = new PrintWriter(new StringWriter());
        for (List<String> arguments : List.of(
                List.of("migrate", "dev", "--target-schema", "sales", "--name", "first"),
                List.of("migrate", "dev", "--target-schema", "wrong", "--name", "first", "--write"),
                List.of("migrate", "dev", "--target-schema", "sales", "--name", "first", "--write", "--write"),
                List.of("migrate", "deploy", "--target-schema", "sales", "--approve-digest", "a".repeat(64)),
                List.of("migrate", "deploy", "--target-schema", "sales", "--dry-run", "--approve-physical", "token"))) {
            assertEquals(2, commands.run(arguments, output, output));
        }
    }
}
