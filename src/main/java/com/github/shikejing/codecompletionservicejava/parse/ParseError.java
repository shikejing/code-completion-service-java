package com.github.shikejing.codecompletionservicejava.parse;

/** One parse problem located by absolute offset. */
public record ParseError(int offset, String message) {
    public String format() {
        return message + " [offset=" + offset + "]";
    }
}
