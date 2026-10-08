package com.github.shikejing.codecompletionservicejava.model;

/**
 * Half-open range in a document: [start, end).
 */
public record Range(Position start, Position end) {
    public static Range of(Position start, Position end) {
        return new Range(start, end);
    }
}
