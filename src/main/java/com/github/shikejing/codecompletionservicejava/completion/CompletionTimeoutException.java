package com.github.shikejing.codecompletionservicejava.completion;

/** Raised when a request exceeds its configured timeout. */
public class CompletionTimeoutException extends RuntimeException {
    public CompletionTimeoutException(String message) { super(message); }
}
