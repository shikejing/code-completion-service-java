package com.github.shikejing.codecompletionservicejava.runtime;

/** Control-flow exception thrown at a cancellation checkpoint. */
public final class QueryCancelledException extends RuntimeException {
    public QueryCancelledException() {
        super("query cancelled");
    }
}
