package com.github.shikejing.codecompletionservicejava;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import com.github.shikejing.codecompletionservicejava.client.LocalEditorClient;

/**
 * Cancels a request over HTTP while its parse is still running.
 *
 * <p>The document is large enough (~6 MB of declarations, under the 1 MB
 * default? no: worker threads=32 configured and max-document-chars raised)
 * that lexing stays in flight for well over a second, leaving a comfortable,
 * non-racy cancellation window without blocking any worker thread.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "completion.limits.worker-threads=32",
        "completion.limits.max-document-chars=8000000",
        "completion.request-timeout-millis=60000"
})
class CancelLoopbackTest {

    @LocalServerPort
    int port;

    @Test
    void cancelInFlightRequestOverHttp() throws Exception {
        LocalEditorClient client = new LocalEditorClient("http://127.0.0.1:" + port);
        String uri = "file:///Cancel-" + System.nanoTime() + ".java";
        StringBuilder big = new StringBuilder("class Cancel {\n");
        for (int i = 0; i < 100_000; i++) {
            big.append("  void op").append(i).append("(int p").append(i).append(") {\n")
               .append("    int local").append(i).append(" = ").append(i).append(";\n  }\n");
        }
        big.append("}\n");
        assertThat(client.openDocument(uri, 0, big.toString()).statusCode()).isEqualTo(200);

        boolean sawCancelled = false;
        for (int attempt = 0; attempt < 3 && !sawCancelled; attempt++) {
            String requestId = "cancel-me-" + System.nanoTime();
            CompletableFuture<String> future = CompletableFuture.supplyAsync(
                    () -> client.complete(uri, 50_000, 20, null, 60_000L, requestId).body());
            // parse of a ~6MB document takes multiple seconds; 300ms is safely
            // inside that window on any normal CI machine.
            Thread.sleep(300);
            var cancelResponse = client.cancel(requestId);
            assertThat(cancelResponse.statusCode()).isEqualTo(200);
            if (cancelResponse.body().contains("\"cancelled\":true")) {
                String body = future.get(30, TimeUnit.SECONDS);
                assertThat(body).contains("\"status\":\"CANCELLED\"");
                assertThat(body).contains("\"items\":[]");
                sawCancelled = true;
            } else {
                // request already finished (very fast machine): drain and retry
                future.get(30, TimeUnit.SECONDS);
            }
        }
        assertThat(sawCancelled).as("at least one in-flight request was cancelled").isTrue();
    }
}
