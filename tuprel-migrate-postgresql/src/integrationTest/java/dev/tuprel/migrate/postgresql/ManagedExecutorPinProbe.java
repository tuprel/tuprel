package dev.tuprel.migrate.postgresql;

import dev.tuprel.migrate.MigrationChecksum;
import java.nio.file.Path;

/** Child JVM exercises the exact loaded origin without an administrative connection. */
final class ManagedExecutorPinProbe {
    private ManagedExecutorPinProbe() { }

    public static void main(String[] args) {
        boolean supervisor = args.length == 3;
        ManagedExecutorPin pin = new ManagedExecutorPin(Path.of(args[0]), new MigrationChecksum(args[1]), "development",
                PhysicalDeployDigest.DOMAIN, PhysicalDeployDigest.VERSION, supervisor ? Long.parseLong(args[2]) : 0);
        System.exit(pin.verifiesRunningExecutor() && (!supervisor || pin.protectedInstallation()) ? 0 : 2);
    }
}
