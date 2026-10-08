package com.github.shikejing.codecompletionservicejava.service;

import com.github.shikejing.codecompletionservicejava.analysis.CompletionEngine;
import com.github.shikejing.codecompletionservicejava.cache.CompletionCache;
import com.github.shikejing.codecompletionservicejava.config.CompletionProperties;
import com.github.shikejing.codecompletionservicejava.document.DocumentManager;
import com.github.shikejing.codecompletionservicejava.parse.IncrementalAnalyzer;
import com.github.shikejing.codecompletionservicejava.parse.Lexer;
import com.github.shikejing.codecompletionservicejava.parse.Parser;
import com.github.shikejing.codecompletionservicejava.parse.SyntaxAnalyzer;
import com.github.shikejing.codecompletionservicejava.persistence.DocumentStore;
import com.github.shikejing.codecompletionservicejava.persistence.FileDocumentStore;
import com.github.shikejing.codecompletionservicejava.persistence.NoopDocumentStore;
import com.github.shikejing.codecompletionservicejava.runtime.QueryExecutor;

/**
 * Single place assembling the service graph (useful for tests and the
 * local in-process client without starting a web server).
 */
public final class ServiceFactory {

    private ServiceFactory() {
    }

    public static CompletionService create(CompletionProperties properties) {
        DocumentStore store = properties.getDataDir() == null || properties.getDataDir().isBlank()
                ? new NoopDocumentStore()
                : new FileDocumentStore(properties.getDataDir());
        DocumentManager documents = new DocumentManager(properties);
        Lexer lexer = new Lexer();
        Parser parser = new Parser();
        SyntaxAnalyzer full = new SyntaxAnalyzer(lexer, parser);
        IncrementalAnalyzer incremental = new IncrementalAnalyzer(full);
        CompletionEngine engine = new CompletionEngine(properties);
        CompletionCache cache = new CompletionCache(properties.getCompletionCacheSize());
        QueryExecutor executor = new QueryExecutor(properties);
        return new CompletionService(properties, documents, incremental, engine,
                cache, executor, store);
    }
}
