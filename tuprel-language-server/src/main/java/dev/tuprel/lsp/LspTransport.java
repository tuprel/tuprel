package dev.tuprel.lsp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.Strictness;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/** Bounded LSP framing over one byte stream in each direction. */
final class LspTransport {
    static final int MAX_MESSAGE_BYTES = 1024 * 1024;
    private static final int MAX_HEADER_BYTES = 8192;
    private static final Gson JSON = new GsonBuilder().setStrictness(Strictness.STRICT).serializeNulls().create();

    private final InputStream input;
    private final OutputStream output;

    LspTransport(InputStream input, OutputStream output) {
        this.input = input;
        this.output = output;
    }

    String read() throws IOException {
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        int tail = 0;
        while (header.size() < MAX_HEADER_BYTES) {
            int value = input.read();
            if (value < 0) {
                if (header.size() == 0) {
                    return null;
                }
                throw new EOFException();
            }
            header.write(value);
            tail = (tail << 8) | value;
            if (tail == 0x0d0a0d0a) {
                int length = contentLength(header.toString(StandardCharsets.US_ASCII));
                byte[] body = input.readNBytes(length);
                if (body.length != length) {
                    throw new EOFException();
                }
                return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(body)).toString();
            }
        }
        throw new IOException("Header too large");
    }

    private int contentLength(String header) throws IOException {
        int length = -1;
        for (String line : header.substring(0, header.length() - 4).split("\r\n", -1)) {
            int separator = line.indexOf(':');
            if (separator <= 0) {
                throw new IOException("Invalid header");
            }
            if (line.substring(0, separator).equalsIgnoreCase("Content-Length")) {
                String value = line.substring(separator + 1).strip();
                if (length != -1 || value.isEmpty() || value.length() > 7
                        || !value.chars().allMatch(digit -> digit >= '0' && digit <= '9')) {
                    throw new IOException("Invalid content length");
                }
                length = Integer.parseInt(value);
            }
        }
        if (length <= 0 || length > MAX_MESSAGE_BYTES) {
            throw new IOException("Content length outside limits");
        }
        return length;
    }

    JsonElement parse(String body) {
        return JSON.fromJson(body, JsonElement.class);
    }

    void send(JsonElement message) throws IOException {
        byte[] body = JSON.toJson(message).getBytes(StandardCharsets.UTF_8);
        output.write(("Content-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        output.write(body);
        output.flush();
    }
}
