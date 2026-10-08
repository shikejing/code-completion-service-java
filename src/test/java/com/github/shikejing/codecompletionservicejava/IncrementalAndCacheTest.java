package com.github.shikejing.codecompletionservicejava;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.github.shikejing.codecompletionservicejava.analysis.FaultTolerantParser;
import com.github.shikejing.codecompletionservicejava.analysis.IncrementalAnalyzer;
import com.github.shikejing.codecompletionservicejava.analysis.ParsedUnit;
import com.github.shikejing.codecompletionservicejava.completion.CompletionCache;
import com.github.shikejing.codecompletionservicejava.config.ServiceProperties;
import com.github.shikejing.codecompletionservicejava.model.CompletionCandidate;
import com.github.shikejing.codecompletionservicejava.model.CompletionKind;
import com.github.shikejing.codecompletionservicejava.model.Edit;
import com.github.shikejing.codecompletionservicejava.model.Position;
import com.github.shikejing.codecompletionservicejava.model.TextRange;

class IncrementalAndCacheTest {

    private final FaultTolerantParser parser = new FaultTolerantParser();

    private static String flatten(ParsedUnit unit) {
        StringBuilder sb = new StringBuilder();
        collect(unit.rootScope(), sb);
        return sb.toString();
    }

    private static void collect(com.github.shikejing.codecompletionservicejava.analysis.Scope scope,
                                StringBuilder sb) {
        scope.symbols().forEach(s -> sb.append(s.kind()).append(':')
                .append(s.name()).append('@').append(s.startOffset()).append(';'));
        scope.children().forEach(c -> collect(c, sb));
    }

    @Test
    void incrementalResultEqualsFullRebuild() {
        ServiceProperties props = new ServiceProperties();
        IncrementalAnalyzer analyzer = new IncrementalAnalyzer(parser, props);
        String before = """
                public class Demo {
                    int alpha;
                    void m() {
                        int local = 1;
                    }
                }
                """;
        ParsedUnit oldUnit = analyzer.rebuild(before, 0);
        // insert a new local declaration inside m()
        int insertPos = before.indexOf("int local");
        String after = before.substring(0, insertPos)
                + "int inserted = 2;\n        " + before.substring(insertPos);
        TextRange range = new TextRange(new Position(3, 8), new Position(3, 8));
        ParsedUnit incremental = analyzer.update(oldUnit, before,
                List.of(Edit.change(1, "e1", range, "int inserted = 2;\n        ")),
                after, 1);
        ParsedUnit full = analyzer.rebuild(after, 1);
        assertThat(flatten(incremental)).isEqualTo(flatten(full));
        assertThat(incremental.hasSyntaxErrors()).isEqualTo(full.hasSyntaxErrors());
    }

    @Test
    void fullReplacementTriggersFullRebuild() {
        ServiceProperties props = new ServiceProperties();
        IncrementalAnalyzer analyzer = new IncrementalAnalyzer(parser, props);
        ParsedUnit u0 = analyzer.rebuild("class A { }", 0);
        ParsedUnit u1 = analyzer.update(u0, "class A { }",
                List.of(new Edit(1, "full", Edit.Kind.CHANGE, null, null, "class B { int x; }")),
                "class B { int x; }", 1);
        assertThat(u1.fullRebuild()).isTrue();
        assertThat(flatten(u1)).contains("MEMBER_FIELD:x");
    }

    @Test
    void cacheServesByVersionAndLosesEntriesOnInvalidation() {
        ServiceProperties props = new ServiceProperties();
        CompletionCache cache = new CompletionCache(props);
        CompletionCache.Key k1 = new CompletionCache.Key("u", 1, 10, "cfg");
        List<CompletionCandidate> v1 = List.of(new CompletionCandidate(
                "a", CompletionKind.LOCAL_VARIABLE, "int", 1.0, "001|a|0", List.of(), List.of()));
        assertThat(cache.get(k1)).isNull();
        cache.put(k1, v1);
        assertThat(cache.get(k1)).isEqualTo(v1);

        CompletionCache.Key k2 = new CompletionCache.Key("u", 2, 10, "cfg");
        assertThat(cache.get(k2)).isNull();
        cache.invalidateVersionOlderThan("u", 2);
        assertThat(cache.get(k1)).isNull();
    }

    @Test
    void cacheIsBoundedByMaxEntries() {
        ServiceProperties props = new ServiceProperties();
        props.getCache().setMaxEntries(16);
        CompletionCache cache = new CompletionCache(props);
        for (int i = 0; i < 100; i++) {
            cache.put(new CompletionCache.Key("u" + i, 0, 0, "cfg"), List.of());
        }
        assertThat(cache.size()).isLessThanOrEqualTo(16);
    }

    @Test
    void cacheKeyIncludesConfigHash() {
        ServiceProperties props = new ServiceProperties();
        CompletionCache cache = new CompletionCache(props);
        CompletionCache.Key a = new CompletionCache.Key("u", 1, 0, "cfgA");
        CompletionCache.Key b = new CompletionCache.Key("u", 1, 0, "cfgB");
        cache.put(a, List.of());
        assertThat(cache.get(b)).isNull();
    }
}
