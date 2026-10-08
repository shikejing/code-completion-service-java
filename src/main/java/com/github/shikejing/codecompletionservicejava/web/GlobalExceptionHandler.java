package com.github.shikejing.codecompletionservicejava.web;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns malformed requests into deterministic JSON error bodies instead of
 * container default error pages. Service-level failures (timeouts, unknown
 * documents, cancellation) are already encoded in {@code CompletionResponse};
 * this advice only guards the HTTP binding boundary.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> malformedJson(HttpMessageNotReadableException error) {
        return ResponseEntity.badRequest().body(Map.of(
                "status", "BAD_REQUEST",
                "message", "malformed JSON request body"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> illegalArgument(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of(
                "status", "BAD_REQUEST",
                "message", String.valueOf(error.getMessage())));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unexpected(Exception error) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "status", "ERROR",
                "message", error.getClass().getSimpleName() + ": " + error.getMessage()));
    }
}
