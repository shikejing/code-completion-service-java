package com.github.shikejing.codecompletionservicejava.analysis;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import com.github.shikejing.codecompletionservicejava.config.ServiceProperties;
import com.github.shikejing.codecompletionservicejava.document.CommitEvent;
import com.github.shikejing.codecompletionservicejava.document.DocumentManager;
import com.github.shikejing.codecompletionservicejava.model.DocumentSnapshot;

import jakarta.annotation.PostConstruct;

/**
 * Caches the latest {@link ParsedUnit} per document and (re)analyses on every
 * committed snapshot. Analysis always derives from a complete committed
 * snapshot, and a unit is published atomically: queries see either the old
 * unit or the new unit, never a partially updated scope tree.
 */
@Service
public class AnalysisService {

    private static final class State {
        final ParsedUnit unit;
        State(ParsedUnit unit) { this.unit = unit; }
    }

    private final DocumentManager documentManager;
    private final IncrementalAnalyzer analyzer;
    private final ServiceProperties properties;
    private final ConcurrentHashMap<String, State> states = new ConcurrentHashMap<>();

    public AnalysisService(DocumentManager documentManager,
                           FaultTolerantParser parser,
                           ServiceProperties properties) {
        this.documentManager = documentManager;
        this.analyzer = new IncrementalAnalyzer(parser, properties);
        this.properties = properties;
    }

    @PostConstruct
    void registerListener() {
        registerListenerPublic();
    }

    /** Registers the commit listener; also used by non-Spring tests. */
    public void registerListenerPublic() {
        documentManager.addCommitListener(this::onCommitted);
    }

    /** Invoked by DocumentManager while holding the document write lock. */
    private void onCommitted(CommitEvent event) {
        if (!event.snapshot().open()) {
            states.remove(event.uri());
            return;
        }
        State previous = states.get(event.uri());
        ParsedUnit unit = analyzer.update(
                previous == null ? null : previous.unit,
                previous == null ? "" : previous.unit.text(),
                List.of(event.edit()), event.snapshot().text(), event.snapshot().version());
        // atomic publication; an older callback can never overwrite a newer one
        states.merge(event.uri(), new State(unit), (oldValue, newValue) ->
                newValue.unit.version() >= oldValue.unit.version() ? newValue : oldValue);
    }

    /** Analyses the given snapshot on demand (also used for recovery). */
    public ParsedUnit unitFor(DocumentSnapshot snapshot) {
        if (snapshot == null) {
            return null;
        }
        State state = states.get(snapshot.uri());
        if (state != null && state.unit.version() == snapshot.version()
                && state.unit.text().equals(snapshot.text())) {
            return state.unit;
        }
        ParsedUnit fresh = analyzer.rebuild(snapshot.text(), snapshot.version());
        states.merge(snapshot.uri(), new State(fresh), (oldValue, newValue) ->
                newValue.unit.version() >= oldValue.unit.version() ? newValue : oldValue);
        return fresh;
    }

    /** Forces analysis of recovered state at startup. */
    public void bootstrap(DocumentSnapshot snapshot) {
        ParsedUnit unit = analyzer.rebuild(snapshot.text(), snapshot.version());
        states.put(snapshot.uri(), new State(unit));
    }

    public void invalidate(String uri) {
        states.remove(uri);
    }

    public int cachedDocumentCount() {
        return states.size();
    }

    public Map<String, Long> cachedVersions() {
        java.util.Map<String, Long> out = new java.util.HashMap<>();
        states.forEach((k, v) -> out.put(k, v.unit.version()));
        return out;
    }
}
