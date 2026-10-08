package com.github.shikejing.codecompletionservicejava.client;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Minimal editor simulation over the local loopback. Uses the JDK
 * {@link HttpClient} and posts JSON; there is no dependency on any external
 * service. The companion tests use this client against a randomly chosen
 * local port.
 */
public class LocalEditorClient {

    private final String baseUrl;
    private final HttpClient http;

    public LocalEditorClient(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
    }

    public String baseUrl() {
        return baseUrl;
    }

    public HttpResponse<String> openDocument(String uri, long version, String text) {
        String body = "{\"version\":" + version + ",\"editId\":\"open-" + version
                + "\",\"kind\":\"OPEN\",\"fullText\":" + Json.quote(text) + "}";
        return post("/api/documents/" + enc(uri) + "/edits", body);
    }

    public HttpResponse<String> change(String uri, long version, String editId,
                                       int startLine, int startChar,
                                       int endLine, int endChar, String text) {
        String body = "{\"version\":" + version
                + ",\"editId\":" + Json.quote(editId)
                + ",\"kind\":\"CHANGE\",\"range\":{"
                + "\"start\":{\"line\":" + startLine + ",\"character\":" + startChar + "},"
                + "\"end\":{\"line\":" + endLine + ",\"character\":" + endChar + "}},"
                + "\"text\":" + Json.quote(text) + "}";
        return post("/api/documents/" + enc(uri) + "/edits", body);
    }

    public HttpResponse<String> close(String uri, long version) {
        String body = "{\"version\":" + version + ",\"editId\":\"close-" + version
                + "\",\"kind\":\"CLOSE\"}";
        return post("/api/documents/" + enc(uri) + "/edits", body);
    }

    public HttpResponse<String> complete(String uri, int line, int character, String desiredType) {
        return complete(uri, line, character, desiredType, null);
    }

    public HttpResponse<String> complete(String uri, int line, int character,
                                         String desiredType, Long timeoutMillis) {
        return complete(uri, line, character, desiredType, timeoutMillis, null);
    }

    public HttpResponse<String> complete(String uri, int line, int character,
                                         String desiredType, Long timeoutMillis,
                                         String requestId) {
        String body = "{\"uri\":" + Json.quote(uri)
                + ",\"position\":{\"line\":" + line + ",\"character\":" + character + "}"
                + (desiredType == null ? "" : ",\"desiredType\":" + Json.quote(desiredType))
                + (timeoutMillis == null ? "" : ",\"timeoutMillis\":" + timeoutMillis)
                + (requestId == null ? "" : ",\"requestId\":" + Json.quote(requestId))
                + "}";
        return post("/api/documents/completions", body);
    }

    public HttpResponse<String> cancel(String requestId) {
        return post("/api/requests/" + requestId + "/cancel", "{}");
    }

    public HttpResponse<String> snapshot(String uri) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/documents/" + enc(uri) + "/snapshot"))
                    .timeout(Duration.ofSeconds(2))
                    .GET().build();
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    private HttpResponse<String> post(String path, String body) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String enc(String value) {
        // path-safe token encoding matching UriEncoding on the server
        return java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    static final class Json {
        private Json() {}
        static String quote(String s) {
            if (s == null) return "null";
            StringBuilder sb = new StringBuilder("\"");
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"' -> sb.append("\\\"");
                    case '\\' -> sb.append("\\\\");
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    default -> sb.append(c < 0x20 ? String.format("\\u%04x", (int) c) : String.valueOf(c));
                }
            }
            return sb.append('"').toString();
        }
    }
}
