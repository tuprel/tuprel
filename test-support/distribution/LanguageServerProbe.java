import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** External stdio consumer; compile with --release 17 against the installed Gson. */
public final class LanguageServerProbe {
    private static final String URI = "file:///fixture/schema.tuprel";
    private static final String VALID = "model Order { id UUID @id state Status }\n"
            + "enum Status { New Paid }";
    private final Process process;
    private final List<String> transcript = new ArrayList<>();

    private LanguageServerProbe(Process process) {
        this.process = process;
    }

    public static void main(String[] args) throws Exception {
        Path distribution = Path.of(args[0]).toAbsolutePath();
        Path javaHome = Path.of(args[1]).toAbsolutePath();
        boolean windows = System.getProperty("os.name").startsWith("Windows");
        List<String> command;
        if (args[2].equals("launcher")) {
            Path launcher = distribution.resolve("bin/tuprel-language-server" + (windows ? ".bat" : ""));
            command = windows ? List.of("cmd.exe", "/d", "/c", launcher.toString())
                    : List.of("/bin/sh", launcher.toString());
        } else {
            command = List.of(javaHome.resolve(windows ? "bin/java.exe" : "bin/java").toString(),
                    "-cp", distribution.resolve("lib") + java.io.File.separator + "*", "dev.tuprel.lsp.TuprelLanguageServer");
        }
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(distribution.getParent().toFile());
        builder.environment().put("JAVA_HOME", javaHome.toString());
        for (String name : List.of("JAVA_OPTS", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH")) {
            builder.environment().remove(name);
        }
        Process child = builder.start();
        // A stalled read, oversized response or a failed assertion cannot leave a server behind.
        Thread watchdog = new Thread(() -> {
            try {
                if (!child.waitFor(25, TimeUnit.SECONDS)) {
                    child.descendants().forEach(ProcessHandle::destroyForcibly);
                    child.destroyForcibly();
                }
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
            }
        }, "lsp-probe-deadline");
        watchdog.setDaemon(true);
        watchdog.start();
        try {
            LanguageServerProbe probe = new LanguageServerProbe(child);
            probe.exercise();
            Files.writeString(Path.of(args[3]), String.join("\n", probe.transcript) + "\n", StandardCharsets.UTF_8);
            System.out.println("PASS " + args[2] + " Java " + Runtime.version().feature()
                    + " " + Duration.ofNanos(child.info().totalCpuDuration().orElse(Duration.ZERO).toNanos()));
        } finally {
            child.descendants().forEach(ProcessHandle::destroyForcibly);
            child.destroyForcibly();
            child.waitFor(5, TimeUnit.SECONDS);
            watchdog.interrupt();
            watchdog.join(1000);
        }
    }

    private void exercise() throws Exception {
        JsonObject initialized = request(1, "initialize", "{\"capabilities\":{}}").getAsJsonObject();
        require(initialized.getAsJsonObject("capabilities").get("hoverProvider").getAsBoolean(), "hover capability");
        send("{\"jsonrpc\":\"2.0\",\"method\":\"initialized\",\"params\":{}}");
        document("didOpen", 1, "model Broken { id Missing }");
        require(!diagnostics(1).isEmpty(), "invalid input must produce diagnostics");
        document("didChange", 2, VALID);
        require(diagnostics(2).isEmpty(), "edit must clear diagnostics");
        JsonArray edits = request(2, "textDocument/formatting", "{\"textDocument\":{\"uri\":\"" + URI
                + "\"},\"options\":{\"tabSize\":4,\"insertSpaces\":true}}").getAsJsonArray();
        require(edits.size() == 1 && edits.get(0).getAsJsonObject().get("newText").getAsString().endsWith("\n"), "formatting");
        String typePosition = position(0, VALID.indexOf("Status") + 2);
        JsonObject hover = request(3, "textDocument/hover", typePosition).getAsJsonObject();
        require(hover.getAsJsonObject("contents").get("value").getAsString()
                .equals("Enum: Status\nType: TUPREL enum\nValues: New, Paid"), "enum hover");
        JsonObject definition = request(4, "textDocument/definition", typePosition).getAsJsonObject();
        require(definition.get("uri").getAsString().equals(URI)
                && definition.getAsJsonObject("range").getAsJsonObject("start").get("line").getAsInt() == 1, "definition");
        require(request(5, "textDocument/definition", position(0, 17)).isJsonNull(), "scalar has no definition");
        require(!request(6, "textDocument/completion", position(0, 18)).getAsJsonArray().isEmpty(), "completion");
        send("{");
        require(read().getAsJsonObject("error").get("code").getAsInt() == -32700, "malformed JSON");
        require(request(7, "shutdown", "{}").isJsonNull(), "shutdown");
        send("{\"jsonrpc\":\"2.0\",\"method\":\"exit\"}");
        process.getOutputStream().close();
        require(process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0, "exit code");
        require(process.getInputStream().read() == -1, "unexpected stdout after exit");
        require(process.getErrorStream().readAllBytes().length == 0, "unexpected stderr");
    }

    private static String position(int line, int character) {
        return "{\"textDocument\":{\"uri\":\"" + URI + "\"},\"position\":{\"line\":"
                + line + ",\"character\":" + character + "}}";
    }

    private void document(String method, int version, String text) throws Exception {
        JsonObject doc = new JsonObject();
        doc.addProperty("uri", URI);
        doc.addProperty("version", version);
        JsonObject params = new JsonObject();
        params.add("textDocument", doc);
        if (method.equals("didOpen")) {
            doc.addProperty("languageId", "tuprel");
            doc.addProperty("text", text);
        } else {
            JsonObject change = new JsonObject();
            change.addProperty("text", text);
            JsonArray changes = new JsonArray();
            changes.add(change);
            params.add("contentChanges", changes);
        }
        send("{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/" + method + "\",\"params\":" + params + "}");
    }

    private JsonArray diagnostics(int version) throws Exception {
        JsonObject message = read();
        require(message.get("method").getAsString().equals("textDocument/publishDiagnostics"), "diagnostic notification");
        JsonObject params = message.getAsJsonObject("params");
        require(params.get("uri").getAsString().equals(URI) && params.get("version").getAsInt() == version, "diagnostic identity");
        return params.getAsJsonArray("diagnostics");
    }

    private com.google.gson.JsonElement request(int id, String method, String params) throws Exception {
        send("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"" + method + "\",\"params\":" + params + "}");
        JsonObject response = read();
        require(response.get("id").getAsInt() == id && !response.has("error"), "response ID/result: " + method);
        return response.get("result");
    }

    private void send(String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        process.getOutputStream().write(("Content-Length: " + bytes.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        process.getOutputStream().write(bytes);
        process.getOutputStream().flush();
    }

    private JsonObject read() throws Exception {
        InputStream input = process.getInputStream();
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        while (!header.toString(StandardCharsets.US_ASCII).endsWith("\r\n\r\n")) {
            int value = input.read();
            require(value != -1 && header.size() < 8192, "invalid/absent stdout frame");
            header.write(value);
        }
        String framing = header.toString(StandardCharsets.US_ASCII);
        require(framing.matches("Content-Length: [0-9]+\r\n\r\n"), "stdout must contain only frames");
        int size = Integer.parseInt(framing.substring(16, framing.length() - 4));
        require(size > 0 && size < 2 * 1024 * 1024, "response budget");
        byte[] body = input.readNBytes(size);
        require(body.length == size, "truncated response");
        JsonObject message = JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
        require(message.get("jsonrpc").getAsString().equals("2.0"), "JSON-RPC version");
        transcript.add(message.toString());
        return message;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
