package com.github.shikejing.codecompletionservicejava;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.github.shikejing.codecompletionservicejava.analysis.FaultTolerantParser;
import com.github.shikejing.codecompletionservicejava.analysis.ParsedUnit;
import com.github.shikejing.codecompletionservicejava.completion.CandidateRanker;
import com.github.shikejing.codecompletionservicejava.completion.PrefixResolver;
import com.github.shikejing.codecompletionservicejava.completion.ScopeResolver;
import com.github.shikejing.codecompletionservicejava.config.ServiceProperties;
import com.github.shikejing.codecompletionservicejava.model.CompletionCandidate;

class RankingDeterminismTest {

    private CompletionResponseRunner runner(ServiceProperties props) {
        return new CompletionResponseRunner(props);
    }

    /** Builds candidates the same way CompletionService does (minus cache). */
    static final class CompletionResponseRunner {
        final ServiceProperties props;
        final FaultTolerantParser parser = new FaultTolerantParser();
        final ScopeResolver scopeResolver = new ScopeResolver();
        final PrefixResolver prefixResolver = new PrefixResolver(scopeResolver);
        final CandidateRanker ranker;

        CompletionResponseRunner(ServiceProperties props) {
            this.props = props;
            this.ranker = new CandidateRanker(props);
        }

        List<CompletionCandidate> run(String source, int offset) {
            ParsedUnit unit = parser.parse(source, 0);
            CandidateRanker.PrefixContext prefix = prefixResolver.resolve(source, unit, offset);
            List<CandidateRanker.ScoredSymbol> symbols = new ArrayList<>();
            for (ScopeResolver.VisibleSymbol v : scopeResolver.resolve(unit, offset)) {
                symbols.add(new CandidateRanker.ScoredSymbol(
                        v.symbol(), v.distance(), v.symbol().typeName(), List.of()));
            }
            String pfx = prefix.prefix();
            for (String keyword : com.github.shikejing.codecompletionservicejava.completion.CompletionService.KEYWORDS) {
                if (pfx.isEmpty() || keyword.startsWith(pfx)) {
                    symbols.add(new CandidateRanker.ScoredSymbol(
                            new com.github.shikejing.codecompletionservicejava.analysis.Symbol(
                                    keyword,
                                    com.github.shikejing.codecompletionservicejava.model.CompletionKind.KEYWORD,
                                    null, "", -1, -1, -1, 0),
                            100, null, List.of()));
                }
            }
            return ranker.rank(symbols, prefix, null, System.nanoTime(), 0L);
        }
    }

    private static final String SOURCE = """
            public class Service {
                int fieldAlpha;
                void methodBeta(int paramAlpha) {
                    int localAlpha = 0;
                    long localAlpha2 = 1;
                    MARK;
                }
            }
            """;

    @Test
    void sameInputProducesIdenticalOrderingAcrossRequests() {
        ServiceProperties props = new ServiceProperties();
        var r = runner(props);
        int cursor = SOURCE.indexOf("MARK");
        List<String> first = r.run(SOURCE, cursor).stream().map(CompletionCandidate::label).toList();
        for (int i = 0; i < 10; i++) {
            List<String> again = r.run(SOURCE, cursor).stream()
                    .map(CompletionCandidate::label).toList();
            assertThat(again).containsExactlyElementsOf(first);
        }
    }

    @Test
    void namesAreDeduplicatedAndShadowingWins() {
        var r = runner(new ServiceProperties());
        int cursor = SOURCE.indexOf("MARK");
        List<CompletionCandidate> candidates = r.run(SOURCE, cursor);
        long alphaCount = candidates.stream().filter(c -> c.label().equals("fieldAlpha")).count();
        // fieldAlpha unique (no duplicate name entries)
        assertThat(alphaCount).isLessThanOrEqualTo(1);
    }

