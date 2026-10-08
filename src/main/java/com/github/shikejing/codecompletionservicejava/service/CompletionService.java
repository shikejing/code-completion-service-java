package com.github.shikejing.codecompletionservicejava.service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import com.github.shikejing.codecompletionservicejava.analysis.CompletionEngine;
import com.github.shikejing.codecompletionservicejava.analysis.RecencyTracker;
import com.github.shikejing.codecompletionservicejava.cache.CompletionCache;
import com.github.shikejing.codecompletionservicejava.config.CompletionProperties;
import com.github.shikejing.codecompletionservicejava.document.DocumentManager;
import com.github.shikejing.codecompletionservicejava.document.DocumentSnapshot;
import com.github.shikejing.codecompletionservicejava.model.ChangeOutcome;
import com.github.shikejing.codecompletionservicejava.model.CompletionItem;
import com.github.shikejing.codecompletionservicejava.model.CompletionRequest;
import com.github.shikejing.codecompletionservicejava.model.CompletionResponse;
import com.github.shikejing.codecompletionservicejava.model.CompletionStatus;
import com.github.shikejing.codecompletionservicejava.model.DocumentChangeRequest;
import com.github.shikejing.codecompletionservicejava.model.DocumentCloseRequest;
import com.github.shikejing.codecompletionservicejava.model.DocumentOpenRequest;
import com.github.shikejing.codecompletionservicejava.model.Position;
import com.github.shikejing.codecompletionservicejava.parse.IncrementalAnalyzer;
import com.github.shikejing.codecompletionservicejava.parse.SyntaxModel;
import com.github.shikejing.codecompletionservicejava.persistence.DocumentStore;
import com.github.shikejing.codecompletionservicejava.runtime.QueryCancelledException;
import com.github.shikejing.codecompletionservicejava.runtime.DelayHook;
import com.github.shikejing.codecompletionservicejava.runtime.QueryExecutor;
import com.github.shikejing.codecompletionservicejava.runtime.QueryTask;

/**
 * Process-local facade implementing every end-to-end guarantee.
 *
 * Concurrency model:
 *  - {@link DocumentManager} serializes edits per document; queries never take
 *    that lock, they read an immutable snapshot, so slow queries cannot block
 *    edits and every query observes one fully committed version;
 *  - the per-document derived state (current syntax model + its lock) is
 *    swapped atomically after an edit; queries pin the snapshot/model they
 *    started with, so no field-level mixing across versions is possible;
 *  - completion runs on the dedicated {@link QueryExecutor}; each request is
 *    cancellable and is raced against {@code queryTimeoutMs}; cancellation or
 *    timeout always resolves to a terminal response with an empty item list
 *    and no cache write;
 *  - caches are version-gated; recency and document/derived-state maps are
 *    bounded (LRU documents, bounded cache, fixed edit window).
 */
public class CompletionService implements AutoCloseable {

    /** Derived state for one document, swapped wholesale per commit. */
    private record Derived(DocumentSnapshot snapshot, SyntaxModel model,
                           RecencyTracker recency) {
    }

    private final CompletionProperties properties;
    private final DocumentManager documents;
    private final IncrementalAnalyzer incrementalAnalyzer;
    private final CompletionEngine engine;
    private final CompletionCache cache;
    private final QueryExecutor queryExecutor;
    private final DocumentStore store;

    private final Map<String, ReentrantLock> modelLocks = new ConcurrentHashMap<>();
    private final Map<String, Derived> derived = new ConcurrentHashMap<>();
    private final Map<Long, QueryTask> running = new ConcurrentHashMap<>();
    // request id -> document, so closing a document cancels its in-flight work
    private final Map<Long, String> requestDocuments = new ConcurrentHashMap<>();
    private final Map<String, java.util.Set<Long>> documentRequests =
            new ConcurrentHashMap<>();
    private final AtomicLong requestIds = new AtomicLong();
    private volatile DelayHook delayHook = DelayHook.NONE;
    private final java.util.Set<String> delayedUris =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Inject a delay hook (testing/diagnostics) for all documents. */
    public void setDelayHook(DelayHook hook) {
        this.delayHook = hook == null ? DelayHook.NONE : hook;
    }

