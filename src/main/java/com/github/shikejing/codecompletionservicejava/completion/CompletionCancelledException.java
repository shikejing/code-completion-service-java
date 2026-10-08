package com.github.shikejing.codecompletionservicejava.completion;

/** Raised when a request observes cooperative cancellation. */
public class CompletionCancelledException extends RuntimeException {
    public CompletionCancelledException() { super("completion request cancelled"); }
}
