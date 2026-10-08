package com.github.shikejing.codecompletionservicejava.runtime;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Component;

import com.github.shikejing.codecompletionservicejava.completion.CancellationToken;
import com.github.shikejing.codecompletionservicejava.model.CompletionResponse;
import com.github.shikejing.codecompletionservicejava.completion.CompletionService;
import com.github.shikejing.codecompletionservicejava.config.ServiceProperties;
import com.github.shikejing.codecompletionservicejava.model.CompletionRequest;

import jakarta.annotation.PreDestroy;

/**
 * Bounded thread pool that isolates query work from edit processing.
 *
 * <p>Edits are applied synchronously on the caller's thread (they must not
 * queue behind slow queries). Queries run here with a hard timeout: when the
 * timeout elapses a deterministic {@code TIMEOUT} response is returned and
 * the underlying task is cancelled (interrupted). Cancellation is delivered
 * through the same {@link CancellationToken}.</p>
 */
@Component
public class QueryExecutor {

    private final ServiceProperties properties;
    private final CompletionService completionService;
    private final RequestRegistry requestRegistry;
    private final ExecutorService pool;

    public QueryExecutor(ServiceProperties properties, CompletionService completionService,
                         RequestRegistry requestRegistry) {
        this.properties = properties;
        this.completionService = completionService;
        this.requestRegistry = requestRegistry;
        int threads = Math.max(1, properties.getLimits().getWorkerThreads());
        AtomicInteger counter = new AtomicInteger();
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "completion-query-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        this.pool = Executors.newFixedThreadPool(threads, factory);
    }

    /** Runs a completion query with timeout; never throws, always returns a response. */
    public CompletionResponse submit(CompletionRequest request, long timeoutMillis) {
        CancellationToken token = new CancellationToken();
        String requestId = request.requestId() != null && !request.requestId().isBlank()
                ? registerExternal(request.requestId(), token)
                : requestRegistry.register(token);
        long start = System.nanoTime();
        // Attach before submission so the very first lexer checkpoint is already
        // bound to this worker's cancellation token. The pool is fixed size so
        // the task runs on a stable thread for its whole lifetime.
        Future<CompletionResponse> future = pool.submit(
                () -> {
                    com.github.shikejing.codecompletionservicejava.completion.CancellationProbe.attach(token);
                    try {
                        return completionService.complete(request, token);
                    } finally {
                        com.github.shikejing.codecompletionservicejava.completion.CancellationProbe.detach();
                        requestRegistry.finish(requestId);
                    }
                });
        try {
            if (timeoutMillis > 0) {
                return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
            }
            return future.get();
        } catch (TimeoutException timeout) {
            future.cancel(true);
            token.cancel();
            return CompletionResponses.timeout(request, start);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            token.cancel();
            return CompletionResponses.cancelled(request, start);
        } catch (java.util.concurrent.ExecutionException execution) {
            Throwable cause = execution.getCause() == null ? execution : execution.getCause();
            return CompletionResponses.error(request, start, cause);
        }
    }

    /** Cancels a tracked in-flight request; returns false if unknown/finished. */
    public boolean cancel(String requestId) {
        return requestRegistry.cancel(requestId);
    }

    private String registerExternal(String externalId, CancellationToken token) {
        // first registration wins; a duplicate client id observes the same
        // in-flight request instead of creating an untracked parallel one
        requestRegistry.putIfActive(externalId, token);
        return externalId;
    }

    public ExecutorService pool() {
        return pool;
    }

    @PreDestroy
    public void shutdown() {
        pool.shutdownNow();
    }
}
