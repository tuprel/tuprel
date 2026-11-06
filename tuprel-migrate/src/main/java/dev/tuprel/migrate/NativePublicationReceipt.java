package dev.tuprel.migrate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;

/** Bounded journal outside format-v1 discovery. It contains no SQL or credentials. */
record NativePublicationReceipt(UUID generation, String stage, String destination, MigrationChecksum checksum,
                                MigrationChecksum baseline, String identity) {
    private static final String HEADER = "TUPREL-PUBLICATION 1";

    static Path path(Path root) { return root.resolveSibling(".migrations.publish.receipt"); }

    byte[] bytes() {
        return String.join("\n", HEADER, generation.toString(), stage, destination,
                checksum.value(), baseline.value(), identity, "").getBytes(StandardCharsets.US_ASCII);
    }

    static NativePublicationReceipt read(Path receipt) throws IOException {
        String[] fields = new String(MigrationDirectory.readRegular(receipt, 640), StandardCharsets.US_ASCII)
                .split("\n", -1);
        if (fields.length != 8 || !HEADER.equals(fields[0]) || !fields[7].isEmpty()
                || !fields[2].matches("\\.migration-stage-[a-f0-9]{32}")
                || !NativeRenameProcess.validIdentity(fields[6])) { throw invalid(); }
        try {
            UUID generation = UUID.fromString(fields[1]);
            if (!generation.toString().equals(fields[1])) { throw invalid(); }
            MigrationFile.validateDirectoryName(fields[3]);
            return new NativePublicationReceipt(generation, fields[2], fields[3],
                    new MigrationChecksum(fields[4]), new MigrationChecksum(fields[5]), fields[6]);
        } catch (IllegalArgumentException failure) { throw invalid(); }
    }

    private static IOException invalid() { return new IOException("Unverified publication receipt"); }
}
