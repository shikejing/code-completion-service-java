package com.github.shikejing.codecompletionservicejava.runtime;

import java.util.List;

import com.github.shikejing.codecompletionservicejava.model.CompletionResponse;
import com.github.shikejing.codecompletionservicejava.model.CompletionRequest;
import com.github.shikejing.codecompletionservicejava.model.Position;

/** Factory for the terminal, non-result responses. */
final class CompletionResponses {

    private CompletionResponses() {}

    static CompletionResponse timeout(CompletionRequest request, long startNanos) {
        return terminal(request, "TIMEOUT", "completion exceeded the configured timeout", startNanos);
    }

    static CompletionResponse cancelled(CompletionRequest request, long startNanos) {
        return terminal(request, "CANCELLED", "completion request was cancelled", startNanos);
    }

    static CompletionResponse error(CompletionRequest request, long startNanos, Throwable cause) {
        return terminal(request, "ERROR",
                cause.getClass().getSimpleName() + ": " + cause.getMessage(), startNanos);
    }

    private static CompletionResponse terminal(CompletionRequest request, String status,
                                               String message, long startNanos) {
        Position position = request.position() == null ? new Position(0, 0) : request.position();
        return new CompletionResponse(request.uri(), -1L, position, "", List.of(),
                false, false, System.nanoTime() - startNanos, status, message, request.requestId());
    }
}
