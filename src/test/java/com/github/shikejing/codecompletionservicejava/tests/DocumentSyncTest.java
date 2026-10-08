package com.github.shikejing.codecompletionservicejava.tests;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import com.github.shikejing.codecompletionservicejava.config.CompletionProperties;
import com.github.shikejing.codecompletionservicejava.document.DocumentManager;
import com.github.shikejing.codecompletionservicejava.model.*;

class DocumentSyncTest {

    private DocumentManager manager() {
        return new DocumentManager(new CompletionProperties());
    }

    private DocumentChangeRequest edit(String uri, int version, int base,
                                       Position s, Position e, String text) {
        return DocumentChangeRequest.incremental(uri, version, base,
                List.of(TextEdit.of(s, e, text)));
    }

    @Test
    void sequentialEditsApply() {
        DocumentManager dm = manager();
        dm.open(new DocumentOpenRequest("u", "abc", 0));
        ChangeOutcome o1 = dm.change(edit("u", 1, 0,
                Position.of(0, 1), Position.of(0, 2), "X"));
        assertEquals(ChangeStatus.APPLIED, o1.status());
        assertEquals("aXc", dm.snapshot("u").orElseThrow().text());
    }

    @Test
    void duplicateIsIdempotent() {
        DocumentManager dm = manager();
        dm.open(new DocumentOpenRequest("u", "abc", 0));
        var req = edit("u", 1, 0, Position.of(0, 0), Position.of(0, 1), "ZZ");
        var first = dm.change(req);
        var second = dm.change(req);
        assertEquals(ChangeStatus.APPLIED, first.status());
        assertEquals(ChangeStatus.DUPLICATE, second.status());
        assertEquals("ZZbc", dm.snapshot("u").orElseThrow().text());
        assertEquals(1, dm.snapshot("u").orElseThrow().version());
    }

    @Test
    void staleVersionRejectedByDefault() {
        DocumentManager dm = manager();
        dm.open(new DocumentOpenRequest("u", "abc", 0));
        dm.change(edit("u", 1, 0, Position.of(0, 0), Position.of(0, 1), "X"));
        // another version 1 with different content -> regression rejected
        var stale = edit("u", 1, 0, Position.of(0, 0), Position.of(0, 1), "Y");
        var o = dm.change(stale);
        assertEquals(ChangeStatus.REJECTED_STALE, o.status());
        assertEquals("Xbc", dm.snapshot("u").orElseThrow().text());
        // base older than current, version jumps ahead
        var oldBase = edit("u", 5, 0, Position.of(0, 1), Position.of(0, 2), "Q");
        assertEquals(ChangeStatus.REJECTED_STALE, dm.change(oldBase).status());
        assertEquals("Xbc", dm.snapshot("u").orElseThrow().text());
    }

    @Test
    void futureEditsBufferedThenCommittedInOrder() {
        DocumentManager dm = manager();
        dm.open(new DocumentOpenRequest("u", "abcdef", 0));
        // v2 arrives before v1
        var buffered = dm.change(edit("u", 2, 1,
                Position.of(0, 2), Position.of(0, 3), "Y"));
        assertEquals(ChangeStatus.BUFFERED, buffered.status());
        assertEquals("abcdef", dm.snapshot("u").orElseThrow().text());
        // gap v1 fills -> v1 then v2 commit
        var outcome = dm.change(edit("u", 1, 0,
                Position.of(0, 1), Position.of(0, 2), "X"));
        assertEquals(ChangeStatus.APPLIED, outcome.status());
        assertEquals(List.of(1, 2), outcome.appliedVersions());
        assertEquals("aXYdef", dm.snapshot("u").orElseThrow().text());
    }

    @Test
    void versionSkipBeyondBufferPolicyReject() {
        CompletionProperties props = new CompletionProperties();
        props.setOutOfOrderPolicy(OutOfOrderPolicy.REJECT);
        DocumentManager dm = new DocumentManager(props);
        dm.open(new DocumentOpenRequest("u", "abc", 0));
        var o = dm.change(edit("u", 3, 2, Position.of(0, 0), Position.of(0, 1), "z"));
        assertEquals(ChangeStatus.REJECTED_STALE, o.status());
    }

    @Test
    void overlappingEditsInOneNotificationRejectedAtomically() {
        DocumentManager dm = manager();
        dm.open(new DocumentOpenRequest("u", "abcdef", 0));
        var req = DocumentChangeRequest.incremental("u", 1, 0, List.of(
                TextEdit.of(Position.of(0, 0), Position.of(0, 3), "XYZ"),
                TextEdit.of(Position.of(0, 2), Position.of(0, 4), "QQ")));
        var o = dm.change(req);
        assertEquals(ChangeStatus.REJECTED_OVERLAP, o.status());
        assertEquals("abcdef", dm.snapshot("u").orElseThrow().text());
    }

    @Test
    void touchingEditsAreNotOverlapping() {
        DocumentManager dm = manager();
        dm.open(new DocumentOpenRequest("u", "abc", 0));
        var req = DocumentChangeRequest.incremental("u", 1, 0, List.of(
                TextEdit.of(Position.of(0, 0), Position.of(0, 1), "X"),
                TextEdit.of(Position.of(0, 1), Position.of(0, 2), "Y")));
        assertEquals(ChangeStatus.APPLIED, dm.change(req).status());
        assertEquals("XYc", dm.snapshot("u").orElseThrow().text());
    }

    @Test
    void rebasePolicyTranslatesStaleEdit() {
        CompletionProperties props = new CompletionProperties();
        props.setStalePolicy(StalePolicy.REBASE);
        DocumentManager dm = new DocumentManager(props);
        dm.open(new DocumentOpenRequest("u", "abcdef", 0));
        // v1 inserts "XX" at the beginning
        dm.change(edit("u", 1, 0, Position.of(0, 0), Position.of(0, 0), "XX"));
        // stale edit based on v0 intended to replace 'c' (offset 2) with 'C';
        // after insertion 'c' moved to offset 4, rebase translates it
        var stale = edit("u", 2, 0, Position.of(0, 2), Position.of(0, 3), "C");
        var o = dm.change(stale);
        assertEquals(ChangeStatus.REBASED, o.status());
        assertEquals("XXabCdef", dm.snapshot("u").orElseThrow().text());
    }

    @Test
    void fullSyncReplacesWholeText() {
        DocumentManager dm = manager();
        dm.open(new DocumentOpenRequest("u", "abc", 0));
        var o = dm.change(DocumentChangeRequest.full("u", 1, "brand new"));
        assertEquals(ChangeStatus.APPLIED, o.status());
        assertEquals("brand new", dm.snapshot("u").orElseThrow().text());
    }

    @Test
    void closeRemovesDocument() {
        DocumentManager dm = manager();
        dm.open(new DocumentOpenRequest("u", "abc", 0));
        assertTrue(dm.close(new DocumentCloseRequest("u")));
        assertTrue(dm.snapshot("u").isEmpty());
        assertEquals(ChangeStatus.CLOSED,
                dm.change(edit("u", 1, 0, Position.of(0, 0), Position.of(0, 0), "x"))
                        .status());
    }
}
