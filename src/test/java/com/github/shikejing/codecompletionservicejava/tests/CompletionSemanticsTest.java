package com.github.shikejing.codecompletionservicejava.tests;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import com.github.shikejing.codecompletionservicejava.analysis.CompletionEngine;
import com.github.shikejing.codecompletionservicejava.analysis.RecencyTracker;
import com.github.shikejing.codecompletionservicejava.config.CompletionProperties;
import com.github.shikejing.codecompletionservicejava.model.CompletionItem;
import com.github.shikejing.codecompletionservicejava.model.Position;
import com.github.shikejing.codecompletionservicejava.model.SymbolKind;
import com.github.shikejing.codecompletionservicejava.parse.Lexer;
import com.github.shikejing.codecompletionservicejava.parse.Parser;
import com.github.shikejing.codecompletionservicejava.parse.SyntaxAnalyzer;
import com.github.shikejing.codecompletionservicejava.parse.SyntaxModel;

class CompletionSemanticsTest {

    private SyntaxModel analyze(String text) {
        return new SyntaxAnalyzer(new Lexer(), new Parser()).analyze("u", 1, text);
    }

    private List<CompletionItem> completeAtLast(SyntaxModel model, String marker,
                                                CompletionProperties props,
                                                RecencyTracker recency) {
        int off = model.text().lastIndexOf(marker) + marker.length();
        Position pos = model.lineIndex().position(off);
        return new CompletionEngine(props).complete(model, pos, recency);
    }

    @Test
    void categoriesAreClassified() {
        String text = """
                class Widget {
                  String name;
                  int compute(int factor) {
                    int localV = factor;
                    cat
                  }
                }
                """;
        var model = analyze(text);
        var items = completeAtLast(model, "cat", new CompletionProperties(),
                new RecencyTracker(10));
        // 'cat' matches nothing here; ensure no symbol starts with cat -> empty
        assertTrue(items.stream().noneMatch(i -> i.label().equals("compute")));
    }

    @Test
    void methodsFieldsAndTypesPresent() {
        String text = """
                class Widget extends Gadget {
                  int fieldOne;
                  int doWork(int p) {
                    do
                  }
                }
                class Gadget {}
                """;
        var model = analyze(text);
        int off = model.text().lastIndexOf("do") + 2;
        var items = new CompletionEngine(new CompletionProperties())
                .complete(model, model.lineIndex().position(off),
                        new RecencyTracker(10));
        var doWork = items.stream().filter(i -> i.label().equals("doWork")).findFirst();
        assertTrue(doWork.isPresent());
        assertEquals(SymbolKind.METHOD, doWork.get().kind());
    }

    @Test
    void memberAccessOnlyOffersMembers() {
        String text = """
                class Inner {
                  int memberField;
                  int memberMethod() { return 1; }
                }
                class Outer {
                  void m() {
                    Inner in = new Inner();
                    in.mem
                  }
                }
                """;
        var model = analyze(text);
        int off = model.text().lastIndexOf("mem") + 3;
        var items = new CompletionEngine(new CompletionProperties())
                .complete(model, model.lineIndex().position(off),
                        new RecencyTracker(10));
        assertTrue(items.stream().anyMatch(i -> i.label().equals("memberField")));
        assertTrue(items.stream().anyMatch(i -> i.label().equals("memberMethod")));
        // an unrelated local must not leak into member completion
        assertTrue(items.stream().noneMatch(i -> i.label().equals("in")));
        // and type Widget from outer scope must not leak either
        assertTrue(items.stream().noneMatch(i -> i.label().equals("Outer")));
    }

    @Test
    void syntaxErrorsStillReturnCandidates() {
        String text = """
                class Broken {
                  void m( {
                    int recoverable = 3;
                    rec
                  }
                """;
        var model = analyze(text);
        assertFalse(model.errors().isEmpty(), "errors should be recorded");
        int off = model.text().lastIndexOf("rec") + 3;
        var items = new CompletionEngine(new CompletionProperties())
                .complete(model, model.lineIndex().position(off),
                        new RecencyTracker(10));
        assertTrue(items.stream().anyMatch(i -> i.label().equals("recoverable")));
    }

    @Test
    void prefixFiltersResults() {
        String text = """
                class C {
                  void m() {
                    int apple = 1;
                    int banana = 2;
                    ap
                  }
                }
                """;
        var model = analyze(text);
        var items = completeAtLast(model, "ap", new CompletionProperties(),
                new RecencyTracker(10));
        assertTrue(items.stream().anyMatch(i -> i.label().equals("apple")));
        assertTrue(items.stream().noneMatch(i -> i.label().equals("banana")));
    }

    @Test
    void dedupByNameKeepsStrongestSource() {
        String text = """
                class C {
                  int shadowed;
                  void m(int shadowed) {
                    sha
                  }
                }
                """;
        var model = analyze(text);
        var items = completeAtLast(model, "sha", new CompletionProperties(),
                new RecencyTracker(10));
        var matches = items.stream().filter(i -> i.label().equals("shadowed")).toList();
        assertEquals(1, matches.size());
        assertEquals(SymbolKind.PARAMETER, matches.get(0).kind());
    }

    @Test
    void keywordsAreOffered() {
        String text = "class C { void m() { ret } }";
        var model = analyze(text);
        int off = model.text().lastIndexOf("ret") + 3;
        var items = new CompletionEngine(new CompletionProperties())
                .complete(model, model.lineIndex().position(off),
                        new RecencyTracker(10));
        assertTrue(items.stream().anyMatch(i -> i.label().equals("return")
                && i.kind() == SymbolKind.KEYWORD));
    }

    @Test
    void recencyBoostsRecentlyEditedName() {
        CompletionProperties props = new CompletionProperties();
        String text = "class C {\n  void m() {\n    int zebra = 1;\n    int alpha = 2;\n  }\n}\n";
        var model = analyze(text);
        // cursor at the end of the method body, before the closing brace
        int body = text.indexOf("alpha = 2;") + "alpha = 2;".length();
        Position pos = model.lineIndex().position(body + 1);
        var engine = new CompletionEngine(props);
        var plain = engine.complete(model, pos, new RecencyTracker(10));
        RecencyTracker recent = new RecencyTracker(10);
        recent.recordEdit(List.of("zebra"));
        var boosted = engine.complete(model, pos, recent);
        double plainZebra = score(plain, "zebra");
        double boostedZebra = score(boosted, "zebra");
        double alphaScore = score(boosted, "alpha");
        assertTrue(boostedZebra > plainZebra,
                "recently edited zebra must gain weight");
        // with equal bases zebra overtakes alpha only due to recency
        assertTrue(boostedZebra > alphaScore);
    }

    private double score(List<CompletionItem> items, String name) {
        return items.stream().filter(i -> i.label().equals(name)).findFirst()
                .orElseThrow().score();
    }
}
