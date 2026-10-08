package com.github.shikejing.codecompletionservicejava.tests;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import com.github.shikejing.codecompletionservicejava.analysis.CompletionEngine;
import com.github.shikejing.codecompletionservicejava.analysis.RecencyTracker;
import com.github.shikejing.codecompletionservicejava.config.CompletionProperties;
import com.github.shikejing.codecompletionservicejava.model.Position;
import com.github.shikejing.codecompletionservicejava.parse.Lexer;
import com.github.shikejing.codecompletionservicejava.parse.Parser;
import com.github.shikejing.codecompletionservicejava.parse.SyntaxAnalyzer;
import com.github.shikejing.codecompletionservicejava.parse.SyntaxModel;

class SmokeTest {

    private SyntaxModel analyze(String text) {
        SyntaxAnalyzer a = new SyntaxAnalyzer(new Lexer(), new Parser());
        return a.analyze("test://S", 1, text);
    }

    @Test
    void completesLocalsAndFieldsWithShadowing() {
        String text = """
                class Foo {
                  int count;
                  void bar(int count) {
                    int local = 1;
                    cou
                  }
                }
                """;
        SyntaxModel model = analyze(text);
        int offset = text.lastIndexOf("cou") + 3;
        Position pos = model.lineIndex().position(offset);
        CompletionEngine engine = new CompletionEngine(new CompletionProperties());
        var items = engine.complete(model, pos,
                new RecencyTracker(10));
        assertTrue(items.stream().anyMatch(i -> i.label().equals("count")),
                "count should be offered");
        var count = items.stream().filter(i -> i.label().equals("count")).findFirst().orElseThrow();
        // innermost 'count' is the parameter, which shadows the field
        assertEquals(com.github.shikejing.codecompletionservicejava.model.SymbolKind.PARAMETER,
                count.kind());
        assertTrue(count.source().startsWith("parameter"));
    }

    @Test
    void unclosedBlockStillCompletes() {
        String text = """
                class Foo {
                  void bar() {
                    int banana = 2;
                    ban
                """;
        SyntaxModel model = analyze(text);
        int offset = text.lastIndexOf("ban") + 3;
        Position pos = model.lineIndex().position(offset);
        CompletionEngine engine = new CompletionEngine(new CompletionProperties());
        var items = engine.complete(model, pos, new RecencyTracker(10));
        assertTrue(items.stream().anyMatch(i -> i.label().equals("banana")));
    }

    @Test
    void deterministicOrder() {
        String text = """
                class Foo {
                  void m() {
                    int apple = 1;
                    int apricot = 2;
                    ap
                  }
                }
                """;
        SyntaxModel model = analyze(text);
        int offset = text.lastIndexOf("ap") + 2;
        Position pos = model.lineIndex().position(offset);
        CompletionEngine engine = new CompletionEngine(new CompletionProperties());
        List<?> first = engine.complete(model, pos, new RecencyTracker(10));
        List<?> second = engine.complete(model, pos, new RecencyTracker(10));
        assertEquals(first, second);
    }
}
