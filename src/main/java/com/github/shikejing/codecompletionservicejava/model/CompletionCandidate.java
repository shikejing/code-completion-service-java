package com.github.shikejing.codecompletionservicejava.model;

import java.util.List;

/**
 * One deduplicated completion item. {@code sources} preserves every declaration
 * that contributed to the same visible name (shadowing chain).
 */
public record CompletionCandidate(
        String label,
        CompletionKind kind,
        String detail,
        double score,
        String sortText,
        List<SymbolSource> sources,
        List<String> scoreBreakdown
) {
}
