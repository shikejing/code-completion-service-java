package com.github.shikejing.codecompletionservicejava.analysis;

import com.github.shikejing.codecompletionservicejava.model.CompletionKind;

/**
 * A single declaration discovered while parsing.
 */
public record Symbol(
        String name,
        CompletionKind kind,
        String typeName,
        String containerName,
        int startOffset,
        int endOffset,
        int line,
        int depth
) {
}