    /** Apply the injected delay hook only to one document (testing). */
    public void setDelayedUri(String uri) {
        if (uri == null) {
            delayedUris.clear();
        } else {
            delayedUris.add(uri);
        }
    }

    public CompletionService(CompletionProperties properties,
                             DocumentManager documentManager,
                             IncrementalAnalyzer incrementalAnalyzer,
                             CompletionEngine completionEngine,
                             CompletionCache completionCache,
                             QueryExecutor queryExecutor,
                             DocumentStore documentStore) {
        this.properties = properties;
        this.documents = documentManager;
        this.incrementalAnalyzer = incrementalAnalyzer;
        this.engine = completionEngine;
        this.cache = completionCache;
        this.queryExecutor = queryExecutor;
        this.store = documentStore;
        recover();
    }

    // ------------------------------------------------------------------
    // Document lifecycle
    // ------------------------------------------------------------------

    public void open(DocumentOpenRequest request) {
        documents.open(request);
        modelLocks.computeIfAbsent(request.uri(), u -> new ReentrantLock());
        ensureDerived(request.uri());
        persist(request.uri());
    }

    public ChangeOutcome change(DocumentChangeRequest request) {
        Optional<DocumentSnapshot> before = documents.snapshot(request.uri());
        ChangeOutcome outcome = documents.change(request);
        if (outcome.status() == com.github.shikejing.codecompletionservicejava
                .model.ChangeStatus.APPLIED
                || outcome.status() == com.github.shikejing.codecompletionservicejava
                .model.ChangeStatus.REBASED) {
            updateDerivedAfterChange(request.uri(), before.orElse(null), outcome);
            // All versions produced by this notification chain are newer than
            // any cache entry; invalidate and persist the final state.
            cache.invalidateOlderThan(request.uri(), outcome.version());
            persist(request.uri());
        }
        return outcome;
    }

