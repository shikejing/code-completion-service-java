package com.github.shikejing.codecompletionservicejava;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.github.shikejing.codecompletionservicejava.analysis.FaultTolerantParser;
import com.github.shikejing.codecompletionservicejava.analysis.ParsedUnit;
import com.github.shikejing.codecompletionservicejava.analysis.Symbol;
import com.github.shikejing.codecompletionservicejava.completion.CandidateRanker;
import com.github.shikejing.codecompletionservicejava.completion.PrefixResolver;
import com.github.shikejing.codecompletionservicejava.completion.ScopeResolver;
import com.github.shikejing.codecompletionservicejava.document.LineIndex;
import com.github.shikejing.codecompletionservicejava.model.CompletionKind;

class ParserScopeTest {

    private final FaultTolerantParser parser = new FaultTolerantParser();
    private final ScopeResolver scopeResolver = new ScopeResolver();

    private int offsetOf(String text, String marker) {
        int idx = text.indexOf(marker);
        if (idx < 0) {
            throw new IllegalArgumentException("marker not found: " + marker);
        }
        return idx;
    }

    @Test
    void parsesFieldsMethodsParametersAndLocals() {
        String src = """
                public class Demo {
                    private int fieldA;
                    String run(String paramB) {
                        int localC = 1;
                        return paramB + fieldA + localC;
                    }
                }
                """;
        ParsedUnit unit = parser.parse(src, 0);
        assertThat(unit.types()).hasSize(1);
        assertThat(unit.types().get(0).simpleName()).isEqualTo("Demo");

        int cursor = offsetOf(src, "return") ;
        List<Symbol> visible = scopeResolver.visibleSymbols(unit, cursor);
        assertThat(visible).extracting(Symbol::name)
                .contains("fieldA", "paramB", "localC", "Demo");
        assertThat(visible).filteredOn(s -> s.name().equals("localC"))
                .singleElement().extracting(Symbol::kind).isEqualTo(CompletionKind.LOCAL_VARIABLE);
        assertThat(visible).filteredOn(s -> s.name().equals("paramB"))
                .singleElement().extracting(Symbol::kind).isEqualTo(CompletionKind.METHOD_PARAMETER);
        assertThat(visible).filteredOn(s -> s.name().equals("fieldA"))
                .singleElement().extracting(Symbol::kind).isEqualTo(CompletionKind.MEMBER_FIELD);
        assertThat(visible).filteredOn(s -> s.name().equals("run"))
                .singleElement().extracting(Symbol::kind).isEqualTo(CompletionKind.METHOD);
    }

    @Test
    void innerScopeShadowsOuterDeclaration() {
        String src = """
                public class Demo {
                    int x = 1;
                    void m() {
                        int x = 2;
                        XMARK;
                    }
                }
                """;
        ParsedUnit unit = parser.parse(src, 0);
        int cursor = offsetOf(src, "XMARK");
        List<ScopeResolver.VisibleSymbol> visible = scopeResolver.resolve(unit, cursor);
        var x = visible.stream().filter(v -> v.symbol().name().equals("x")).toList();
        // single deduplicated name, nearest (local) declaration wins
        assertThat(x).hasSize(1);
        assertThat(x.get(0).symbol().kind()).isEqualTo(CompletionKind.LOCAL_VARIABLE);
    }

    @Test
    void unclosedBlockStillYieldsDeterministicSymbols() {
        String src = """
                public class Demo {
                    void m() {
                        int openLocal = 1;
                        if (true) {
                            int nested = 2;
                """;
        ParsedUnit unit = parser.parse(src, 0);
        assertThat(unit.hasSyntaxErrors()).isTrue();
        int cursor = src.length();
        List<Symbol> visible = scopeResolver.visibleSymbols(unit, cursor);
        assertThat(visible).extracting(Symbol::name).contains("openLocal", "nested", "m");
    }

    @Test
    void garbageTokensDoNotDestroySurroundingDeclarations() {
        String src = """
                public class Demo {
                    void m() {
                        @@@ int validLocal = 0;
                        VALIDHERE;
                    }
                }
                """;
        ParsedUnit unit = parser.parse(src, 0);
        int cursor = offsetOf(src, "VALIDHERE");
        List<Symbol> visible = scopeResolver.visibleSymbols(unit, cursor);
        assertThat(visible).extracting(Symbol::name).contains("validLocal");
    }

    @Test
    void keywordsAndTypesAreClassified() {
        String src = "public class Demo { }";
        ParsedUnit unit = parser.parse(src, 0);
        assertThat(scopeResolver.visibleSymbols(unit, src.length()))
                .filteredOn(s -> s.name().equals("Demo"))
                .singleElement().extracting(Symbol::kind).isEqualTo(CompletionKind.TYPE);
    }

    @Test
    void completionIsNotTheFullSymbolTableAtWrongScope() {
        String src = """
                public class Demo {
                    int firstField;
                    void a() { int onlyInA = 1; }
                    void b() {
                        ONLYB;
                    }
                }
                """;
        ParsedUnit unit = parser.parse(src, 0);
        int cursor = offsetOf(src, "ONLYB");
        List<String> names = scopeResolver.visibleSymbols(unit, cursor).stream()
                .map(Symbol::name).toList();
        assertThat(names).doesNotContain("onlyInA"); // not a context-free dump
        assertThat(names).contains("firstField", "Demo");
    }

    @Test
    void memberCompletionResolvesReceiverType() {
        String src = """
                public class Box {
                    int boxField;
                    void boxMethod() {}
                }
                class Use {
                    void m() {
                        Box b;
                        b.DOTMARK
                    }
                }
                """;
        ParsedUnit unit = parser.parse(src, 0);
        int cursor = offsetOf(src, "DOTMARK") + "DOTMARK".length();
        PrefixResolver resolver = new PrefixResolver(scopeResolver);
        CandidateRanker.PrefixContext ctx = resolver.resolve(src, unit, cursor);
        assertThat(ctx.dotMember()).isTrue();
        assertThat(ctx.receiverType()).isEqualTo("Box");
        List<Symbol> members = unit.membersOf("Box");
        assertThat(members).extracting(Symbol::name).contains("boxField", "boxMethod");
    }

    @Test
    void lineIndexRoundTripsOffsetsAndPositions() {
        String text = "abc\ndefgh\nz";
        LineIndex index = new LineIndex(text);
        // "abc\n" is 4 chars, line 1 starts at offset 4; char 3 -> 7 ('f')
        assertThat(text.charAt(7)).isEqualTo('g'); // d=4,e=5,f=6,g=7
        assertThat(index.offset(new com.github.shikejing.codecompletionservicejava.model.Position(1, 3)))
                .isEqualTo(7);
        assertThat(index.position(0).line()).isZero();
        assertThat(index.position(text.indexOf("z")).line()).isEqualTo(2);
    }
}
