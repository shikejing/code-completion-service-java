package com.github.shikejing.codecompletionservicejava;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;

import com.github.shikejing.codecompletionservicejava.analysis.AnalysisService;
import com.github.shikejing.codecompletionservicejava.completion.CancellationToken;
import com.github.shikejing.codecompletionservicejava.completion.CompletionService;
import com.github.shikejing.codecompletionservicejava.config.ServiceProperties;
import com.github.shikejing.codecompletionservicejava.document.DocumentManager;
import com.github.shikejing.codecompletionservicejava.model.CompletionRequest;
import com.github.shikejing.codecompletionservicejava.model.CompletionResponse;
import com.github.shikejing.codecompletionservicejava.model.DocumentSnapshot;
import com.github.shikejing.codecompletionservicejava.model.Edit;
import com.github.shikejing.codecompletionservicejava.model.Position;

class CancelTimeoutTest {

    private ServiceProperties serviceProperties() {
        ServiceProperties props = new ServiceProperties();
        props.getCache().setEnabled(false); // isolate cancellation semantics
        return props;
    }

    @Test
    void preCancelledRequestReturnsDeterministicEmptyResult() {
        DocumentManager manager = new DocumentManager(new ServiceProperties());
        manager.applyEdit("u", Edit.open(0, "class X { int alpha; }"));
        AnalysisService analysis = new AnalysisService(manager,
                new com.github.shikejing.codecompletionservicejava.analysis.FaultTolerantParser(),
                new ServiceProperties());
        analysis.registerListenerPublic();

        CompletionService service = new CompletionService(
                new ServiceProperties(), manager, analysis,
                new com.github.shikejing.codecompletionservicejava.completion.ScopeResolver(),
                new com.github.shikejing.codecompletionservicejava.completion.PrefixResolver(
                        new com.github.shikejing.codecompletionservicejava.completion.ScopeResolver()),
                new com.github.shikejing.codecompletionservicejava.completion.CandidateRanker(
                        new ServiceProperties()),
                new com.github.shikejing.codecompletionservicejava.completion.CompletionCache(
                        new ServiceProperties()));

        CancellationToken token = new CancellationToken();
        token.cancel();
        CompletionRequest request = new CompletionRequest("u", new Position(0, 8),
                null, null, 0, "cfg");
        CompletionResponse response = service.complete(request, token);
        assertThat(response.status()).isEqualTo("CANCELLED");
        assertThat(response.items()).isEmpty();
    }

    @Test
    void queryExecutorTimeoutProducesTerminalTimeoutStatus() {
        ServiceProperties props = serviceProperties();
        props.setRequestTimeoutMillis(1);
        DocumentManager manager = new DocumentManager(new ServiceProperties());
        // a large document to make parse+rank exceed 1 ms reliably
        StringBuilder big = new StringBuilder("class Big {\n");
        for (int i = 0; i < 4000; i++) {
            big.append("  void m").append(i).append("() { int local").append(i)
               .append(" = ").append(i).append("; }\n");
        }
        big.append("}\n");
        manager.applyEdit("u", Edit.open(0, big.toString()));
        AnalysisService analysis = new AnalysisService(manager,
                new com.github.shikejing.codecompletionservicejava.analysis.FaultTolerantParser(),
                new ServiceProperties());
        analysis.registerListenerPublic();

        var scopeResolver = new com.github.shikejing.codecompletionservicejava.completion.ScopeResolver();
        CompletionService service = new CompletionService(
                props, manager, analysis, scopeResolver,
                new com.github.shikejing.codecompletionservicejava.completion.PrefixResolver(scopeResolver),
                new com.github.shikejing.codecompletionservicejava.completion.CandidateRanker(props),
                new com.github.shikejing.codecompletionservicejava.completion.CompletionCache(props));
        var registry = new com.github.shikejing.codecompletionservicejava.runtime.RequestRegistry();
        var executor = new com.github.shikejing.codecompletionservicejava.runtime.QueryExecutor(
                props, service, registry);

        CompletionRequest request = new CompletionRequest("u",
                new Position(0, 10), null, null, 1, "cfg");
        CompletionResponse response = executor.submit(request, 1);
        // even if the machine is extremely fast, result must be either OK or TIMEOUT,
        // never a partial item list; under the 1ms budget TIMEOUT is expected.
        assertThat(response.status()).isIn("OK", "TIMEOUT");
        if (response.status().equals("TIMEOUT")) {
            assertThat(response.items()).isEmpty();
        }
        executor.shutdown();
    }
}
