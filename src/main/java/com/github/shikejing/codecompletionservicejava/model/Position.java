package com.github.shikejing.codecompletionservicejava.model;

/**
 * Zero-based document position (LSP style): line and character offsets.
 */
public record Position(int line, int character) {
    public Position {
        if (line < 0 || character < 0) {
            throw new IllegalArgumentException("position components must be >= 0");
        }
    }
}
