# Tuprel Language Server

The Java 21 server provides official diagnostics, whole-document formatting,
contextual completion, Hover and Go to Definition over local stdin and stdout.
Start it from an installed distribution.

The D2 staging build targets Java 17 and is tested with the same installed
libraries on Java 17, 21 and 25. This certification work does not change the
public Java 21 requirement; activation remains subject to ADR-0024.

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
- Limits are 8 KiB per header, 1 MiB per inbound frame, 64 levels of JSON
  nesting, 64 open documents, 64 Ki UTF-16 units per document, 4,096 UTF-16
  units per URI and 200 published diagnostics per document. Unrecoverable
  framing errors and invalid UTF-8 end the session. Invalid JSON within a
  valid frame returns a JSON-RPC parse error and the session can continue.
- A rejected open at capacity sends a generic warning once until a tracked
  document closes. The client can then reopen the rejected document.
- Versioned diagnostics from tuprel-language-server identify skipped or
  interrupted analysis: TUPREL-LSP-DOCUMENT-TOO-LARGE,
  TUPREL-LSP-ANALYSIS-STACK-LIMIT and TUPREL-LSP-ANALYSIS-FAILED. An oversized
  snapshot retains only its version. Later edits that fit the budget can be
  analysed. Runtime exceptions from the compiler emit a redacted stderr
  message. Other JVM errors propagate.
- No workspace file access, database, subprocess or network access is required.
  Operational errors go to stderr without schema text, URIs or stack traces.
  Compiler messages go to the client through the protocol.

## Whole-document formatting

The server advertises documentFormattingProvider and accepts
textDocument/formatting for an open document. Send the usual options object
with tabSize and insertSpaces. The formatter follows RFC-001: four spaces per
level and LF, so applying an edit may convert CRLF to LF. Editor options do
not change that policy.

The server analyses the in-memory snapshot in message order. The official
formatter validates the input, and the server compiles the output again and
compares ordered language tokens and comments with the official lexer.
String values are compared after decoding. Comment line endings are
normalised for comparison. An edit replaces the exact original UTF-16 range,
and the server never applies the edit itself. An unchanged, invalid,
unavailable, oversized, unknown or closed document returns an empty list.

Formatting results carry no document version. The future editor client must
capture the version at request time and discard the result if the user has
edited, closed or reopened the document before the result can be applied.

The following request and response were captured from the installed server
after opening an unsaved document with text model Item{ id Int @id }.
Framing headers are omitted.

```text
>>> {"jsonrpc":"2.0","id":2,"method":"textDocument/formatting","params":{"textDocument":{"uri":"untitled:report.tuprel"},"options":{"tabSize":4,"insertSpaces":true}}}
<<< {"jsonrpc":"2.0","id":2,"result":[{"range":{"start":{"line":0,"character":0},"end":{"line":0,"character":24}},"newText":"model Item {\n    id Int @id\n}\n"}]}
```

## Contextual completion

The server advertises `completionProvider` with `@` as a trigger character.
Send `textDocument/completion` with the URI of an open document and a zero-based
UTF-16 position. The server uses the latest accepted in-memory snapshot in wire
order. Completion requests and responses carry no document version.

The official parser identifies the grammar slot at the cursor. Suggestions cover
top-level declarations, built-in scalars and declared model and enum names at a
field type, and separate field and model attribute names. Model and enum names
come from the same document, including declarations after the cursor. A document
can contain errors and still yield suggestions at an unambiguous slot. Field
types and implicit field attributes are offered on the field's line. Explicit
`@` and `@@` prefixes follow the parser's placement rules.

Suggestions match the typed prefix case-sensitively and have unique labels in
ascending lexical order. Each text edit replaces the current token, including
the sigil for an attribute. The server uses LSP Keyword, Class, Enum and Property
kinds for declarations, scalar or model types, enum types and attributes. Attribute
suggestions identify their syntactic scope and leave argument entry and semantic
checks to the editor and official diagnostics.

