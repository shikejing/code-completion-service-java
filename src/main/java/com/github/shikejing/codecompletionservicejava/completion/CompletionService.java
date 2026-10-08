package com.github.shikejing.codecompletionservicejava.completion;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.github.shikejing.codecompletionservicejava.analysis.AnalysisService;
import com.github.shikejing.codecompletionservicejava.analysis.ParsedUnit;
import com.github.shikejing.codecompletionservicejava.analysis.Symbol;
import com.github.shikejing.codecompletionservicejava.config.ServiceProperties;
import com.github.shikejing.codecompletionservicejava.document.DocumentManager;
import com.github.shikejing.codecompletionservicejava.document.LineIndex;
import com.github.shikejing.codecompletionservicejava.model.CompletionCandidate;
import com.github.shikejing.codecompletionservicejava.model.CompletionKind;
import com.github.shikejing.codecompletionservicejava.model.CompletionRequest;
import com.github.shikejing.codecompletionservicejava.model.CompletionResponse;
import com.github.shikejing.codecompletionservicejava.model.DocumentSnapshot;
import com.github.shikejing.codecompletionservicejava.model.SymbolSource;

/**
 * Deterministic completion pipeline.
 *
 * <ol>
 *   <li>Read one immutable committed {@link DocumentSnapshot} under the
 *       document read lock; every derived value in the response belongs to
 *       exactly that version.</li>
 *   <li>Check the version/position/config keyed cache; on hit return the
 *       cached immutable list (cache hits cannot change results).</li>
 *   <li>Resolve the prefix (incl. {@code .} member access), collect visible
 *   symbols with shadowing, and rank them with configured weights.</li>
 * </ol>
 *
 * Cancellation is cooperative: the token is checked between stages. A
 * cancelled request returns a {@code CANCELLED} response with an empty list
 * and performs no cache write or other side effect.
 */
@Service
public class CompletionService {

    /** A conservative, completion-oriented keyword set. */
    public static final Set<String> KEYWORDS = Set.of(
            "abstract", "boolean", "break", "byte", "case", "catch", "char", "class",
            "continue", "default", "do", "double", "else", "enum", "extends", "final",
            "finally", "float", "for", "if", "implements", "import", "instanceof",
            "int", "interface", "long", "native", "new", "package", "private",
            "protected", "public", "return", "short", "static", "strictfp", "super",
            "switch", "synchronized", "this", "throw", "throws", "transient", "try",
            "void", "volatile", "while", "var", "record", "true", "false", "null");

    private final ServiceProperties properties;
    private final DocumentManager documentManager;
    private final AnalysisService analysisService;
    private final ScopeResolver scopeResolver;
    private final PrefixResolver prefixResolver;
    private final CandidateRanker ranker;
    private final CompletionCache cache;

    public CompletionService(ServiceProperties properties,
                             DocumentManager documentManager,
                             AnalysisService analysisService,
                             ScopeResolver scopeResolver,
                             PrefixResolver prefixResolver,
                             CandidateRanker ranker,
                             CompletionCache cache) {
        this.properties = properties;
        this.documentManager = documentManager;
        this.analysisService = analysisService;
        this.scopeResolver = scopeResolver;
        this.prefixResolver = prefixResolver;
        this.ranker = ranker;
        this.cache = cache;
    }

    public CompletionResponse complete(CompletionRequest request) {
        return complete(request, new CancellationToken());
    }

