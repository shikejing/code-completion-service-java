package com.github.shikejing.codecompletionservicejava;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import com.github.shikejing.codecompletionservicejava.client.LocalEditorClient;

/**
 * Boots the real HTTP server on a random loopback port and drives it with the
 * in-process JDK client: the whole chain stays in this process and on
 * 127.0.0.1, with a temp directory for durability (configured in
 * {@code src/test/resources/application.properties}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LocalLoopbackIntegrationTest {

    @LocalServerPort
    int port;

    private LocalEditorClient client() {
        return new LocalEditorClient("http://127.0.0.1:" + port);
    }

    @Test
    void openEditCompleteAndCloseOverLoopback() {
        LocalEditorClient client = client();
        String uri = "file:///Loop-" + System.nanoTime() + ".java";
        String source = """
                public class Loop {
                    int memberOne;
                    String echo(String argOne) {
                        int localOne = 1;
                        return localOne;
                    }
                }
                """;
        assertThat(client.openDocument(uri, 0, source).statusCode()).isEqualTo(200);
        // duplicate OPEN replay is idempotent
        assertThat(client.openDocument(uri, 0, source).statusCode()).isEqualTo(200);

        // cursor inside echo() with prefix "local" (line 4 is 0-based index 3)
        var response = client.complete(uri, 3, 36, null);
        assertThat(response.statusCode()).isEqualTo(200);
        String body = response.body();
        assertThat(body).contains("\"label\":\"localOne\"");
        assertThat(body).contains("LOCAL_VARIABLE");
        assertThat(body).contains("argOne");
        assertThat(body).contains("memberOne");
        assertThat(body).contains("\"documentVersion\":0");

        var stale = client.change(uri, 0, "stale", 0, 0, 0, 1, "X");
        assertThat(stale.statusCode()).isEqualTo(409);

        var edit = client.change(uri, 1, "e1", 3, 8, 3, 8, "X");
        assertThat(edit.statusCode()).isEqualTo(200);

        var close = client.close(uri, 2);
        assertThat(close.statusCode()).isEqualTo(200);
        assertThat(close.body()).contains("APPLIED");
    }

    @Test
    void repeatedQueriesReturnIdenticalOrderAndCacheIsTransparent() {
        LocalEditorClient client = client();
        String uri = "file:///Stable-" + System.nanoTime() + ".java";
        String source = """
                public class Stable {
                    void m(int zebra, int apple) {
                        int mango = 1;
                        MARK
                    }
                }
                """;
        client.openDocument(uri, 0, source);
        // cursor at end of the MARK token on line 3 (0-based)
        String first = client.complete(uri, 3, 28, null).body();
        for (int i = 0; i < 5; i++) {
            String again = client.complete(uri, 3, 28, null).body();
            assertThat(labels(again)).isEqualTo(labels(first));
        }
        // a subsequent identical request is served from cache without changing results
        String cached = client.complete(uri, 3, 28, null).body();
        assertThat(cached).contains("\"cacheHit\":true");
        assertThat(labels(cached)).isEqualTo(labels(first));
    }

    private static String labels(String body) {
        StringBuilder sb = new StringBuilder();
        int idx = 0;
        while ((idx = body.indexOf("\"label\":", idx)) >= 0) {
            int q1 = body.indexOf('"', idx + 8) + 1;
            int q2 = body.indexOf('"', q1);
            sb.append(body, q1, q2).append('|');
            idx = q2;
        }
        return sb.toString();
    }
}
