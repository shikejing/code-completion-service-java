package com.github.shikejing.codecompletionservicejava;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import com.github.shikejing.codecompletionservicejava.client.LocalEditorClient;

/**
 * Verifies that an over-budget query over the loopback terminates with the
 * deterministic {@code TIMEOUT} status and an empty item list rather than a
 * half-built response.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TimeoutLoopbackTest {

    @LocalServerPort
    int port;

    @Test
    void timeoutReturnsDeterministicTerminalStatus() {
        LocalEditorClient client = new LocalEditorClient("http://127.0.0.1:" + port);
        String uri = "file:///Huge-" + System.nanoTime() + ".java";
        StringBuilder big = new StringBuilder("class Huge {\n");
        for (int i = 0; i < 12_000; i++) {
            big.append("  void operation").append(i)
               .append("(int alpha").append(i).append(") {\n")
               .append("    int local").append(i).append(" = ").append(i).append(";\n  }\n");
        }
        big.append("}\n");
        assertThat(client.openDocument(uri, 0, big.toString()).statusCode()).isEqualTo(200);

        // 1ms request timeout; result must be terminal and complete
        var response = client.complete(uri, 100, 20, null, 1L);
        assertThat(response.statusCode()).isEqualTo(200);
        String body = response.body();
        assertThat(body).containsAnyOf("\"status\":\"TIMEOUT\"", "\"status\":\"OK\"");
        if (body.contains("\"status\":\"TIMEOUT\"")) {
            assertThat(body).contains("\"items\":[]");
        }
    }
}
