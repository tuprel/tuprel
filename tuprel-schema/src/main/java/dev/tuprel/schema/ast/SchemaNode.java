package dev.tuprel.schema.ast;

import dev.tuprel.schema.SourceSpan;

/** Common source-span contract for immutable schema AST nodes. */
public interface SchemaNode {
    /** Returns the source span that produced this node. */
    SourceSpan span();
}
