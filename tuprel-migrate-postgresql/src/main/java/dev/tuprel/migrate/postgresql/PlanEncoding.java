package dev.tuprel.migrate.postgresql;

import dev.tuprel.migrate.MigrationChecksum;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Versioned canonical bytes, not Java serialization or locale-dependent text. */
final class PlanEncoding {
    private PlanEncoding() { }
    @FunctionalInterface interface Encoder { void write(DataOutputStream output) throws IOException; }
    static MigrationChecksum digest(Encoder encoder) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            encoder.write(output); output.flush();
            return MigrationChecksum.of(bytes.toByteArray());
        } catch (IOException exception) {
            throw new IllegalArgumentException("Invalid canonical plan encoding");
        }
    }
    static void text(DataOutputStream output, String value) throws IOException {
        java.nio.ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(value));
        byte[] bytes = new byte[encoded.remaining()]; encoded.get(bytes);
        output.writeInt(bytes.length); output.write(bytes);
    }
}
