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

class TypeMatchWeightTest {

    @Test
    void desiredTypeAddsTypeMatchBonusToMatchingCandidate() {
        ServiceProperties props = new ServiceProperties();
        var parser = new FaultTolerantParser();
        var scopeResolver = new ScopeResolver();
        var prefixResolver = new PrefixResolver(scopeResolver);
        var ranker = new CandidateRanker(props);

        String src = """
                public class S {
                    int number;
                    String text;
                    void m() {
                        MARK
                    }
                }
                """;
        ParsedUnit unit = parser.parse(src, 0);
        int cursor = src.indexOf("MARK");
        var prefix = prefixResolver.resolve(src, unit, cursor);

        List<CandidateRanker.ScoredSymbol> symbols = new ArrayList<>();
        for (ScopeResolver.VisibleSymbol v : scopeResolver.resolve(unit, cursor)) {
            symbols.add(new CandidateRanker.ScoredSymbol(
                    v.symbol(), v.distance(), v.symbol().typeName(), List.of()));
        }

        List<CompletionCandidate> baseline = ranker.rank(
                symbols, prefix, null, System.nanoTime(), 0L);
        CompletionCandidate textBase = find(baseline, "text");
        CompletionCandidate numberBase = find(baseline, "number");
        // both member fields share the same base score under identical distance
        assertThat(textBase.score()).isEqualTo(numberBase.score());

        List<CompletionCandidate> withDesired = ranker.rank(
                symbols, prefix, "String", System.nanoTime(), 0L);
        CompletionCandidate text = find(withDesired, "text");
        CompletionCandidate number = find(withDesired, "number");
        assertThat(text.score()).isGreaterThan(number.score());
        assertThat(withDesired.indexOf(text)).isLessThan(withDesired.indexOf(number));
        assertThat(text.scoreBreakdown()).anyMatch(line ->
                line.equals("typeMatch(String==String)="
                        + String.format(java.util.Locale.ROOT, "%.1f",
                                props.getWeights().getTypeMatch())));
        assertThat(number.scoreBreakdown()).anyMatch(line ->
                line.equals("typeMatch(String==int)=0.0"));
    }

    private static CompletionCandidate find(List<CompletionCandidate> items, String label) {
        return items.stream().filter(c -> c.label().equals(label)).findFirst().orElseThrow();
    }
}
