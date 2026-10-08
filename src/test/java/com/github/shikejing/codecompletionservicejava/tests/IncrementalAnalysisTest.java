package com.github.shikejing.codecompletionservicejava.tests;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import com.github.shikejing.codecompletionservicejava.model.Position;
import com.github.shikejing.codecompletionservicejava.model.TextEdit;
import com.github.shikejing.codecompletionservicejava.parse.IncrementalAnalyzer;
import com.github.shikejing.codecompletionservicejava.parse.Lexer;
import com.github.shikejing.codecompletionservicejava.parse.ModelsEquivalent;
import com.github.shikejing.codecompletionservicejava.parse.Parser;
import com.github.shikejing.codecompletionservicejava.parse.SyntaxAnalyzer;
import com.github.shikejing.codecompletionservicejava.parse.SyntaxModel;

class IncrementalAnalysisTest {

    private final SyntaxAnalyzer full = new SyntaxAnalyzer(new Lexer(), new Parser());
    private final IncrementalAnalyzer inc = new IncrementalAnalyzer(full);

    /** Apply an edit at the given absolute range and compare inc vs full. */
    private void assertEquivalent(String oldText, int start, int oldEnd, String insert) {
        SyntaxModel before = full.analyze("u", 1, oldText);
        String newText = oldText.substring(0, start) + insert
                + oldText.substring(oldEnd);
        int newEnd = start + insert.length();
        SyntaxModel incremental = inc.reanalyze(before, 2, newText,
                start, oldEnd, newEnd);
        SyntaxModel rebuilt = full.analyze("u", 2, newText);
        assertTrue(ModelsEquivalent.equivalent(rebuilt, incremental),
                "incremental model must equal full rebuild");
    }

    @Test
    void editInsideMethodBodyIsEquivalent() {
        String text = """
                class Sample {
                  int fieldA;
                  int work(int arg) {
                    int local = arg + fieldA;
                    return local;
                  }
                }
                """;
        // replace "arg + fieldA" with a new local declaration
        int s = text.indexOf("arg + fieldA");
        assertEquivalent(text, s, s + "arg + fieldA".length(),
                "int extra = 7");
    }

    @Test
    void insertionInsideBlockIsEquivalent() {
        String text = "class C {\n  void m() {\n    int a = 1;\n  }\n}\n";
        int pos = text.indexOf("int a = 1;");
        assertEquivalent(text, pos, pos, "int inserted = 2;\n    ");
    }

    @Test
    void deletionIsEquivalent() {
        String text = "class C {\n  void m() {\n    int a = 1;\n    int b = 2;\n  }\n}\n";
        int s = text.indexOf("    int b = 2;\n");
        assertEquivalent(text, s, s + "    int b = 2;\n".length(), "");
    }

    @Test
    void braceChangeFallsBackToFullButStaysEquivalent() {
        String text = "class C {\n  void m() {\n    int a = 1;\n  }\n}\n";
        // introduce a nested block (brace balance changes)
        int s = text.indexOf("int a = 1;");
        assertEquivalent(text, s, s, "{ int nested = 0; ");
    }

    @Test
    void editAcrossManyRandomPoints() {
        String text = """
                class A {
                  int x;
                  int foo(int p) {
                    int y = p;
                    if (y > 0) {
                      x = y;
                    }
                    return x;
                  }
                  class Inner {}
                }
                """;
        int[] points = {text.indexOf("int y"), text.indexOf("if"),
                text.indexOf("return"), text.indexOf("class Inner"),
                text.indexOf("x = y"), text.indexOf("class A")};
        for (int p : points) {
            assertEquivalent(text, p, p, "zz");
            assertEquivalent(text, p, Math.min(text.length(), p + 1), "");
        }
    }

    @Test
    void tokensAreReusedOutsideWindow() {
        // The incremental path must splice tokens: a marker token before the
        // edit window keeps identical offsets after the edit.
        String text = "class C {\n  int keepMe = 0;\n  void m() {\n    int a = 1;\n  }\n}\n";
        SyntaxModel before = full.analyze("u", 1, text);
        int s = text.indexOf("int a = 1;");
        String newText = text.substring(0, s) + "int a = 2;"
                + text.substring(s + "int a = 1;".length());
        SyntaxModel after = inc.reanalyze(before, 2, newText, s,
                s + "int a = 1;".length(), s + "int a = 2;".length());
        int markerOffset = text.indexOf("keepMe");
        boolean sameOffset = after.tokens().stream().anyMatch(t ->
                t.text().equals("keepMe") && t.startOffset() == markerOffset);
        assertTrue(sameOffset);
    }
}
