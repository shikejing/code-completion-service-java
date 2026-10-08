package com.github.shikejing.codecompletionservicejava.model;

/**
 * One completion candidate.
 *
 * @param label      text inserted when accepted (also the dedup key)
 * @param detail     signature / type detail for display
 * @param kind       candidate category
 * @param source     provenance description, e.g. "local@3:4", "keyword"
 * @param score      final sort score (higher sorts first)
 * @param sortText   stable tie-breaker text
 * @param scoreReason human readable breakdown of the weighting
 */
public record CompletionItem(String label,
                             String detail,
                             SymbolKind kind,
                             String source,
                             double score,
                             String sortText,
                             String scoreReason) {
}
