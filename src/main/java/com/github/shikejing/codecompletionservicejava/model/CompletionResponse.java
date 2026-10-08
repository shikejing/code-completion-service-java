package com.github.shikejing.codecompletionservicejava.model;

import java.util.List;

/**
 * Deterministic result of a completion query.
 */
public record CompletionResponse(
        String uri,
        long documentVersion,
        Position position,
        String prefix,
        List<CompletionCandidate> items,
        boolean truncated,
        boolean cacheHit,
        long elapsedNanos,
        String status,
        String statusMessage,
        String requestId
) {
    public CompletionResponse(String uri, long documentVersion, Position position, String prefix,
                              List<CompletionCandidate> items, boolean truncated, boolean cacheHit,
                              long elapsedNanos, String status, String statusMessage) {
        this(uri, documentVersion, position, prefix, items, truncated, cacheHit,
                elapsedNanos, status, statusMessage, null);
    }
}