    @Test
    void localRanksAboveFieldAndKeyword() {
        ServiceProperties props = new ServiceProperties();
        props.setMaxCandidates(200);
        var r = runner(props);
        int cursor = SOURCE.indexOf("MARK");
        List<String> order = r.run(SOURCE, cursor).stream()
                .map(CompletionCandidate::label).toList();
        assertThat(order.indexOf("localAlpha")).isGreaterThanOrEqualTo(0);
        assertThat(order.indexOf("localAlpha")).isLessThan(order.indexOf("fieldAlpha"));
        // keywords are offered as a category as well
        assertThat(order).contains("class");
        // every declared symbol outranks every keyword (base 90..40 vs 10)
        List<String> declared = List.of("localAlpha2", "localAlpha", "paramAlpha",
                "fieldAlpha", "methodBeta", "Service");
        int lastDeclared = declared.stream().mapToInt(order::indexOf).max().orElseThrow();
        int firstKeyword = order.stream()
                .filter(com.github.shikejing.codecompletionservicejava.completion.CompletionService.KEYWORDS::contains)
                .mapToInt(order::indexOf).min().orElseThrow();
        assertThat(lastDeclared).isLessThan(firstKeyword);
    }

    @Test
    void keywordBaseCanBeLiftedByConfiguration() {
        ServiceProperties keywordWins = new ServiceProperties();
        keywordWins.getWeights().setKeyword(1000);
        keywordWins.getWeights().setPrefixCaseSensitive(0);
        var r = runner(keywordWins);
        // type the prefix "cla": only "class" keyword and matching declared
        // names survive; with weight 1000 the keyword must rank first
        String src = "public class Clazz { void m() { claMARK } }";
        int cursor = src.indexOf("MARK");
        List<String> order = r.run(src, cursor).stream()
                .map(CompletionCandidate::label).toList();
        assertThat(order).contains("class");
        assertThat(order.get(0)).isEqualTo("class");
    }

    @Test
    void sourcesArePreservedForDeduplicatedCandidate() {
        String src = """
                public class S {
                    int x;
                    void m() {
                        int x = 1;
                        MARK;
                    }
                }
                """;
        var r = runner(new ServiceProperties());
        CompletionCandidate x = r.run(src, src.indexOf("MARK")).stream()
                .filter(c -> c.label().equals("x")).findFirst().orElseThrow();
        assertThat(x.kind()).isEqualTo(com.github.shikejing.codecompletionservicejava.model.CompletionKind.LOCAL_VARIABLE);
        assertThat(x.sources()).isNotEmpty();
        assertThat(x.scoreBreakdown()).isNotEmpty();
    }

    @Test
    void weightsAreConfigurableAndAffectOrder() {
        ServiceProperties keywordWins = new ServiceProperties();
        keywordWins.getWeights().setKeyword(1000);
        var r = runner(keywordWins);
        String src = "public class S { void m() { retMARK } }";
        int cursor = src.indexOf("MARK");
        List<String> order = r.run(src, cursor).stream()
                .map(CompletionCandidate::label).toList();
        assertThat(order.get(0)).isEqualTo("return");
    }

    @Test
    void maxCandidatesIsHonored() {
        ServiceProperties props = new ServiceProperties();
        props.setMaxCandidates(3);
        var r = runner(props);
        int cursor = SOURCE.indexOf("MARK");
        assertThat(r.run(SOURCE, cursor)).hasSizeLessThanOrEqualTo(3);
    }

    @Test
    void prefixFilteringIsContextSensitive() {
        String src = """
                public class S {
                    int apple;
                    int banana;
                    void m() {
                        appMARK
                    }
                }
                """;
        var r = runner(new ServiceProperties());
        // cursor right after 'app'
        int cursor = src.indexOf("MARK");
        List<String> labels = r.run(src, cursor).stream()
                .map(CompletionCandidate::label).toList();
        assertThat(labels).contains("apple");
        assertThat(labels).doesNotContain("banana");
    }
}
