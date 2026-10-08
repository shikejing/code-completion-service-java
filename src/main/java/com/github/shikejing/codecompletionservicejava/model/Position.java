package com.github.shikejing.codecompletionservicejava.model;

/**
 * Zero-based line/character offset inside a document.
 */
public record Position(int line, int character) {
    public static Position of(int line, int character) {
        return new Position(line, character);
    }
}
