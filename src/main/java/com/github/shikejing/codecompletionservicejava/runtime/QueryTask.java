package com.github.shikejing.codecompletionservicejava.runtime;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cancellation handle shared between a running query and its submitter.
 * The worker polls {@link #isCancelled()} between computation steps and must
 * stop before publishing any result or side effect once it sees true.
 */
public final class QueryTask {

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final long requestId;

    public QueryTask(long requestId) {
        this.requestId = requestId;
    }

    public long requestId() {
        return requestId;
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    public boolean cancel() {
        return cancelled.compareAndSet(false, true);
    }

    /** Throws {@link QueryCancelledException} if cancellation was requested. */
    public void checkpoint() {
        if (cancelled.get()) {
            throw new QueryCancelledException();
        }
    }
}
