package com.github.shikejing.codecompletionservicejava.runtime;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import com.github.shikejing.codecompletionservicejava.config.CompletionProperties;

/**
 * Bounded worker pool dedicated to queries (analysis + completion).
 *
 * Edit handling never uses this pool - edits run on short per-document
 * critical sections - so slow queries cannot block edits. The queue is
 * bounded, so a burst of slow queries cannot grow memory without limit; a
 * rejected submission fails the returned future, which the service converts
 * into a terminal TIMEOUT response instead of queueing indefinitely.
 */
public final class QueryExecutor implements AutoCloseable {

    /** A submitted unit of work plus its cancellation handle. */
    public record Submission<T>(CompletableFuture<T> future, QueryTask task) {
    }

    private final ThreadPoolExecutor pool;
    private final AtomicLong ids = new AtomicLong();

    public QueryExecutor(CompletionProperties properties) {
        int workers = Math.max(1, properties.getQueryWorkers());
        ThreadFactory factory = new ThreadFactory() {
            private final AtomicInteger n = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "completion-query-" + n.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };
        int queueCapacity = Math.max(workers, 64);
        this.pool = new ThreadPoolExecutor(workers, workers,
                30L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(queueCapacity),
                factory,
                new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * Submit a query function that receives its {@link QueryTask}. The function
     * should call {@link QueryTask#checkpoint()} between steps.
     */
    public <T> Submission<T> submit(Function<QueryTask, T> work) {
        QueryTask task = new QueryTask(ids.incrementAndGet());
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            pool.execute(() -> {
                try {
                    T result = work.apply(task);
                    // Normal completion only when not cancelled; cancellation is
                    // signalled via the exception path so dependent stages run.
                    if (task.isCancelled()) {
                        future.completeExceptionally(new QueryCancelledException());
                    } else {
                        future.complete(result);
                    }
                } catch (QueryCancelledException e) {
                    future.completeExceptionally(e);
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            });
        } catch (RejectedExecutionException e) {
            future.completeExceptionally(e);
        }
        return new Submission<>(future, task);
    }

    public void shutdown() {
        pool.shutdownNow();
        try {
            pool.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public int activeCount() {
        return pool.getActiveCount();
    }

    public int queueSize() {
        return pool.getQueue().size();
    }

    @Override
    public void close() {
        shutdown();
    }
}
