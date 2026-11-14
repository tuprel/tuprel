package dev.tuprel.lsp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import dev.tuprel.schema.SchemaCompiler;
import dev.tuprel.schema.SchemaDiagnostic;
import dev.tuprel.schema.SourceText;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Standalone Tuprel language server entry point. */
public final class TuprelLanguageServer {
    private static final int MAX_DOCUMENTS = 64;
    private static final int MAX_DOCUMENT_UNITS = 64 * 1024;
    private static final int MAX_URI_UNITS = 4096;
    private static final int MAX_DIAGNOSTICS = 200;

    private enum State { NEW, INITIALIZING, READY, SHUTDOWN }

    private record Snapshot(int version, String text) {}

    private final LspTransport transport;
    private final SchemaCompiler compiler = new SchemaCompiler();
    private final Map<String, Snapshot> documents = new HashMap<>();
    private State state = State.NEW;

    private TuprelLanguageServer(LspTransport transport) {
        this.transport = transport;
    }

    /** Runs the stdio session and returns a process status through the Java launcher. */
    public static void main(String[] args) {
        try {
            System.exit(new TuprelLanguageServer(
                    new LspTransport(new BufferedInputStream(System.in), System.out)).run());
        } catch (IOException | RuntimeException failure) {
            System.err.println("TUPREL language server session terminated.");
            System.exit(1);
        }
    }

    private int run() throws IOException {
        String body;
        while ((body = transport.read()) != null) {
            JsonElement input;
            try {
                input = transport.parse(body);
            } catch (JsonParseException failure) {
                error(JsonNull.INSTANCE, -32700, "Parse error");
                continue;
            }
            if (input == null || !input.isJsonObject()) {
                error(JsonNull.INSTANCE, -32600, "Invalid Request");
                continue;
            }
            JsonObject message = input.getAsJsonObject();
            String method = string(message.get("method"));
            JsonElement id = message.get("id");
            if (!"2.0".equals(string(message.get("jsonrpc"))) || method == null || !validId(id)) {
                error(JsonNull.INSTANCE, -32600, "Invalid Request");
                continue;
            }
            if (id != null) {
                request(id, method, message.get("params"));
            } else if ("exit".equals(method)) {
                return state == State.SHUTDOWN ? 0 : 1;
            } else {
                notification(method, message.get("params"));
            }
        }
        return 0;
    }

    private void request(JsonElement id, String method, JsonElement params) throws IOException {
        if ("initialize".equals(method) && state == State.NEW) {
            if (params == null || !params.isJsonObject()) {
                error(id, -32602, "Invalid params");
                return;
            }
            JsonObject capabilities = new JsonObject();
            JsonObject sync = new JsonObject();
            sync.addProperty("openClose", true);
            sync.addProperty("change", 1);
            capabilities.add("textDocumentSync", sync);
            capabilities.addProperty("positionEncoding", "utf-16");
            JsonObject result = new JsonObject();
            result.add("capabilities", capabilities);
            state = State.INITIALIZING;
            respond(id, result);
        } else if (state == State.SHUTDOWN || "initialize".equals(method)) {
            error(id, -32600, "Invalid Request");
        } else if (state != State.READY) {
            error(id, -32002, "Server not initialized");
        } else if ("shutdown".equals(method)) {
            state = State.SHUTDOWN;
            documents.clear();
            respond(id, JsonNull.INSTANCE);
        } else {
            error(id, -32601, "Method not found");
        }
    }

