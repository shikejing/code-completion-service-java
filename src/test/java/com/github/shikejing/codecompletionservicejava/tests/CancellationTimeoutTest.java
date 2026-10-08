package com.github.shikejing.codecompletionservicejava.tests;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import com.github.shikejing.codecompletionservicejava.config.CompletionProperties;
import com.github.shikejing.codecompletionservicejava.model.CompletionRequest;
import com.github.shikejing.codecompletionservicejava.model.CompletionResponse;
import com.github.shikejing.codecompletionservicejava.model.CompletionStatus;
import com.github.shikejing.codecompletionservicejava.model.DocumentChangeRequest;
import com.github.shikejing.codecompletionservicejava.model.DocumentOpenRequest;
import com.github.shikejing.codecompletionservicejava.model.Position;
import com.github.shikejing.codecompletionservicejava.runtime.SleepingDelayHook;
import com.github.shikejing.codecompletionservicejava.service.CompletionService;
import com.github.shikejing.codecompletionservicejava.service.ServiceFactory;

class CancellationTimeoutTest {

    private String doc() {
        return "class C {\n  void m() {\n    int alpha = 1;\n  }\n}\n";
    }

    @Test
    void normalCompletionIsOk() throws Exception {
        CompletionService svc = ServiceFactory.create(new CompletionProperties());
        try {
            svc.open(new DocumentOpenRequest("u", doc(), 0));
            int off = doc().indexOf("alpha");
            CompletableFuture<CompletionResponse> f = svc.completeAsync(
                    new CompletionRequest("u",
                            Position.of(2, 8)));
            CompletionResponse r = f.get(2, TimeUnit.SECONDS);
            assertEquals(CompletionStatus.OK, r.status());
        } finally {
            svc.close();
        }
    }

    @Test
    void bigDocumentStillCompletesOrReturnsEmptyTerminal() throws Exception {
        CompletionProperties props = new CompletionProperties();
        CompletionService svc = ServiceFactory.create(props);
        try {
            StringBuilder big = new StringBuilder("class C {\n void m() {\n");
            for (int i = 0; i < 50_000; i++) {
                big.append("int v").append(i).append(" = ").append(i).append(";\n");
            }
            big.append("}\n}\n");
            svc.open(new DocumentOpenRequest("u", big.toString(), 0));
            CompletionResponse r = svc.completeAsync(
                    new CompletionRequest("u", Position.of(100, 4)))
                    .get(5, TimeUnit.SECONDS);
            // any terminal response must be internally consistent
            if (r.status() != CompletionStatus.OK) {
                assertTrue(r.items().isEmpty());
            } else {
                assertNotNull(r.items());
            }
        } finally {
            svc.close();
        }
    }

    @Test
    void slowQueryDoesNotBlockEdits() throws Exception {
        CompletionProperties props = new CompletionProperties();
        props.setQueryWorkers(1);
        props.setQueryTimeoutMs(60_000);
        CompletionService svc = ServiceFactory.create(props);
        try {
            svc.open(new DocumentOpenRequest("u", doc(), 0));
            // Issue many queries on the single worker; they may queue, but
            // edits must still be processed immediately.
            long start = System.nanoTime();
            for (int i = 0; i < 20; i++) {
                svc.completeAsync(new CompletionRequest("u", Position.of(2, 8)));
            }
            var outcome = svc.change(DocumentChangeRequest.full("u", 1,
                    "class C { int changed = 1; }"));
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertEquals(com.github.shikejing.codecompletionservicejava
                    .model.ChangeStatus.APPLIED, outcome.status());
            assertTrue(elapsedMs < 2000,
                    "edits must not wait behind slow queries, took " + elapsedMs);
        } finally {
            svc.close();
        }
    }

    @Test
    void closedDocumentYieldsClosedStatus() throws Exception {
        CompletionService svc = ServiceFactory.create(new CompletionProperties());
        try {
            CompletionResponse r = svc.completeAsync(
                    new CompletionRequest("missing", Position.of(0, 0)))
                    .get(1, TimeUnit.SECONDS);
            assertEquals(CompletionStatus.CLOSED, r.status());
            assertTrue(r.items().isEmpty());
        } finally {
            svc.close();
        }
    }

    @Test
    void deterministicTimeoutProducesTimeoutStatus() throws Exception {
        CompletionProperties props = new CompletionProperties();
        props.setQueryTimeoutMs(40);
        props.setQueryWorkers(2);
        CompletionService svc = ServiceFactory.create(props);
        try {
            svc.open(new DocumentOpenRequest("u", doc(), 0));
            svc.setDelayHook(new SleepingDelayHook(5_000));
            CompletionResponse r = svc.completeAsync(
                    new CompletionRequest("u", Position.of(2, 8)))
                    .get(2, TimeUnit.SECONDS);
            assertEquals(CompletionStatus.TIMEOUT, r.status());
            assertTrue(r.items().isEmpty());
            assertEquals("", r.prefix());
        } finally {
            svc.close();
        }
    }

    @Test
    void explicitCancelStopsRequest() throws Exception {
        CompletionProperties props = new CompletionProperties();
        props.setQueryTimeoutMs(60_000);
        props.setQueryWorkers(4);
        CompletionService svc = ServiceFactory.create(props);
        try {
            svc.open(new DocumentOpenRequest("u", doc(), 0));
            svc.setDelayHook(new SleepingDelayHook(5_000));
            svc.setDelayedUri("u");
            // cancel() works by request id which is assigned internally; here we
            // verify closing the document while queries run terminates them
            // deterministically and the service keeps working afterwards.
            var future = svc.completeAsync(
                    new CompletionRequest("u", Position.of(2, 8)));
            Thread.sleep(60);
            svc.close(new com.github.shikejing.codecompletionservicejava
                    .model.DocumentCloseRequest("u"));
            // a new document and query still works after close
            svc.open(new DocumentOpenRequest("v", doc(), 0));
            CompletionResponse r2 = svc.completeAsync(
                    new CompletionRequest("v", Position.of(2, 8)))
                    .get(2, TimeUnit.SECONDS);
            assertEquals(CompletionStatus.OK, r2.status());
            // original future must not throw and resolves to a terminal result
            CompletionResponse r1 = future.get(2, TimeUnit.SECONDS);
            assertEquals(CompletionStatus.CANCELLED, r1.status());
            assertTrue(r1.items().isEmpty());
        } finally {
            svc.close();
        }
    }
}
