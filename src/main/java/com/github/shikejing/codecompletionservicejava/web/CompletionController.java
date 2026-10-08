package com.github.shikejing.codecompletionservicejava.web;

import com.github.shikejing.codecompletionservicejava.model.ChangeOutcome;
import com.github.shikejing.codecompletionservicejava.model.CompletionRequest;
import com.github.shikejing.codecompletionservicejava.model.CompletionResponse;
import com.github.shikejing.codecompletionservicejava.model.DocumentChangeRequest;
import com.github.shikejing.codecompletionservicejava.model.DocumentCloseRequest;
import com.github.shikejing.codecompletionservicejava.model.DocumentOpenRequest;
import com.github.shikejing.codecompletionservicejava.service.CompletionService;
import java.util.concurrent.CompletableFuture;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Loopback HTTP surface. Binds to 127.0.0.1 only (see application.properties).
 */
@RestController
@RequestMapping("/api/documents")
public class CompletionController {

    private final CompletionService service;

    public CompletionController(CompletionService service) {
        this.service = service;
    }

    @PostMapping("/open")
    public void open(@RequestBody DocumentOpenRequest request) {
        service.open(request);
    }

    @PostMapping("/change")
    public ChangeOutcome change(@RequestBody DocumentChangeRequest request) {
        return service.change(request);
    }

    @PostMapping("/close")
    public void close(@RequestBody DocumentCloseRequest request) {
        service.close(request);
    }

    @PostMapping("/completion")
    public CompletableFuture<CompletionResponse> completion(@RequestBody CompletionRequest request) {
        return service.completeAsync(request);
    }
}
