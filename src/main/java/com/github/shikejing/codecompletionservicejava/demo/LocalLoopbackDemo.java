package com.github.shikejing.codecompletionservicejava.demo;

import java.util.List;
import com.github.shikejing.codecompletionservicejava.config.CompletionProperties;
import com.github.shikejing.codecompletionservicejava.model.ChangeOutcome;
import com.github.shikejing.codecompletionservicejava.model.CompletionItem;
import com.github.shikejing.codecompletionservicejava.model.CompletionRequest;
import com.github.shikejing.codecompletionservicejava.model.CompletionResponse;
import com.github.shikejing.codecompletionservicejava.model.DocumentChangeRequest;
import com.github.shikejing.codecompletionservicejava.model.DocumentOpenRequest;
import com.github.shikejing.codecompletionservicejava.model.Position;
import com.github.shikejing.codecompletionservicejava.model.TextEdit;
import com.github.shikejing.codecompletionservicejava.service.CompletionService;
import com.github.shikejing.codecompletionservicejava.service.ServiceFactory;

/**
 * In-process, dependency-free demonstration of the full pipeline: the "editor"
 * is just local data structures handed to the service; the "transport" is a
 * direct method call (the HTTP controller exposes the exact same operations
 * over 127.0.0.1). Run with {@code mvn exec:java} or from an IDE.
 */
public final class LocalLoopbackDemo {

    private LocalLoopbackDemo() {
    }

    public static void main(String[] args) {
        CompletionProperties props = new CompletionProperties();
        try (CompletionService service = ServiceFactory.create(props)) {
            String uri = "inmemory://demo/Demo.java";
            service.open(new DocumentOpenRequest(uri,
                    "class Demo {\n  void m() {\n    \n  }\n}\n", 0));

            // 1) normal edit: type a local declaration
            ChangeOutcome v1 = service.change(DocumentChangeRequest.incremental(
                    uri, 1, 0, List.of(TextEdit.of(
                            Position.of(2, 4), Position.of(2, 4),
                            "int counter = 0;"))));
            System.out.println("edit v1 -> " + v1.status());

            // 2) duplicate notification: must be idempotent
            var duplicate = DocumentChangeRequest.incremental(uri, 1, 0,
                    List.of(TextEdit.of(Position.of(2, 4), Position.of(2, 4),
                            "int counter = 0;")));
            System.out.println("duplicate -> " + service.change(duplicate).status());

            // 3) stale edit: rejected by default
            var stale = DocumentChangeRequest.incremental(uri, 9, 0,
                    List.of(TextEdit.of(Position.of(0, 0), Position.of(0, 0),
                            "// stale\n")));
            System.out.println("stale -> " + service.change(stale).status());

            // 4) completion at the cursor inside the method
            CompletionResponse response = service.complete(new CompletionRequest(
                    uri, Position.of(2, 10)));
            System.out.println("completion status=" + response.status()
                    + " prefix='" + response.prefix() + "' candidates="
                    + response.items().size());
            for (CompletionItem item : response.items().stream().limit(8).toList()) {
                System.out.printf("  %-12s %-15s score=%-6.2f source=%s%n",
                        item.label(), item.kind(), item.score(), item.source());
            }
        }
    }
}
