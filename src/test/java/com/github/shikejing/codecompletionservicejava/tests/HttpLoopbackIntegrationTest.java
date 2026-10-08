package com.github.shikejing.codecompletionservicejava.tests;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import com.github.shikejing.codecompletionservicejava.model.*;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end test over the loopback HTTP surface. The server binds to
 * 127.0.0.1 with a random port; nothing leaves the process/loopback.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HttpLoopbackIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    private String url(String path) {
        return "http://127.0.0.1:" + port + "/api/documents" + path;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> post(String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForObject(url(path), new HttpEntity<>(body, headers), Map.class);
    }

    @Test
    void openChangeCompleteOverHttp() {
        String uri = "file:///Http.java";
        post("/open", Map.of(
                "uri", uri,
                "version", 0,
                "text", "class Http { void m(){ int alpha=1; } }\n"));
        Map<String, Object> change = post("/change", Map.of(
                "uri", uri,
                "version", 1,
                "baseVersion", 0,
                "kind", "FULL",
                "edits", List.of(),
                "fullText",
                "class Http { void m(){ int alpha=1; int beta=2; } }\n"));
        assertEquals("APPLIED", change.get("status"));

        // completion right at the end of the method body
        Map<String, Object> resp = post("/completion", Map.of(
                "uri", uri,
                "position", Map.of("line", 0, "character", 40)));
        assertEquals("OK", resp.get("status"));
        List<Map<String, Object>> items = (List<Map<String, Object>>) resp.get("items");
        assertNotNull(items);
        assertTrue(items.stream().anyMatch(i -> "beta".equals(i.get("label"))));
        assertTrue(items.stream().anyMatch(i -> "alpha".equals(i.get("label"))));

        post("/close", Map.of("uri", uri));
        Map<String, Object> after = post("/completion", Map.of(
                "uri", uri,
                "position", Map.of("line", 0, "character", 0)));
        assertEquals("CLOSED", after.get("status"));
        assertTrue(((List<?>) after.get("items")).isEmpty());
    }
}
