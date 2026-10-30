package dev.tuprel.migrate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/** Test process: blocks inside a real cross-process critical section until stdin is released. */
public final class MigrationCreatorProcess {
    private MigrationCreatorProcess() {}

    public static void main(String[] arguments) throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-10-30T23:59:58Z"), ZoneOffset.UTC);
        new MigrationCreator(clock, (path, bytes) -> {
            System.out.println("READY");
            System.out.flush();
            if (System.in.read() < 0) { throw new java.io.IOException("Parent closed input"); }
            Files.write(path, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        }).create(Path.of(arguments[0]), "process");
    }
}