    private void notification(String method, JsonElement arguments) throws IOException {
        if (state == State.INITIALIZING && "initialized".equals(method)) {
            state = State.READY;
            return;
        }
        if (state != State.READY) {
            return;
        }

        JsonObject params = object(arguments);
        JsonObject document = object(params.get("textDocument"));
        String uri = string(document.get("uri"));
        if (uri == null || uri.isBlank() || uri.length() > MAX_URI_UNITS) {
            return;
        }
        Snapshot current = documents.get(uri);
        switch (method) {
            case "textDocument/didOpen" -> {
                Integer version = version(document.get("version"));
                String text = string(document.get("text"));
                if (current == null && documents.size() < MAX_DOCUMENTS && version != null && text != null) {
                    update(uri, version, text);
                }
            }
            case "textDocument/didChange" -> {
                Integer version = version(document.get("version"));
                String text = fullText(params.get("contentChanges"));
                if (current != null && version != null && version > current.version() && text != null) {
                    update(uri, version, text);
                }
            }
            case "textDocument/didClose" -> {
                if (documents.remove(uri) != null) {
                    publish(uri, null, new JsonArray());
                }
            }
            default -> { }
        }
    }

    private void update(String uri, int version, String text) throws IOException {
        // Keep the latest version even when the content exceeds the analysis budget.
        Snapshot snapshot = new Snapshot(version, text.length() <= MAX_DOCUMENT_UNITS ? text : null);
        documents.put(uri, snapshot);
        JsonArray diagnostics = new JsonArray();
        if (snapshot.text() != null) {
            try {
                // The logical source name has no effect on diagnostics and never needs filesystem access.
                List<SchemaDiagnostic> found =
                        compiler.compile(SourceText.of("editor.tuprel", snapshot.text())).diagnostics();
                DiagnosticRanges ranges = new DiagnosticRanges(snapshot.text());
                for (SchemaDiagnostic diagnostic : found.stream().limit(MAX_DIAGNOSTICS).toList()) {
                    JsonObject item = new JsonObject();
                    item.add("range", ranges.range(diagnostic.span()));
                    item.addProperty("severity", switch (diagnostic.severity()) {
                        case ERROR -> 1;
                        case WARNING -> 2;
                    });
                    item.addProperty("code", diagnostic.code());
                    item.addProperty("source", "tuprel");
                    item.addProperty("message", diagnostic.message());
                    diagnostics.add(item);
                }
            } catch (StackOverflowError exhausted) {
                // The schema parser descends recursively into expressions. Keep the session usable.
                System.err.println("TUPREL schema nesting exceeds the analysis budget.");
            }
        }
        // Dispatch and publication run on the reader thread, in wire order, without queued analysis.
        publish(uri, snapshot.version(), diagnostics);
    }

    private void publish(String uri, Integer version, JsonArray diagnostics) throws IOException {
        JsonObject params = new JsonObject();
        params.addProperty("uri", uri);
        if (version != null) {
            params.addProperty("version", version);
        }
        params.add("diagnostics", diagnostics);
        JsonObject message = envelope();
        message.addProperty("method", "textDocument/publishDiagnostics");
        message.add("params", params);
        transport.send(message);
    }

    private static String fullText(JsonElement changes) {
        if (changes == null || !changes.isJsonArray() || changes.getAsJsonArray().size() != 1) {
            return null;
        }
        JsonObject change = object(changes.getAsJsonArray().get(0));
        return change.has("range") || change.has("rangeLength") ? null : string(change.get("text"));
    }

    private static JsonObject object(JsonElement element) {
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
    }

    private static String string(JsonElement element) {
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()
                ? element.getAsString() : null;
    }

    private static Integer version(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            return null;
        }
        try {
            return Integer.valueOf(value.getAsString());
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    private static boolean validId(JsonElement id) {
        return id == null || id.isJsonNull() || string(id) != null || version(id) != null;
    }

    private void error(JsonElement id, int code, String description) throws IOException {
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", description);
        JsonObject result = envelope();
        result.add("id", id);
        result.add("error", error);
        transport.send(result);
    }

    private void respond(JsonElement id, JsonElement payload) throws IOException {
        JsonObject result = envelope();
        result.add("id", id);
        result.add("result", payload);
        transport.send(result);
    }

    private static JsonObject envelope() {
        JsonObject message = new JsonObject();
        message.addProperty("jsonrpc", "2.0");
        return message;
    }
}
