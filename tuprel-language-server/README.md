# Tuprel Language Server

The Java 21 server reports the official schema compiler diagnostics over local
stdin and stdout. Start it from an installed distribution.

```powershell
.\gradlew.bat :tuprel-language-server:installDist
.\tuprel-language-server\build\install\tuprel-language-server\bin\tuprel-language-server.bat
```

On Unix, use ./gradlew and the launcher without .bat. The distribution can
run offline after it has been built. The server consumes LSP frames, each
with a Content-Length in bytes and a UTF-8 JSON-RPC body. Use an LSP client
or a protocol harness to send frames.

## B1 contract

- Initialize, send initialized, and use full text synchronization. Positions
  are zero-based UTF-16 and diagnostic ranges are half-open.
- Open documents by URI. Only newer versions of an open document are accepted.
  Publications carry the analysed version. Close clears the diagnostic list.
  The server handles messages and publications serially in wire order. Clients
  can use the published version to ignore results for edits sent meanwhile.
- Shutdown returns null. Exit after shutdown returns status 0. Exit before
  shutdown returns status 1. Clean EOF ends the process with status 0.
- Limits are 8 KiB per header, 1 MiB per inbound frame, 64 open documents,
  64 Ki UTF-16 units per document, 4,096 UTF-16 units per URI and 200 published
  diagnostics per document. At capacity, new open notifications are ignored.
  Oversized documents clear diagnostics and retain the version so a later
  smaller edit can recover. Unrecoverable framing errors end the session.
- No workspace file access, database, subprocess or network access is required.
  Operational errors go to stderr without schema text, URIs or stack traces.
  Compiler messages go to the client through the protocol.
- Formatting and editor integration have separate phases.

## Dependency decision

[Eclipse LSP4J 1.0.0](https://github.com/eclipse-lsp4j/lsp4j/releases/tag/v1.0.0)
was evaluated for protocol types, lifecycle, and dispatch. It adds the LSP4J
and JSON-RPC artifacts, and its JSON-RPC launcher would still need an input
budget and lifecycle policy for this application. A JSON-only adapter with
[Gson 2.15.0](https://github.com/google/gson/releases/tag/gson-parent-2.15.0)
keeps the current protocol surface explicit. Gson parses strict JSON without
project-specific reflection or deserialization. The runtime graph adds Gson
and Error Prone annotations 2.50.0, both Apache-2.0. An OSV query for those
versions returned no advisories at verification time. Gradle locks both
and verifies artifact hashes. Revisit the protocol library choice when
adding methods with more complex contracts.

Run the child-process protocol suite with
`./gradlew :tuprel-language-server:integrationTest --dependency-verification strict`.

## Captured stdio exchange

The following messages were sent to and received from the installed server
with synthetic schemas. The UTF-8 Content-Length headers are omitted here.

```text
>>> {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"capabilities":{}}}
<<< {"jsonrpc":"2.0","id":1,"result":{"capabilities":{"textDocumentSync":{"openClose":true,"change":1},"positionEncoding":"utf-16"}}}
>>> {"jsonrpc":"2.0","method":"initialized","params":{}}
>>> {"jsonrpc":"2.0","method":"textDocument/didOpen","params":{"textDocument":{"uri":"untitled:report.tuprel","languageId":"tuprel","version":1,"text":"/* 🧪 */ $"}}}
<<< {"jsonrpc":"2.0","method":"textDocument/publishDiagnostics","params":{"uri":"untitled:report.tuprel","version":1,"diagnostics":[{"range":{"start":{"line":0,"character":9},"end":{"line":0,"character":10}},"severity":1,"code":"TUPREL-SCHEMA-LEX-001","source":"tuprel","message":"Unexpected character '$'."}]}}
>>> {"jsonrpc":"2.0","method":"textDocument/didChange","params":{"textDocument":{"uri":"untitled:report.tuprel","version":2},"contentChanges":[{"text":"model Example { id UUID @id }"}]}}
<<< {"jsonrpc":"2.0","method":"textDocument/publishDiagnostics","params":{"uri":"untitled:report.tuprel","version":2,"diagnostics":[]}}
>>> {"jsonrpc":"2.0","method":"textDocument/didClose","params":{"textDocument":{"uri":"untitled:report.tuprel"}}}
<<< {"jsonrpc":"2.0","method":"textDocument/publishDiagnostics","params":{"uri":"untitled:report.tuprel","diagnostics":[]}}
>>> {"jsonrpc":"2.0","id":2,"method":"shutdown"}
<<< {"jsonrpc":"2.0","id":2,"result":null}
>>> {"jsonrpc":"2.0","method":"exit"}
```
