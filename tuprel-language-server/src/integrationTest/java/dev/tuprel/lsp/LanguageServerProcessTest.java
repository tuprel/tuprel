package dev.tuprel.lsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.tuprel.schema.SchemaCompiler;
import dev.tuprel.schema.SchemaDiagnostic;
import dev.tuprel.schema.SchemaFormatter;
import dev.tuprel.schema.SourceText;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

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
            assertUnavailable(client, FIRST, 2, "TUPREL-LSP-DOCUMENT-TOO-LARGE");
            assertTrue(client.diagnostics(FIRST, 3).isEmpty());

            for (int i = 1; i < 64; i++) {
                String uri = "untitled:capacity-" + i;
                client.open(uri, 1, VALID);
                client.diagnostics(uri, 1);
            }
            client.open(SECOND, 1, VALID);
            JsonObject warning = client.read();
            assertEquals("window/showMessage", warning.get("method").getAsString());
            assertEquals(2, warning.getAsJsonObject("params").get("type").getAsInt());
            assertFalse(warning.toString().contains(SECOND));
            client.open("untitled:capacity-extra", 1, VALID);
            client.notify("textDocument/didClose", document(FIRST, null));
            client.diagnostics(FIRST, null);
            client.open(SECOND, 1, VALID);
            client.diagnostics(SECOND, 1);
            client.open("untitled:capacity-reached-again", 1, VALID);
            assertEquals("window/showMessage", client.read().get("method").getAsString());
            client.shutdown();
        }
    }

    @Test
    void exitsOnEofAndUnrecoverableFrames() throws Exception {
        try (Client client = new Client()) {
            client.initialize();
            client.finish(0);
        }
        for (String frame : new String[] {
            "Content-Length: 1048577\r\n\r\n",
            "Content-Length: 3\r\n\r\n{}",
            "Content-Length: 2\r\ncOnTeNt-LeNgTh: 2\r\n\r\n{}",
            "Content-Length: -2\r\n\r\n",
            "Content-Length: 2x\r\n\r\n",
            "Content-Length: 0\r\n\r\n",
            "Content-Length: 9999999999\r\n\r\n",
            "Content-Type: application/vscode-jsonrpc\r\n\r\n{}",
            "Content-Length : 2\r\n\r\n{}",
            "X-Large: " + "a".repeat(8192) + "\r\nContent-Length: 2\r\n\r\n{}",
            "Content-Length: 2\r\n"
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

    @Test
    void reportsCompilerStackExhaustionAndRecoversOnLaterVersion() throws Exception {
        try (Client client = new Client()) {
            client.initialize();
            String nested = "model User { id UUID @id @default(" + "[".repeat(20_000)
                    + "0" + "]".repeat(20_000) + ") }";
            client.open(FIRST, 1, nested);
            assertUnavailable(client, FIRST, 1, "TUPREL-LSP-ANALYSIS-STACK-LIMIT");
            client.change(FIRST, 2, VALID);
            assertTrue(client.diagnostics(FIRST, 2).isEmpty());
            client.shutdown();
        }
    }

    @Test
    void unexpectedCompilerExceptionIsVisibleAndLaterRequestsRecover() throws Exception {
        String input = frame("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}")
                + frame("{\"jsonrpc\":\"2.0\",\"method\":\"initialized\"}")
                + frame("""
                        {"jsonrpc":"2.0","method":"textDocument/didOpen","params":{
                          "textDocument":{"uri":"untitled:test","version":1,"text":"synthetic"}}}
                        """)
                + frame("""
                        {"jsonrpc":"2.0","method":"textDocument/didChange","params":{
                          "textDocument":{"uri":"untitled:test","version":2},
                          "contentChanges":[{"text":"model User { id UUID @id }"}]}}
                        """);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        SchemaCompiler compiler = new SchemaCompiler();
        TuprelLanguageServer server = new TuprelLanguageServer(
                new LspTransport(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), output),
                source -> {
                    if ("synthetic".equals(source.text())) {
                        throw new IllegalStateException("private/compiler/input");
                    }
                    return compiler.compile(source).diagnostics();
                });
        assertEquals(0, server.run());
        LspTransport response = new LspTransport(new ByteArrayInputStream(output.toByteArray()), output);
        response.read();
        JsonObject params = response.parse(response.read()).getAsJsonObject().getAsJsonObject("params");
        assertEquals(1, params.get("version").getAsInt());
        JsonArray diagnostics = params.getAsJsonArray("diagnostics");
        assertEquals(1, diagnostics.size());
        assertEquals("TUPREL-LSP-ANALYSIS-FAILED", diagnostics.get(0).getAsJsonObject().get("code").getAsString());
        assertFalse(params.toString().contains("private/compiler/input"));
        JsonObject recovered = response.parse(response.read()).getAsJsonObject().getAsJsonObject("params");
        assertEquals(2, recovered.get("version").getAsInt());
        assertTrue(recovered.getAsJsonArray("diagnostics").isEmpty());
        assertNull(response.read());
    }

    @Test
    void rejectsDeepJsonAndMalformedRequestsWithoutChangingState() throws Exception {
        try (Client client = new Client()) {
            client.initialize();
            client.send("{\"nested\":" + "[".repeat(20_000) + "0" + "]".repeat(20_000) + "}");
            assertError(client, -32700);
            client.send("{} {}");
            assertError(client, -32700);
            for (String id : new String[] {"true", "{}", "[]", "null", "1.5", "2147483648"}) {
                client.send("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"shutdown\"}");
                assertError(client, -32600);
            }
            for (String params : new String[] {"[]", "true", "null", "{\"unexpected\":true}"}) {
                client.send("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"shutdown\",\"params\":" + params + "}");
                assertError(client, -32602);
            }
            client.send("{\"jsonrpc\":\"2.0\",\"method\":\"exit\",\"params\":[]}");
            client.open(FIRST, 1, VALID);
            assertTrue(client.diagnostics(FIRST, 1).isEmpty());
            client.shutdown();
        }
    }

    @Test
    void invalidUtf8TerminatesWithEmptyStdoutAndRedactedStderr() throws Exception {
        try (Client client = new Client()) {
            client.raw("Content-Length: 2\r\n\r\n");
            client.process.getOutputStream().write(new byte[] {(byte) 0xc3, 0x28});
            client.finish(1);
            assertEquals("TUPREL language server session terminated.",
                    new String(client.process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).strip());
        }
    }

    @Test
    void eofDuringAnIncompleteExitAfterShutdownIsAProtocolFailure() throws Exception {
        try (Client client = new Client()) {
            client.initialize();
            client.send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"shutdown\"}");
            assertTrue(client.read().get("result").isJsonNull());
            client.raw("Content-Length: 50\r\n\r\n{\"jsonrpc\":\"2.0\"");
            client.finish(1);
        }
    }

    @Test
    void formatsTheUnsavedSnapshotWithoutWritingToDisk(@TempDir Path directory) throws Exception {
        Path saved = Files.writeString(directory.resolve("saved.tuprel"), "saved content\n");
        Path missing = directory.resolve("missing.tuprel");
        String uri = saved.toUri().toString();
        String unsavedUri = missing.toUri().toString();
        try (Client client = new Client()) {
            client.initialize();
            String input = "model User{ id UUID @id() }";
            String expected = "model User {\n    id UUID @id\n}\n";
            client.open(uri, 1, input);
            assertTrue(client.diagnostics(uri, 1).isEmpty());
            assertEdit(client.format(uri), expected, 0, input.length());

            client.open(unsavedUri, 1, "model Other{ id Int @id }");
            client.diagnostics(unsavedUri, 1);
            assertEdit(client.format(unsavedUri), "model Other {\n    id Int @id\n}\n", 0, "model Other{ id Int @id }".length());
            // A formatting response never changes the tracked text.
            String edited = assertEdit(client.format(uri), expected, 0, input.length());
            client.change(uri, 2, edited);
            assertTrue(client.diagnostics(uri, 2).isEmpty());
            assertTrue(client.format(uri).isEmpty());
            client.notify("textDocument/didClose", document(uri, null));
            client.diagnostics(uri, null);
            assertTrue(client.format(uri).isEmpty());
            assertTrue(client.format(SECOND).isEmpty());
            assertEquals("saved content\n", Files.readString(saved));
            assertFalse(Files.exists(missing));
            client.shutdown();
        }
    }

    @Test
    void formatsCommentsAndEscapesWithTheOriginalUtf16Range() throws Exception {
        String comment = "/* emoji 🧪\n    keep indentation\n\n*/";
        String model = "model First{\n id UUID @id\n text String @default(\"a\\\"🐈\")\n}";
        String original = comment + "\n" + model + "\nmodel Second{\n id Int @id\n} // 🧪";
        SchemaFormatter formatter = new SchemaFormatter();
        try (Client client = new Client()) {
            client.initialize();
            for (String eol : new String[] {"\n", "\r\n"}) {
                for (boolean trailingNewline : new boolean[] {false, true}) {
                    String input = (original + (trailingNewline ? "\n" : "")).replace("\n", eol);
                    SourceText source = SourceText.of("before", input);
                    String expected = formatter.format(source).formattedText().orElseThrow();
                    assertTrue(expected.contains(comment));
                    assertTrue(expected.contains("\"a\\\"🐈\""));
                    assertFalse(expected.contains("\r"));
                    SourceText formatted = SourceText.of("after", expected);
                    assertTrue(new SchemaCompiler().compile(formatted).isValid());
                    assertTrue(formatter.preservesContents(source, formatted));

                    client.open(FIRST, 1, input);
                    assertTrue(client.diagnostics(FIRST, 1).isEmpty());
                    String edited = assertEdit(client.format(FIRST), expected, trailingNewline ? 11 : 10,
                            trailingNewline ? 0 : "} // 🧪".length());
                    client.change(FIRST, 2, edited);
                    assertTrue(client.diagnostics(FIRST, 2).isEmpty());
                    assertTrue(client.format(FIRST).isEmpty());
                    client.notify("textDocument/didClose", document(FIRST, null));
                    client.diagnostics(FIRST, null);
                }
            }
            client.shutdown();
        }
    }

    @Test
    void invalidSnapshotsAndParametersCannotProduceFormattingEdits() throws Exception {
        try (Client client = new Client()) {
            client.initialize();
            client.open(FIRST, 1, "");
            assertFalse(client.diagnostics(FIRST, 1).isEmpty());
            assertTrue(client.format(FIRST).isEmpty());
            client.change(FIRST, 2, "model User {}");
            assertFalse(client.diagnostics(FIRST, 2).isEmpty());
            assertTrue(client.format(FIRST).isEmpty());
            client.change(FIRST, 3, " ".repeat(65_537));
            assertUnavailable(client, FIRST, 3, "TUPREL-LSP-DOCUMENT-TOO-LARGE");
            assertTrue(client.format(FIRST).isEmpty());
            client.change(FIRST, 4, "model User { id UUID @default(" + "[".repeat(20_000)
                    + "0" + "]".repeat(20_000) + ") @id }");
            assertUnavailable(client, FIRST, 4, "TUPREL-LSP-ANALYSIS-STACK-LIMIT");
            assertTrue(client.format(FIRST).isEmpty());
            for (String params : new String[] {
                "{}", "[]", "{\"textDocument\":{\"uri\":true},\"options\":{}}",
                "{\"textDocument\":{\"uri\":\"untitled:one\"},\"options\":{\"tabSize\":-1,\"insertSpaces\":true}}",
                "{\"textDocument\":{\"uri\":\"untitled:one\"},\"options\":{\"tabSize\":4,\"insertSpaces\":\"true\"}}"
            }) {
                client.requestFormatting(params);
                assertError(client, -32602);
            }
            client.change(FIRST, 5, VALID);
            assertTrue(client.diagnostics(FIRST, 5).isEmpty());
            assertEdit(client.format(FIRST), "model User {\n    id UUID @id\n}\n", 0, VALID.length());
            client.shutdown("TUPREL-LSP-FORMATTING: schema formatting interrupted.");
        }
    }

    private static String assertEdit(JsonArray edits, String expected, int line, int character) {
        assertEquals(1, edits.size());
        JsonObject edit = edits.get(0).getAsJsonObject();
        assertPosition(edit, "start", 0, 0);
        assertPosition(edit, "end", line, character);
        String newText = edit.get("newText").getAsString();
        assertEquals(expected, newText);
        return newText;
    }

    private static void assertError(Client client, int code) throws IOException {
        assertEquals(code, client.read().getAsJsonObject("error").get("code").getAsInt());
    }

    private static void assertUnavailable(Client client, String uri, int version, String code) throws IOException {
        JsonArray diagnostics = client.diagnostics(uri, version);
        assertEquals(1, diagnostics.size());
        JsonObject diagnostic = diagnostics.get(0).getAsJsonObject();
        assertEquals(code, diagnostic.get("code").getAsString());
        assertEquals("tuprel-language-server", diagnostic.get("source").getAsString());
        assertEquals(2, diagnostic.get("severity").getAsInt());
        assertPosition(diagnostic, "start", 0, 0);
        assertPosition(diagnostic, "end", 0, 0);
    }

    private static String frame(String body) {
        return "Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n" + body;
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
                    "-Xss512k", "-cp", System.getProperty("tuprel.lsp.lib") + File.separator + "*",
                    "dev.tuprel.lsp.TuprelLanguageServer").start();
            transport = new LspTransport(new BufferedInputStream(process.getInputStream()), process.getOutputStream());
        }

        void raw(String frame) throws IOException {
            process.getOutputStream().write(frame.getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().flush();
        }

        void send(String body) throws IOException {
            // Independent byte count on the client side, including non-ASCII input.
            raw(frame(body));
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
            assertTrue(capabilities.get("documentFormattingProvider").getAsBoolean());
            assertFalse(capabilities.has("documentRangeFormattingProvider"));
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

        JsonArray format(String uri) throws IOException {
            JsonObject params = document(uri, null);
            JsonObject options = new JsonObject();
            options.addProperty("tabSize", 2);
            options.addProperty("insertSpaces", false);
            params.add("options", options);
            requestFormatting(params.toString());
            JsonObject response = read();
            assertEquals("format", response.get("id").getAsString());
            return response.getAsJsonArray("result");
        }

        void requestFormatting(String params) throws IOException {
            send("{\"jsonrpc\":\"2.0\",\"id\":\"format\",\"method\":\"textDocument/formatting\",\"params\":"
                    + params + "}");
        }

        void shutdown() throws Exception {
            shutdown("");
        }

        void shutdown(String expectedStderr) throws Exception {
            send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"shutdown\"}");
            assertTrue(read().get("result").isJsonNull());
            notify("exit", new JsonObject());
            finish(0);
            assertEquals(expectedStderr, new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).strip());
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
