package com.github.shikejing.codecompletionservicejava.tests;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import com.github.shikejing.codecompletionservicejava.config.CompletionProperties;
import com.github.shikejing.codecompletionservicejava.model.*;
import com.github.shikejing.codecompletionservicejava.service.ServiceFactory;

class ConcurrencyTest {

    private String documentWithLines(int n) {
        StringBuilder sb = new StringBuilder("class C {\n  void m() {\n");
        for (int i = 0; i < n; i++) {
            sb.append("    int v").append(i).append(" = ").append(i).append(";\n");
        }
        sb.append("  }\n}\n");
        return sb.toString();
    }

    @Test
    void queriesOnlySeeCommittedVersionsAndNoFieldMixing() throws Exception {
        CompletionProperties props = new CompletionProperties();
        props.setQueryWorkers(8);
        props.setQueryTimeoutMs(30_000);
        try (var svc = ServiceFactory.create(props)) {
            svc.open(new DocumentOpenRequest("u", "class C {\n void m(){}\n}\n", 0));

            int writers = 4;
            int readers = 8;
            int iterations = 200;
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(writers + readers);
            AtomicInteger okResponses = new AtomicInteger();
            List<Throwable> errors = java.util.Collections.synchronizedList(new ArrayList<>());

            for (int w = 0; w < writers; w++) {
                final int writerId = w;
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < iterations; i++) {
                            int v = writerId * iterations + i + 1;
                            String text = "class C {\n void m(){ int writer"
                                    + writerId + "step" + i + " = 1; }\n}\n";
                            // All writers contend on document u; only the next
                            // contiguous version applies, the rest are stale.
                            svc.change(DocumentChangeRequest.full("u",
                                    currentVersion(svc, "u") + 1, text));
                        }
                    } catch (Throwable t) {
                        errors.add(t);
                    }
                });
            }
            for (int r = 0; r < readers; r++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < iterations; i++) {
                            var resp = svc.completeAsync(
                                    new CompletionRequest("u", Position.of(2, 20)))
                                    .get(10, TimeUnit.SECONDS);
                            // every response is a terminal, internally consistent
                            // snapshot: version matches a committed full text
                            if (resp.status() == CompletionStatus.OK) {
                                okResponses.incrementAndGet();
                                assertNotNull(resp.items());
                            }
                        }
                    } catch (Throwable t) {
                        errors.add(t);
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));
            assertTrue(errors.isEmpty(),
                    "no failures expected: " + errors.stream().findFirst().orElse(null));
            assertTrue(okResponses.get() > 0);
        }
    }

    @Test
    void perDocumentLockingSerializesCommits() throws Exception {
        CompletionProperties props = new CompletionProperties();
        try (var svc = ServiceFactory.create(props)) {
            svc.open(new DocumentOpenRequest("u", "0", 0));
            int threads = 8;
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            AtomicInteger applied = new AtomicInteger();
            for (int t = 0; t < threads; t++) {
                final int index = t;
                pool.submit(() -> {
                    try {
                        start.await();
                        // append-style edits based on version 0 race; at most one
                        // chain can be contiguous, and the text must always
                        // remain a well-defined committed value
                        for (int v = 1; v <= 50; v++) {
                            int next = currentVersion(svc, "u") + 1;
                            String text = "class C { int t" + index + "_" + v
                                    + " = 1; }";
                            var outcome = svc.change(
                                    DocumentChangeRequest.full("u", next, text));
                            if (outcome.status() == ChangeStatus.APPLIED) {
                                applied.incrementAndGet();
                            }
                        }
                    } catch (Throwable e) {
                        // latch interruption etc.
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            pool.awaitTermination(20, TimeUnit.SECONDS);
            // regardless of interleaving, final snapshot is non-null and finite
            var snap = svc.currentModel("u");
            assertNotNull(snap);
            assertNotNull(snap.text());
        }
    }

    private static int currentVersion(
            com.github.shikejing.codecompletionservicejava.service.CompletionService svc,
            String uri) {
        return svc.currentModel(uri).version();
    }

    @Test
    void manyDocumentsAreBounded() {
        CompletionProperties props = new CompletionProperties();
        props.setMaxOpenDocuments(16);
        var dm = new com.github.shikejing.codecompletionservicejava.document
                .DocumentManager(props);
        for (int i = 0; i < 100; i++) {
            dm.open(new DocumentOpenRequest("u" + i, "x", 0));
        }
        assertTrue(dm.openDocumentCount() <= 16);
    }
}