    public void close(DocumentCloseRequest request) {
        boolean wasOpen = documents.close(request);
        if (wasOpen) {
            derived.remove(request.uri());
            modelLocks.remove(request.uri());
            cache.invalidate(request.uri());
            store.delete(request.uri());
        }
        // Cancel anything still running against the document; its delay hook
        // observes the flag at the next slice and never publishes a result.
        java.util.Set<Long> ids = documentRequests.remove(request.uri());
        if (ids != null) {
            for (Long id : ids) {
                QueryTask t = running.get(id);
                if (t != null) {
                    t.cancel();
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Derived state (models)
    // ------------------------------------------------------------------

    private void ensureDerived(String uri) {
        if (derived.containsKey(uri)) {
            return;
        }
        ReentrantLock lock = modelLocks.computeIfAbsent(uri, u -> new ReentrantLock());
        lock.lock();
        try {
            if (derived.containsKey(uri)) {
                return;
            }
            DocumentSnapshot snapshot = documents.snapshot(uri).orElse(null);
            if (snapshot == null) {
                return;
            }
            SyntaxModel model = incrementalAnalyzer.fullAnalyzer()
                    .analyze(uri, snapshot.version(), snapshot.text());
            derived.put(uri, new Derived(snapshot, model,
                    new RecencyTracker(properties.getRecencyWindowEdits())));
        } finally {
            lock.unlock();
        }
    }

    private void updateDerivedAfterChange(String uri, DocumentSnapshot before,
                                          ChangeOutcome outcome) {
        ReentrantLock lock = modelLocks.computeIfAbsent(uri, u -> new ReentrantLock());
        lock.lock();
        try {
            DocumentSnapshot after = documents.snapshot(uri).orElseThrow();
            Derived old = derived.get(uri);
            RecencyTracker recency = old != null ? old.recency()
                    : new RecencyTracker(properties.getRecencyWindowEdits());
            SyntaxModel model;
            boolean singleSpan = outcome.replacedStart() >= 0
                    && outcome.appliedVersions().size() == 1;
            if (old != null && before != null && singleSpan) {
                // Incremental path: only the minimal affected region is
                // re-lexed/re-parsed; the analyzer itself validates the result
                // against a full rebuild and falls back on any discrepancy.
                model = incrementalAnalyzer.reanalyze(old.model(), after.version(),
                        after.text(), outcome.replacedStart(),
                        outcome.replacedOldEnd(), outcome.replacedNewEnd());
                List<String> touched = engine.namesTouchedByEdit(old.model(),
                        outcome.replacedStart(), outcome.replacedOldEnd(),
                        insertedText(before.text(), outcome));
                recency.recordEdit(touched);
            } else {
                // Chain drain / full sync / first model: rebuild from scratch.
                model = incrementalAnalyzer.fullAnalyzer()
                        .analyze(uri, after.version(), after.text());
            }
            derived.put(uri, new Derived(after, model, recency));
        } finally {
            lock.unlock();
        }
    }


    private String insertedText(String oldText, ChangeOutcome outcome) {
        // best-effort: use post-commit text window of the replacement
        DocumentSnapshot after = documents.snapshot(outcome.uri()).orElse(null);
        if (after == null) {
            return "";
        }
        int s = Math.max(0, outcome.replacedStart());
        int e = Math.min(after.text().length(), outcome.replacedNewEnd());
        return e >= s ? after.text().substring(s, e) : "";
    }

    // ------------------------------------------------------------------
    // Queries
    // ------------------------------------------------------------------

    public CompletableFuture<CompletionResponse> completeAsync(CompletionRequest request) {
        long requestId = requestIds.incrementAndGet();
        Optional<DocumentSnapshot> pinned = documents.snapshot(request.uri());
        if (pinned.isEmpty()) {
            return CompletableFuture.completedFuture(closedResponse(request));
        }
        ensureDerived(request.uri());
        final DocumentSnapshot snapshot = pinned.get();

        CompletableFuture<CompletionResponse> response = new CompletableFuture<>();
        QueryExecutor.Submission<QueryWork> submission = queryExecutor.submit(
                task -> runQuery(task, request, snapshot, requestId));
        QueryTask task = submission.task();
        running.put(requestId, task);
        requestDocuments.put(requestId, request.uri());
        documentRequests.computeIfAbsent(request.uri(),
                u -> ConcurrentHashMap.newKeySet()).add(requestId);

        // On timeout: mark the task cancelled and resolve with a definite
        // TIMEOUT (never a partial result). The worker observes cancellation at
        // its next checkpoint and performs no cache/result publication.
        java.util.concurrent.ScheduledFuture<?> timeout = scheduler.schedule(() -> {
            if (task.cancel()) {
                response.complete(terminalResponse(request, snapshot.version(),
                        CompletionStatus.TIMEOUT));
            }
        }, properties.getQueryTimeoutMs(), TimeUnit.MILLISECONDS);

        submission.future().whenComplete((work, error) -> {
            running.remove(requestId);
            String uri = requestDocuments.remove(requestId);
            if (uri != null) {
                java.util.Set<Long> ids = documentRequests.get(uri);
                if (ids != null) {
                    ids.remove(requestId);
                }
            }
            timeout.cancel(false);
            if (response.isDone()) {
                return; // timeout/cancellation already produced a terminal result
            }
            if (error != null) {
                response.complete(terminalResponse(request, snapshot.version(),
                        unwrapStatus(error)));
            } else {
                response.complete(toResponse(request, snapshot, work));
            }
        });
        return response;
    }

    private record QueryWork(String prefix, List<CompletionItem> items) {
    }

    private QueryWork runQuery(QueryTask task, CompletionRequest request,
                               DocumentSnapshot snapshot, long requestId) {
        task.checkpoint();
        if (delayedUris.isEmpty() || delayedUris.contains(request.uri())) {
            delayHook.await(task);
        }
        Position position = request.position();

        Optional<List<CompletionItem>> cached = cache.get(
                request.uri(), snapshot.version(), position);
        if (cached.isPresent()) {
            task.checkpoint();
            return new QueryWork(prefixOf(snapshot, position), cached.get());
        }

        Derived state = derived.get(request.uri());
        if (state == null || state.snapshot.version() != snapshot.version()) {
            // document changed between snapshot pin and model availability;
            // refuse to mix versions by returning a closed/stale-free empty OK
            return new QueryWork(prefixOf(snapshot, position), List.of());
        }
        task.checkpoint();
        List<CompletionItem> items = engine.complete(
                state.model(), position, state.recency());
        task.checkpoint();
        // Only publish into the cache if the document is still at the version
        // the items were computed for (put-if-newer, version-gated).
        cache.putIfAbsent(request.uri(), snapshot.version(), position, items,
                currentVersion(request.uri()));
        if (task.isCancelled()) {
            throw new QueryCancelledException();
        }
        return new QueryWork(prefixOf(snapshot, position), items);
    }

    private int currentVersion(String uri) {
        return documents.snapshot(uri).map(DocumentSnapshot::version).orElse(Integer.MIN_VALUE);
    }

    private String prefixOf(DocumentSnapshot snapshot, Position position) {
        int offset = snapshot.lineIndex().offset(position);
        String text = snapshot.text();
        int i = offset;
        while (i > 0 && Character.isJavaIdentifierPart(text.charAt(i - 1))) {
            i--;
        }
        return text.substring(i, offset);
    }

    private CompletionResponse toResponse(CompletionRequest request,
                                          DocumentSnapshot snapshot, QueryWork work) {
        return new CompletionResponse(request.uri(), request.position(),
                snapshot.version(), work.prefix(), CompletionStatus.OK, work.items());
    }

    private CompletionResponse terminalResponse(CompletionRequest request, int version,
                                                CompletionStatus status) {
        return new CompletionResponse(request.uri(), request.position(), version,
                "", status, List.of());
    }

    private CompletionResponse closedResponse(CompletionRequest request) {
        return new CompletionResponse(request.uri(), request.position(), 0,
                "", CompletionStatus.CLOSED, List.of());
    }

    private CompletionStatus unwrapStatus(Throwable error) {
        Throwable t = error;
        while (t instanceof ExecutionException || t instanceof java.util.concurrent
                .CompletionException) {
            t = t.getCause();
        }
        if (t instanceof QueryCancelledException
                || t instanceof InterruptedException
                || t instanceof java.util.concurrent.CancellationException) {
            return CompletionStatus.CANCELLED;
        }
        return CompletionStatus.TIMEOUT;
    }

    public boolean cancel(String uri, long requestId) {
        QueryTask task = running.get(requestId);
        return task != null && task.cancel();
    }

    // ------------------------------------------------------------------
    // Persistence / recovery
    // ------------------------------------------------------------------

    private final java.util.concurrent.ScheduledExecutorService scheduler =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "completion-timeout");
                t.setDaemon(true);
                return t;
            });

    private void persist(String uri) {
        if (!store.enabled()) {
            return;
        }
        Derived state = derived.get(uri);
        if (state == null) {
            return;
        }
        store.saveSnapshot(state.snapshot(), state.recency().encodeBatches());
    }

    private void recover() {
        if (!store.enabled()) {
            return;
        }
        for (DocumentStore.StoredDocument stored : store.loadAll()) {
            documents.restore(stored.snapshot());
            String uri = stored.snapshot().uri();
            modelLocks.put(uri, new ReentrantLock());
            SyntaxModel model = incrementalAnalyzer.fullAnalyzer()
                    .analyze(uri, stored.snapshot().version(), stored.snapshot().text());
            RecencyTracker recency =
                    new RecencyTracker(properties.getRecencyWindowEdits());
            recency.restoreBatches(stored.recencyNames());
            derived.put(uri, new Derived(stored.snapshot(), model, recency));
        }
    }

    public List<String> recentNames(String uri) {
        Derived state = derived.get(uri);
        return state == null ? List.of() : state.recency().recentNames();
    }

    public SyntaxModel currentModel(String uri) {
        Derived state = derived.get(uri);
        return state == null ? null : state.model();
    }

    /** Wait for an async response with the configured timeout (tests/clients). */
    public CompletionResponse complete(CompletionRequest request) {
        try {
            return completeAsync(request).get(properties.getQueryTimeoutMs() + 500L,
                    TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return terminalResponse(request, currentVersion(request.uri()),
                    CompletionStatus.CANCELLED);
        } catch (ExecutionException | TimeoutException e) {
            return terminalResponse(request, currentVersion(request.uri()),
                    CompletionStatus.TIMEOUT);
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        queryExecutor.close();
    }
}