    public CompletionResponse complete(CompletionRequest request, CancellationToken token) {
        long start = System.nanoTime();
        try {
            // 1. committed snapshot (read-committed); fixed for the whole request
            DocumentSnapshot snapshot = documentManager.getSnapshot(request.uri());
            if (snapshot == null) {
                return emptyResponse(request, -1, 0, "", false, "UNKNOWN_DOCUMENT",
                        "document is not open", start);
            }
            token.throwIfCancelled();

            ParsedUnit unit = analysisService.unitFor(snapshot);
            int offset = new LineIndex(snapshot.text()).offset(request.position());
            CandidateRanker.PrefixContext prefix =
                    prefixResolver.resolve(snapshot.text(), unit, offset);

            if (prefix.prefix().length() < properties.getMinPrefixLength()) {
                return emptyResponse(request, snapshot.version(), offset, prefix.prefix(),
                        false, "OK", "prefix shorter than min-prefix-length", start);
            }

            CompletionCache.Key cacheKey = new CompletionCache.Key(
                    request.uri(), snapshot.version(), offset,
                    request.configHash() == null ? "" : request.configHash());
            List<CompletionCandidate> cached = cache.get(cacheKey);
            boolean cacheHit = cached != null;
            if (!cacheHit) {
                cached = computeCandidates(snapshot, unit, offset, prefix, request, token);
                // cancellation after compute: no side effects at all
                token.throwIfCancelled();
                cache.put(cacheKey, cached);
            }

            boolean truncated = cached.size() >= properties.getMaxCandidates();
            return new CompletionResponse(request.uri(), snapshot.version(),
                    request.position(), prefix.prefix(), cached, truncated, cacheHit,
                    System.nanoTime() - start, "OK", "ok", request.requestId());
        } catch (CompletionCancelledException cancelled) {
            return emptyResponse(request, -1, 0, "", false, "CANCELLED",
                    "request cancelled before producing a result", start);
        } catch (CompletionTimeoutException timeout) {
            return emptyResponse(request, -1, 0, "", false, "TIMEOUT",
                    timeout.getMessage(), start);
        } catch (RuntimeException error) {
            // A parse/ranking failure must still produce a deterministic answer,
            // never a stack trace to the editor.
            return emptyResponse(request, -1, 0, "", false, "ERROR",
                    error.getClass().getSimpleName() + ": " + error.getMessage(), start);
        }
    }

    private List<CompletionCandidate> computeCandidates(DocumentSnapshot snapshot,
                                                        ParsedUnit unit, int offset,
                                                        CandidateRanker.PrefixContext prefix,
                                                        CompletionRequest request,
                                                        CancellationToken token) {
        token.throwIfCancelled();

        List<CandidateRanker.ScoredSymbol> scored = new ArrayList<>();
        if (prefix.dotMember()) {
            addMemberSymbols(unit, prefix, scored);
        } else {
            for (ScopeResolver.VisibleSymbol visible
                    : scopeResolver.resolve(unit, offset)) {
                scored.add(new CandidateRanker.ScoredSymbol(
                        visible.symbol(), visible.distance(),
                        visible.symbol().typeName(), List.of()));
            }
            addKeywordSymbols(prefix, scored);
        }

        return ranker.rank(scored, prefix, request.desiredType(),
                System.nanoTime(), snapshot.lastEditNanos());
    }

    private void addMemberSymbols(ParsedUnit unit, CandidateRanker.PrefixContext prefix,
                                  List<CandidateRanker.ScoredSymbol> scored) {
        if (prefix.receiverType() == null) {
            return;
        }
        for (Symbol member : unit.membersOf(prefix.receiverType())) {
            scored.add(new CandidateRanker.ScoredSymbol(member, 0, member.typeName(),
                    List.of(new SymbolSource(member.kind(), member.containerName(),
                            member.startOffset(), member.line()))));
        }
    }

    private void addKeywordSymbols(CandidateRanker.PrefixContext prefix,
                                   List<CandidateRanker.ScoredSymbol> scored) {
        String p = prefix.prefix();
        for (String keyword : KEYWORDS) {
            if (p.isEmpty() || keyword.startsWith(p)) {
                Symbol sym = new Symbol(keyword, CompletionKind.KEYWORD,
                        null, "", -1, -1, -1, 0);
                scored.add(new CandidateRanker.ScoredSymbol(sym, 0, null, List.of()));
            }
        }
    }

    private CompletionResponse emptyResponse(CompletionRequest request, long version, int offset,
                                             String prefix, boolean cacheHit,
                                             String status, String message, long startNanos) {
        return new CompletionResponse(request.uri(), version,
                request.position() == null ? new com.github.shikejing.codecompletionservicejava.model.Position(0, 0)
                        : request.position(),
                prefix, List.of(), false, cacheHit,
                System.nanoTime() - startNanos, status, message, request.requestId());
    }
}
