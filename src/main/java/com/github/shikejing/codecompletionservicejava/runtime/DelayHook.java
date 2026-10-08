package com.github.shikejing.codecompletionservicejava.runtime;

/**
 * Test/diagnostic hook for introducing an interruptible delay at an analysis
 * checkpoint. Production wiring uses {@link #NONE}; tests substitute a
 * sleeping implementation to deterministically exercise cancellation/timeout
 * without relying on large inputs or timing luck.
 */
@FunctionalInterface
public interface DelayHook {

    void await(QueryTask task);

    DelayHook NONE = task -> {
    };
}
