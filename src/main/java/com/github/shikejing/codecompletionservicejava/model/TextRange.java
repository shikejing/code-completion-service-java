package com.github.shikejing.codecompletionservicejava.model;

/**
 * Half-open range of text described by two {@link Position}s.
 */
public record TextRange(Position start, Position end) {
}
