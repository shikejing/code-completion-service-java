package com.github.shikejing.codecompletionservicejava.completion;

import org.springframework.stereotype.Service;

import com.github.shikejing.codecompletionservicejava.analysis.AnalysisService;
import com.github.shikejing.codecompletionservicejava.document.CommitEvent;
import com.github.shikejing.codecompletionservicejava.document.DocumentManager;

import jakarta.annotation.PostConstruct;

/**
 * Keeps derived caches consistent with committed edits.
 *
 * <p>Every committed OPEN/CHANGE invalidates completion entries older than
 * the new version and evicts the parsed-unit cache so no stale derived
 * result can ever be served; CLOSE drops all derived state for the document.
 * Because the callback runs inside the document write lock, a concurrent
 * completion query either fully sees the old version or fully the new one.</p>
 */
@Service
public class CacheMaintenanceService {

    private final DocumentManager documentManager;
    private final CompletionCache completionCache;
    private final AnalysisService analysisService;

    public CacheMaintenanceService(DocumentManager documentManager,
                                   CompletionCache completionCache,
                                   AnalysisService analysisService) {
        this.documentManager = documentManager;
        this.completionCache = completionCache;
        this.analysisService = analysisService;
    }

    @PostConstruct
    void register() {
        documentManager.addCommitListener(this::onCommitted);
    }

    private void onCommitted(CommitEvent event) {
        String uri = event.uri();
        if (!event.snapshot().open()) {
            completionCache.invalidate(uri);
            analysisService.invalidate(uri);
            documentManager.evictClosedIfNeeded();
            return;
        }
        completionCache.invalidateVersionOlderThan(uri, event.snapshot().version());
    }
}
