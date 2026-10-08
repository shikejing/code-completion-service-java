package com.github.shikejing.codecompletionservicejava.model;

import java.util.List;

/**
 * Deterministic response to a completion request. A non-OK status always
 * carries an empty candidate list; a timeout/cancellation never leaks a
 * partial result.
 */
public record CompletionResponse(String uri,
                                 Position position,
                                 int version,
                                 String prefix,
                                 CompletionStatus status,
                                 List<CompletionItem> items) {
}
