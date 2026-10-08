package com.github.shikejing.codecompletionservicejava.web;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.github.shikejing.codecompletionservicejava.config.ServiceProperties;
import com.github.shikejing.codecompletionservicejava.document.DocumentManager;
import com.github.shikejing.codecompletionservicejava.model.CompletionResponse;
import com.github.shikejing.codecompletionservicejava.model.DocumentSnapshot;
import com.github.shikejing.codecompletionservicejava.model.Edit;
import com.github.shikejing.codecompletionservicejava.model.EditOutcome;
import com.github.shikejing.codecompletionservicejava.runtime.QueryExecutor;
import com.github.shikejing.codecompletionservicejava.runtime.RequestRegistry;

/**
 * Local-loopback HTTP surface of the completion service.
 *
 * <pre>
 * POST   /api/documents/{uri}/edits     open / incremental change / close
 * POST   /api/documents/completions     completion query (timeout enforced)
 * GET    /api/documents/{uri}/snapshot  current committed snapshot
 * DELETE /api/documents/{uri}           close the document
 * POST   /api/requests/{id}/cancel      cancel an in-flight request
 * </pre>
 */
@RestController
@RequestMapping("/api/documents")
public class CompletionController {

    private final DocumentManager documentManager;
    private final QueryExecutor queryExecutor;
    private final RequestRegistry requestRegistry;
    private final ServiceProperties properties;
    private final String configHash;

    public CompletionController(DocumentManager documentManager,
                                QueryExecutor queryExecutor,
                                RequestRegistry requestRegistry,
                                ServiceProperties properties) {
        this.documentManager = documentManager;
        this.queryExecutor = queryExecutor;
        this.requestRegistry = requestRegistry;
        this.properties = properties;
        this.configHash = ConfigurationHasher.hash(properties);
    }

    @PostMapping("/{uri}/edits")
    public ResponseEntity<EditOutcome> applyEdit(@PathVariable String uri,
                                                 @RequestBody EditRequestDto body) {
        if (body == null || body.kind() == null) {
            return ResponseEntity.badRequest().body(EditOutcome.rejected(
                    EditOutcome.Status.REJECTED_INVALID_RANGE, -1L, "missing edit kind"));
        }
        Edit edit = body.toEdit();
        EditOutcome outcome = documentManager.applyEdit(UriEncoding.decode(uri), edit);
        HttpStatus status = switch (outcome.status()) {
            case APPLIED, DUPLICATE -> HttpStatus.OK;
            case REJECTED_STALE, REJECTED_VERSION_GAP -> HttpStatus.CONFLICT;
            case REJECTED_INVALID_RANGE -> HttpStatus.BAD_REQUEST;
            case REJECTED_CLOSED -> HttpStatus.GONE;
            case REJECTED_LIMIT -> HttpStatus.TOO_MANY_REQUESTS;
        };
        return ResponseEntity.status(status).body(outcome);
    }

    @PostMapping("/completions")
    public CompletionResponse complete(@RequestBody CompletionRequestDto body) {
        long timeout = body.timeoutMillis() == null
                ? properties.getRequestTimeoutMillis()
                : Math.min(body.timeoutMillis(), properties.getRequestTimeoutMillis() * 10L);
        return queryExecutor.submit(body.toRequest(
                properties.getRequestTimeoutMillis(), configHash), timeout);
    }

    @GetMapping("/{uri}/snapshot")
    public DocumentSnapshot snapshot(@PathVariable String uri) {
        return documentManager.getSnapshot(UriEncoding.decode(uri));
    }

    @DeleteMapping("/{uri}")
    public ResponseEntity<Void> close(@PathVariable String uri) {
        String decoded = UriEncoding.decode(uri);
        EditOutcome outcome = documentManager.applyEdit(decoded, Edit.close(
                Math.max(0, versionOf(decoded))));
        return outcome.status() == EditOutcome.Status.APPLIED
                ? ResponseEntity.noContent().build() : ResponseEntity.status(HttpStatus.CONFLICT).build();
    }

    private long versionOf(String uri) {
        DocumentSnapshot snapshot = documentManager.getSnapshot(uri);
        return snapshot == null ? 0 : snapshot.version();
    }
}
