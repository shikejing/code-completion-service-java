package com.github.shikejing.codecompletionservicejava.runtime;

/**
 * Delay hook that sleeps in small slices, checking the cancellation flag
 * frequently, so a cancelled/expired query stops promptly and without side
 * effects.
 */
public final class SleepingDelayHook implements DelayHook {

    private final long totalSleepMs;
    private final long sliceMs;

    public SleepingDelayHook(long totalSleepMs) {
        this(totalSleepMs, 5L);
    }

    public SleepingDelayHook(long totalSleepMs, long sliceMs) {
        this.totalSleepMs = Math.max(0, totalSleepMs);
        this.sliceMs = Math.max(1, sliceMs);
    }

    @Override
    public void await(QueryTask task) {
        long waited = 0;
        while (waited < totalSleepMs) {
            task.checkpoint();
            long step = Math.min(sliceMs, totalSleepMs - waited);
            try {
                Thread.sleep(step);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new QueryCancelledException();
            }
            waited += step;
        }
        task.checkpoint();
    }
}
