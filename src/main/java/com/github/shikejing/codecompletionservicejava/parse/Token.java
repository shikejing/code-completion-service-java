package com.github.shikejing.codecompletionservicejava.parse;

/**
 * One lexical token with half-open absolute offsets.
 */
public record Token(TokenType type, String text, int startOffset, int endOffset) {
}
