package com.github.shikejing.codecompletionservicejava.tests;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import com.github.shikejing.codecompletionservicejava.cache.CompletionCache;
import com.github.shikejing.codecompletionservicejava.model.CompletionItem;
import com.github.shikejing.codecompletionservicejava.model.Position;
import com.github.shikejing.codecompletionservicejava.model.SymbolKind;

class CacheInvalidationTest {

    private CompletionItem item(String name) {
        return new CompletionItem(name, "", SymbolKind.LOCAL_VARIABLE, "test",
                1.0, "0|" + name, "test");
    }

    @Test
    void hitReturnsStoredItems() {
        CompletionCache cache = new CompletionCache(4);
        Position pos = Position.of(1, 2);
        cache.putIfAbsent("u", 1, pos, List.of(item("a")), 1);
        assertTrue(cache.get("u", 1, pos).isPresent());
        assertFalse(cache.get("u", 2, pos).isPresent());
    }

    @Test
    void editInvalidatesOldVersions() {
        CompletionCache cache = new CompletionCache(4);
        Position pos = Position.of(1, 2);
        cache.putIfAbsent("u", 1, pos, List.of(item("a")), 1);
        cache.invalidateOlderThan("u", 2);
        assertFalse(cache.get("u", 1, pos).isPresent());
    }

    @Test
    void staleResultCannotOverwriteNewer() {
        CompletionCache cache = new CompletionCache(4);
        Position pos = Position.of(0, 0);
        // document is now at v5; a slow query computed for v1 tries to store
        assertFalse(cache.putIfAbsent("u", 1, pos, List.of(item("stale")), 5));
        assertFalse(cache.get("u", 1, pos).isPresent());
    }

    @Test
    void putIsIdempotentAndBounded() {
        CompletionCache cache = new CompletionCache(2);
        cache.putIfAbsent("u", 1, Position.of(0, 1), List.of(item("a")), 1);
        cache.putIfAbsent("u", 1, Position.of(0, 2), List.of(item("b")), 1);
        cache.putIfAbsent("u", 1, Position.of(0, 3), List.of(item("c")), 1);
        assertTrue(cache.size("u") <= 2);
        // first writer wins, identical items
        var first = List.of(item("a"));
        cache.putIfAbsent("u", 1, Position.of(0, 9), first, 1);
        assertFalse(cache.putIfAbsent("u", 1, Position.of(0, 9),
                List.of(item("z")), 1));
    }

    @Test
    void closeClearsDocumentCache() {
        CompletionCache cache = new CompletionCache(4);
        cache.putIfAbsent("u", 1, Position.of(0, 0), List.of(item("a")), 1);
        cache.invalidate("u");
        assertEquals(0, cache.size("u"));
    }
}
