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
import java.util.function.Function;

/** Standalone Tuprel language server entry point. */
public final class TuprelLanguageServer {
    private static final int MAX_DOCUMENTS = 64;
    private static final int MAX_DOCUMENT_UNITS = 64 * 1024;
    private static final int MAX_URI_UNITS = 4096;
    private static final int MAX_DIAGNOSTICS = 200;

    private enum State { NEW, INITIALIZING, READY, SHUTDOWN }

    private record Snapshot(int version, String text) {}

    private final LspTransport transport;
    private final Function<SourceText, List<SchemaDiagnostic>> compile;
    private final Map<String, Snapshot> documents = new HashMap<>();
    private State state = State.NEW;
    private boolean capacityWarningSent;

    TuprelLanguageServer(LspTransport transport, Function<SourceText, List<SchemaDiagnostic>> compile) {
        this.transport = transport;
        this.compile = compile;
    }

    /** Runs the stdio session and returns a process status through the Java launcher. */
    public static void main(String[] args) {
        SchemaCompiler compiler = new SchemaCompiler();
        try {
            System.exit(new TuprelLanguageServer(
                    new LspTransport(new BufferedInputStream(System.in), System.out),
                    source -> compiler.compile(source).diagnostics()).run());
        } catch (IOException | RuntimeException failure) {
            System.err.println("TUPREL language server session terminated.");
            System.exit(1);
        }
    }

    int run() throws IOException {
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
            if (!"2.0".equals(string(message.get("jsonrpc"))) || method == null || method.isEmpty() || !validId(id)) {
                error(JsonNull.INSTANCE, -32600, "Invalid Request");
                continue;
            }
            JsonElement params = message.get("params");
            if (id != null) {
                request(id, method, params);
            } else if (params == null || params.isJsonObject()) {
                if ("exit".equals(method)) {
                    return state == State.SHUTDOWN ? 0 : 1;
                }
                notification(method, params);
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
        } else if (params != null && !params.isJsonObject()) {
            error(id, -32602, "Invalid params");
        } else if ("shutdown".equals(method)) {
            if (params != null && !params.getAsJsonObject().isEmpty()) {
                error(id, -32602, "Invalid params");
                return;
            }
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
                if (current == null && version != null && text != null) {
                    if (documents.size() < MAX_DOCUMENTS) {
                        update(uri, version, text);
                    } else if (!capacityWarningSent) {
                        capacityWarningSent = true;
                        warnAtCapacity();
                    }
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
                    capacityWarningSent = false;
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
        // Dispatch and publication run on the reader thread, in wire order, without queued analysis.
        publish(uri, snapshot.version(), analyze(snapshot.text()));
    }

    private JsonArray analyze(String text) {
        if (text == null) {
            return unavailable("TUPREL-LSP-DOCUMENT-TOO-LARGE",
                    "Analysis skipped: document exceeds " + MAX_DOCUMENT_UNITS + " UTF-16 code units.");
        }

        List<SchemaDiagnostic> found;
        try {
            found = compile.apply(SourceText.of("editor.tuprel", text));
        } catch (StackOverflowError exhausted) {
            // The schema parser descends recursively into expressions.
            return unavailable("TUPREL-LSP-ANALYSIS-STACK-LIMIT",
                    "Analysis interrupted: the compiler exceeded its stack limit.");
        } catch (RuntimeException failure) {
            // Exception messages and stack traces can contain schema text or other local data.
            System.err.println("TUPREL-LSP-ANALYSIS-FAILED: unexpected schema compiler exception.");
            return unavailable("TUPREL-LSP-ANALYSIS-FAILED",
                    "Analysis interrupted by an unexpected compiler error.");
        }

        JsonArray diagnostics = new JsonArray();
        DiagnosticRanges ranges = new DiagnosticRanges(text);
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
        return diagnostics;
    }

    private static JsonArray unavailable(String code, String description) {
        JsonObject origin = new JsonObject();
        origin.addProperty("line", 0);
        origin.addProperty("character", 0);
        JsonObject range = new JsonObject();
        range.add("start", origin);
        range.add("end", origin);
        JsonObject diagnostic = new JsonObject();
        diagnostic.add("range", range);
        diagnostic.addProperty("severity", 2);
        diagnostic.addProperty("code", code);
        diagnostic.addProperty("source", "tuprel-language-server");
        diagnostic.addProperty("message", description);
        JsonArray diagnostics = new JsonArray();
        diagnostics.add(diagnostic);
        return diagnostics;
    }

    private void warnAtCapacity() throws IOException {
        JsonObject params = new JsonObject();
        params.addProperty("type", 2);
        params.addProperty("message", "TUPREL analysis unavailable for additional documents: "
                + MAX_DOCUMENTS + " document limit reached. Close a tracked document and reopen the skipped document.");
        JsonObject message = envelope();
        message.addProperty("method", "window/showMessage");
        message.add("params", params);
        transport.send(message);
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
        return id == null || string(id) != null || version(id) != null;
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
