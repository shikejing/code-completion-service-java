package com.github.shikejing.codecompletionservicejava.web;

import com.github.shikejing.codecompletionservicejava.model.CompletionRequest;
import com.github.shikejing.codecompletionservicejava.model.Position;

/** JSON body for completion queries. */
public record CompletionRequestDto(
        String uri,
        PositionDto position,
        String triggerCharacter,
        String desiredType,
        Long timeoutMillis,
        String requestId
) {
    public record PositionDto(int line, int character) {}

    public CompletionRequest toRequest(long defaultTimeout, String configHash) {
        return new CompletionRequest(uri,
                new Position(position.line(), position.character()),
                triggerCharacter, desiredType,
                timeoutMillis == null ? defaultTimeout : timeoutMillis,
                configHash, requestId);
    }
}
