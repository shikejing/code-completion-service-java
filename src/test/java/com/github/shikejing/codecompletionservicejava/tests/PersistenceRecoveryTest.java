package com.github.shikejing.codecompletionservicejava.tests;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import com.github.shikejing.codecompletionservicejava.config.CompletionProperties;
import com.github.shikejing.codecompletionservicejava.model.*;
import com.github.shikejing.codecompletionservicejava.persistence.FileDocumentStore;
import com.github.shikejing.codecompletionservicejava.service.CompletionService;
import com.github.shikejing.codecompletionservicejava.service.ServiceFactory;

class PersistenceRecoveryTest {

    private CompletionProperties props(Path dir) {
        CompletionProperties p = new CompletionProperties();
        p.setDataDir(dir.toString());
        p.setQueryTimeoutMs(10_000);
        return p;
    }

    @Test
    void restartRestoresLastCommittedTextAndVersion(@TempDir Path dir) {
        String uri = "file:///Demo.java";
        String finalText = "class Demo {\n  int after = 1;\n}\n";
        try (CompletionService svc = ServiceFactory.create(props(dir))) {
            svc.open(new DocumentOpenRequest(uri, "class Demo {}\n", 0));
            svc.change(DocumentChangeRequest.full(uri, 1, "class Demo {\n int x=0;\n}\n"));
            svc.change(DocumentChangeRequest.full(uri, 2, finalText));
        }
        try (CompletionService restored = ServiceFactory.create(props(dir))) {
            var model = restored.currentModel(uri);
            assertNotNull(model);
            assertEquals(2, model.version());
            assertEquals(finalText, model.text());
        }
    }

    @Test
    void replayEditsProducesIdenticalCompletions(@TempDir Path dir) {
        String start = "class R {\n  void m() {\n    int seed = 0;\n  }\n}\n";
        String uri = "file:///R.java";
        List<CompletionResponse> firstRun;
        List<DocumentChangeRequest> replay = List.of(
                DocumentChangeRequest.incremental(uri, 1, 0, List.of(
                        TextEdit.of(Position.of(2, 19), Position.of(2, 19),
                                "\n    int apple = 1;"))),
                DocumentChangeRequest.incremental(uri, 2, 1, List.of(
                        TextEdit.of(Position.of(3, 19), Position.of(3, 19),
                                "\n    int banana = 2;"))));
        try (CompletionService svc = ServiceFactory.create(props(dir))) {
            svc.open(new DocumentOpenRequest(uri, start, 0));
            for (var c : replay) {
                svc.change(c);
            }
            firstRun = List.of(query(svc, uri, Position.of(4, 8)));
        }
        // Fresh process: recovery must restore the last committed document and
        // derived state exactly; no edits are re-applied here.
        try (CompletionService svc = ServiceFactory.create(props(dir))) {
            List<CompletionResponse> secondRun = List.of(query(svc, uri,
                    Position.of(4, 8)));
            assertEquals(firstRun, secondRun,
                    "fixed input + fixed config must replay item-by-item");
        }

        // Separately, in a clean store, re-applying the same batch onto the
        // same start under the same configuration reproduces the result
        // (deterministic replay), independent of any recovered state.
        try (CompletionService svc = ServiceFactory.create(
                props(dir.resolve("replay")))) {
            svc.open(new DocumentOpenRequest(uri, start, 0));
            for (var c : replay) {
                svc.change(c);
            }
            List<CompletionResponse> thirdRun = List.of(query(svc, uri,
                    Position.of(4, 8)));
            assertEquals(firstRun, thirdRun,
                    "replaying the same edits must be reproducible");
        }
    }

    @Test
    void closedDocumentIsRemovedFromStore(@TempDir Path dir) {
        FileDocumentStore store = new FileDocumentStore(dir.toString());
        try (CompletionService svc = ServiceFactory.create(props(dir))) {
            svc.open(new DocumentOpenRequest("file:///T.java", "class T {}", 0));
        }
        FileDocumentStore reopened = new FileDocumentStore(dir.toString());
        assertEquals(1, reopened.loadAll().size());
        try (CompletionService svc = ServiceFactory.create(props(dir))) {
            svc.close(new DocumentCloseRequest("file:///T.java"));
        }
        assertEquals(0, new FileDocumentStore(dir.toString()).loadAll().size());
    }

    @Test
    void tempFilesDoNotLeavePartialSnapshots(@TempDir Path dir) throws Exception {
        try (CompletionService svc = ServiceFactory.create(props(dir))) {
            svc.open(new DocumentOpenRequest("file:///A.java", "class A {}", 0));
            svc.change(DocumentChangeRequest.full("file:///A.java", 1,
                    "class A { int x=1; }"));
        }
        Path docs = dir.resolve("docs");
        try (var stream = Files.list(docs)) {
            stream.forEach(p -> assertTrue(p.toString().endsWith(".snapshot"),
                    "only published snapshots remain: " + p));
        }
    }

    private CompletionResponse query(CompletionService svc, String uri, Position pos) {
        try {
            return svc.completeAsync(new CompletionRequest(uri, pos)).get();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
