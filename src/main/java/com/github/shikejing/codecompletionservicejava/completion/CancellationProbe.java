package com.github.shikejing.codecompletionservicejava.completion;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Cooperative cancellation hooks used by CPU-heavy parse work.
 *
 * <p>A query executes on one worker thread for its whole lifetime; that
 * thread registers its {@link CancellationToken} before running the pipeline
 * and removes it in {@code finally}. The lexer polls at bounded character
 * intervals, so even a very large document observes cancellation promptly
 * without any async machinery.</p>
 */
public final class CancellationProbe {

    private static final ConcurrentHashMap<Thread, CancellationToken> BY_THREAD =
            new ConcurrentHashMap<>();

    private CancellationProbe() {}

    public static void attach(CancellationToken token) {
        if (token != null) {
            BY_THREAD.put(Thread.currentThread(), token);
        }
    }

    public static void detach() {
        BY_THREAD.remove(Thread.currentThread());
    }

    /** Throws {@link CompletionCancelledException} if the current work was cancelled. */
    public static void checkPoint() {
        CancellationToken token = BY_THREAD.get(Thread.currentThread());
        if (token != null && token.isCancelled()) {
            throw new CompletionCancelledException();
        }
    }
}