Comments, strings, ambiguous syntax, closed or unknown documents, oversized
snapshots and positions past the line or document return an empty list.
Malformed parameters receive JSON-RPC error `-32602`. If more than 200 labels
match, the server returns the first 200 and marks the list incomplete so the
client can request a narrower prefix. The client chooses whether to apply an
edit. Completion uses only the current document and does not provide argument
values, cross-file symbols or item resolution.

This exchange was captured from the installed server with CRLF text containing
`state St` on the second line and a following `enum Status { Ready }`.
Framing headers are omitted.

```text
>>> {"jsonrpc":"2.0","id":2,"method":"textDocument/completion","params":{"textDocument":{"uri":"untitled:report.tuprel"},"position":{"line":1,"character":12}}}
<<< {"jsonrpc":"2.0","id":2,"result":[{"label":"Status","kind":13,"textEdit":{"range":{"start":{"line":1,"character":10},"end":{"line":1,"character":12}},"newText":"Status"}},{"label":"String","kind":7,"textEdit":{"range":{"start":{"line":1,"character":10},"end":{"line":1,"character":12}},"newText":"String"}}]}
```

## Hover and Go to Definition

The server advertises `hoverProvider: true` and `definitionProvider: true`.
Send `textDocument/hover` or `textDocument/definition` with the URI of an open
document and a zero-based UTF-16 position inside an identifier. Ranges are
half-open and work with LF, CRLF and supplementary Unicode characters.

Hover returns plaintext for model and enum declarations, references to named
types in field declarations, field names and official scalar types. Models show
fields with unambiguous names and resolved types. Field hover also shows the
owning model and cardinality. Definition returns the exact identifier span of
the target model or enum for a field type reference. The Location uses the
request document's URI.

Both queries use the official lexer, recovered syntax and compiler type
resolution. Independent errors can coexist with a reliable result. Incomplete
model or enum bodies are eligible when the declaration has a real name and
opening brace. The server returns `null` when a symbol is unknown or ambiguous,
a position is outside a supported identifier, a snapshot is unavailable, or
analysis was interrupted. Comments, strings, declarations queried for
Definition, and scalar or field names queried for Definition return `null`.
Field references in `@relation` arguments, symbols in other attributes, enum
value navigation, configuration and cross-file resolution are outside this
scope. Malformed URIs or positions receive JSON-RPC error `-32602`.

Queries see the latest accepted in-memory version in wire order, including
unsaved edits. Closed and unknown documents return `null`. The requests and
responses carry no document version. A client issuing asynchronous requests
must capture the document identity and version and discard the response if the
document was edited, closed or reopened while the request was pending.

The following exchange was captured from the installed server with an unsaved
CRLF document containing `customer Customer` on line 2 and the corresponding
`model Customer` on line 5, counting from zero. Framing headers are omitted.

```text
>>> {"jsonrpc":"2.0","id":2,"method":"textDocument/hover","params":{"textDocument":{"uri":"untitled:orders.tuprel"},"position":{"line":2,"character":15}}}
<<< {"jsonrpc":"2.0","id":2,"result":{"contents":{"kind":"plaintext","value":"Model: Customer\nType: TUPREL model\nFields: id: UUID, name: String"},"range":{"start":{"line":2,"character":13},"end":{"line":2,"character":21}}}}
>>> {"jsonrpc":"2.0","id":3,"method":"textDocument/definition","params":{"textDocument":{"uri":"untitled:orders.tuprel"},"position":{"line":2,"character":15}}}
<<< {"jsonrpc":"2.0","id":3,"result":{"uri":"untitled:orders.tuprel","range":{"start":{"line":5,"character":6},"end":{"line":5,"character":14}}}}
```

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
<<< {"jsonrpc":"2.0","id":1,"result":{"capabilities":{"textDocumentSync":{"openClose":true,"change":1},"positionEncoding":"utf-16","documentFormattingProvider":true,"hoverProvider":true,"definitionProvider":true,"completionProvider":{"triggerCharacters":["@"]}}}}
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
