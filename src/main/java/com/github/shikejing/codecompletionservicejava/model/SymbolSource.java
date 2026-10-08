package com.github.shikejing.codecompletionservicejava.model;

/**
 * Provenance of one declaration contributing to a candidate.
 */
public record SymbolSource(
        CompletionKind kind,
        String containerName,
        int declarationOffset,
        int declarationLine
) {
}
