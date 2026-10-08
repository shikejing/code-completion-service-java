package com.github.shikejing.codecompletionservicejava.completion;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cooperative cancellation signal for one request.
 */
public final class CancellationToken {

    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public void cancel() { cancelled.set(true); }
    public boolean isCancelled() { return cancelled.get(); }

    public void throwIfCancelled() {
        if (cancelled.get()) {
            throw new CompletionCancelledException();
        }
    }
}
