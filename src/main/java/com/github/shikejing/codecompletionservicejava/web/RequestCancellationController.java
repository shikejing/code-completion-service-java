package com.github.shikejing.codecompletionservicejava.web;

import java.util.Map;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.github.shikejing.codecompletionservicejava.runtime.RequestRegistry;

/**
 * Cancellation surface. Kept under {@code /api/requests} separately from the
 * document routes so client-chosen request ids (base64url tokens) map cleanly
 * to a single path variable.
 */
@RestController
public class RequestCancellationController {

    private final RequestRegistry requestRegistry;

    public RequestCancellationController(RequestRegistry requestRegistry) {
        this.requestRegistry = requestRegistry;
    }

    @PostMapping("/api/requests/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable String id) {
        boolean cancelled = requestRegistry.cancel(id);
        return Map.of("cancelled", cancelled, "requestId", id);
    }
}
