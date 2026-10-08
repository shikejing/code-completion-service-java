package com.github.shikejing.codecompletionservicejava.model;

/**
 * A completion query against one document at one cursor position.
 */
public record CompletionRequest(
        String uri,
        Position position,
        String triggerCharacter,
        String desiredType,
        long timeoutMillis,
        String configHash,
        String requestId
) {
    public CompletionRequest(String uri, Position position, String triggerCharacter,
                             String desiredType, long timeoutMillis, String configHash) {
        this(uri, position, triggerCharacter, desiredType, timeoutMillis, configHash, null);
    }
}
