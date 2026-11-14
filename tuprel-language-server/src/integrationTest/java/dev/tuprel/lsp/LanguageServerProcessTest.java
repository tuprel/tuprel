package dev.tuprel.lsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.tuprel.schema.SchemaCompiler;
import dev.tuprel.schema.SchemaDiagnostic;
import dev.tuprel.schema.SourceText;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class LanguageServerProcessTest {
    private static final String FIRST = "untitled:one.tuprel";
    private static final String SECOND = "file:///does-not-exist/two.tuprel";
    private static final String VALID = "model User { id UUID @id }";

    @Test
    void lifecycleAndOfficialDiagnosticsUseUnsavedFullText() throws Exception {
        try (Client client = new Client()) {
            client.send("{\"jsonrpc\":\"2.0\",\"id\":0,\"method\":\"shutdown\"}");
            assertEquals(-32002, client.read().getAsJsonObject("error").get("code").getAsInt());
            client.initialize();

            client.open(FIRST, 1, VALID);
            assertTrue(client.diagnostics(FIRST, 1).isEmpty());
            String invalid = "model User { id UUID @id value Missing }";
            client.open(SECOND, 3, invalid);
            SchemaDiagnostic expected = new SchemaCompiler().compile(SourceText.of("test", invalid)).diagnostics().getFirst();
            JsonObject diagnostic = client.diagnostics(SECOND, 3).get(0).getAsJsonObject();
            assertEquals(expected.code(), diagnostic.get("code").getAsString());
            assertEquals(expected.message(), diagnostic.get("message").getAsString());
            assertEquals(1, diagnostic.get("severity").getAsInt());

            client.change(SECOND, 4, VALID);
            client.change(SECOND, 4, invalid);
            client.change(SECOND, 2, invalid);
            client.change(FIRST, 2, "model");
            client.change(FIRST, 3, VALID);
            assertTrue(client.diagnostics(SECOND, 4).isEmpty());
            assertTrue(client.diagnostics(FIRST, 2).get(0).getAsJsonObject()
                    .get("code").getAsString().startsWith("TUPREL-SCHEMA-PARSE-"));
            assertTrue(client.diagnostics(FIRST, 3).isEmpty());

            client.notify("textDocument/didClose", document(FIRST, null));
            assertTrue(client.diagnostics(FIRST, null).isEmpty());
            client.change(FIRST, 4, invalid);
            client.change(SECOND, 5, VALID);
            assertTrue(client.diagnostics(SECOND, 5).isEmpty());
            client.shutdown();
        }
    }

    @Test
    void mapsUtf16AndEmptyIntervalsAcrossLineEndings() throws Exception {
        try (Client client = new Client()) {
            client.initialize();
            for (String ending : new String[] {"\n", "\r\n"}) {
                String firstLine = "/* 🧪 */ $";
                client.open(FIRST, 1, firstLine + ending + "model");
                JsonArray diagnostics = client.diagnostics(FIRST, 1);
                JsonObject lexical = diagnostics.get(0).getAsJsonObject();
                assertTrue(lexical.get("code").getAsString().startsWith("TUPREL-SCHEMA-LEX-"));
                assertPosition(lexical, "start", 0, firstLine.indexOf('$'));
                assertPosition(lexical, "end", 0, firstLine.length());
                JsonObject syntax = diagnostics.get(1).getAsJsonObject();
                assertPosition(syntax, "start", 1, 5);
                assertPosition(syntax, "end", 1, 5);
                client.notify("textDocument/didClose", document(FIRST, null));
                client.diagnostics(FIRST, null);
            }
            client.open(FIRST, 1, "");
            JsonObject empty = client.diagnostics(FIRST, 1).get(0).getAsJsonObject();
            assertPosition(empty, "start", 0, 0);
            assertPosition(empty, "end", 0, 0);
            client.shutdown();
        }
    }

    @Test
    void rejectsInvalidMessagesAndBoundsDocumentState() throws Exception {
        try (Client client = new Client()) {
            client.send("{");
            assertEquals(-32700, client.read().getAsJsonObject("error").get("code").getAsInt());
            client.send("[]");
            assertEquals(-32600, client.read().getAsJsonObject("error").get("code").getAsInt());
            client.initialize();
            client.open(FIRST, 1, "$".repeat(1000));
            assertEquals(200, client.diagnostics(FIRST, 1).size());
            client.change(FIRST, 2, "$".repeat(65_537));
            client.change(FIRST, 1, "model");
            client.change(FIRST, 3, VALID);
            assertTrue(client.diagnostics(FIRST, 2).isEmpty());
            assertTrue(client.diagnostics(FIRST, 3).isEmpty());

            for (int i = 1; i < 64; i++) {
                String uri = "untitled:capacity-" + i;
                client.open(uri, 1, VALID);
                client.diagnostics(uri, 1);
            }
            client.open(SECOND, 1, VALID);
            client.notify("textDocument/didClose", document(FIRST, null));
            client.diagnostics(FIRST, null);
            client.open(SECOND, 1, VALID);
            client.diagnostics(SECOND, 1);
            client.shutdown();
        }
    }

    @Test
    void exitsOnEofTruncationAndOversizedFrames() throws Exception {
        try (Client client = new Client()) {
            client.initialize();
            client.finish(0);
        }
        for (String frame : new String[] {
            "Content-Length: 1048577\r\n\r\n",
            "Content-Length: 3\r\n\r\n{}",
            "Content-Length: 2\r\nContent-Length: 2\r\n\r\n{}"
        }) {
            try (Client client = new Client()) {
                client.raw(frame);
                client.finish(1);
                assertEquals("TUPREL language server session terminated.",
                        new String(client.process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).strip());
            }
        }
        try (Client client = new Client()) {
            client.notify("exit", new JsonObject());
            client.finish(1);
        }
    }

    private static void assertPosition(JsonObject diagnostic, String boundary, int line, int character) {
        JsonObject position = diagnostic.getAsJsonObject("range").getAsJsonObject(boundary);
        assertEquals(line, position.get("line").getAsInt());
        assertEquals(character, position.get("character").getAsInt());
    }

    private static JsonObject document(String uri, Integer version) {
        JsonObject document = new JsonObject();
        document.addProperty("uri", uri);
        if (version != null) {
            document.addProperty("version", version);
        }
        JsonObject params = new JsonObject();
        params.add("textDocument", document);
        return params;
    }

    private static final class Client implements AutoCloseable {
        private final Process process;
        private final LspTransport transport;

        Client() throws IOException {
            process = new ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", System.getProperty("tuprel.lsp.lib") + File.separator + "*",
                    "dev.tuprel.lsp.TuprelLanguageServer").start();
            transport = new LspTransport(new BufferedInputStream(process.getInputStream()), process.getOutputStream());
        }

        void raw(String frame) throws IOException {
            process.getOutputStream().write(frame.getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().flush();
        }

        void send(String body) throws IOException {
            // Independent byte count on the client side, including non-ASCII input.
            raw("Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n" + body);
        }

        JsonObject read() throws IOException {
            JsonObject response = transport.parse(transport.read()).getAsJsonObject();
            assertEquals("2.0", response.get("jsonrpc").getAsString());
            return response;
        }

        void initialize() throws IOException {
            send("{\"jsonrpc\":\"2.0\",\"id\":\"init\",\"method\":\"initialize\",\"params\":{\"capabilities\":{}}}");
            JsonObject response = read();
            assertEquals("init", response.get("id").getAsString());
            JsonObject capabilities = response.getAsJsonObject("result").getAsJsonObject("capabilities");
            assertEquals("utf-16", capabilities.get("positionEncoding").getAsString());
            JsonObject sync = capabilities.getAsJsonObject("textDocumentSync");
            assertTrue(sync.get("openClose").getAsBoolean());
            assertEquals(1, sync.get("change").getAsInt());
            notify("initialized", new JsonObject());
        }

        void notify(String method, JsonObject params) throws IOException {
            JsonObject request = new JsonObject();
            request.addProperty("jsonrpc", "2.0");
            request.addProperty("method", method);
            request.add("params", params);
            send(request.toString());
        }

        void open(String uri, int version, String text) throws IOException {
            JsonObject params = document(uri, version);
            JsonObject item = params.getAsJsonObject("textDocument");
            item.addProperty("languageId", "tuprel");
            item.addProperty("text", text);
            notify("textDocument/didOpen", params);
        }

        void change(String uri, int version, String text) throws IOException {
            JsonObject params = document(uri, version);
            JsonObject change = new JsonObject();
            change.addProperty("text", text);
            JsonArray changes = new JsonArray();
            changes.add(change);
            params.add("contentChanges", changes);
            notify("textDocument/didChange", params);
        }

        JsonArray diagnostics(String uri, Integer version) throws IOException {
            JsonObject message = read();
            assertEquals("textDocument/publishDiagnostics", message.get("method").getAsString());
            JsonObject params = message.getAsJsonObject("params");
            assertEquals(uri, params.get("uri").getAsString());
            if (version == null) {
                assertFalse(params.has("version"));
            } else {
                assertEquals(version.intValue(), params.get("version").getAsInt());
            }
            return params.getAsJsonArray("diagnostics");
        }

        void shutdown() throws Exception {
            send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"shutdown\"}");
            assertTrue(read().get("result").isJsonNull());
            notify("exit", new JsonObject());
            finish(0);
            assertEquals(-1, process.getErrorStream().read());
        }

        void finish(int expectedExit) throws Exception {
            process.getOutputStream().close();
            assertTrue(process.waitFor(5, TimeUnit.SECONDS));
            assertEquals(expectedExit, process.exitValue());
            assertNull(transport.read(), "stdout contains an unexpected message");
        }

        @Override
        public void close() {
            process.destroyForcibly();
        }
    }
}
