package com.github.shikejing.codecompletionservicejava.client;

import java.net.http.HttpResponse;

/**
 * End-to-end demo that talks to the service exclusively over the local
 * loopback: opens a document, performs out-of-order/duplicate edits, asks
 * for completions (locals, params, fields, methods, types, keywords), and
 * prints the provenance/score of every candidate.
 *
 * <p>Run the application with {@code --demo} (handled in
 * {@code Application}); it never contacts any remote service.</p>
 */
public final class DemoScenario {

    private DemoScenario() {}

    public static void run(int port) {
        LocalEditorClient client = new LocalEditorClient("http://127.0.0.1:" + port);
        String uri = "demo://Hello.java";
        String source = """
                public class Hello {
                    private int counter;
                    String greet(String name) {
                        int local = 1;
                        String message = "hi " + name;
                        return message;
                    }
                }
                """;
        HttpResponse<String> opened = client.openDocument(uri, 0, source);
        System.out.println("open -> " + opened.statusCode() + " " + opened.body());

        // duplicate replay of the same OPEN must be idempotent (already at v0)
        HttpResponse<String> dup = client.openDocument(uri, 0, source);
        System.out.println("duplicate open -> " + dup.statusCode() + " " + dup.body());

        // stale version must be rejected, not silently applied
        HttpResponse<String> stale = client.change(uri, 0, "stale", 0, 0, 0, 0, "x");
        System.out.println("stale change -> " + stale.statusCode() + " " + stale.body());

        // cursor right after "messa" inside greet()
        HttpResponse<String> completion = client.complete(uri, 5, 30, null);
        System.out.println("completion -> " + completion.statusCode());
        System.out.println(completion.body());

        HttpResponse<String> closed = client.close(uri, 1);
        System.out.println("close -> " + closed.statusCode());
    }
}
