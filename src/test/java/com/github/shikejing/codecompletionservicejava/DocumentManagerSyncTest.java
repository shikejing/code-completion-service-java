package com.github.shikejing.codecompletionservicejava;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.shikejing.codecompletionservicejava.config.ServiceProperties;
import com.github.shikejing.codecompletionservicejava.document.DocumentManager;
import com.github.shikejing.codecompletionservicejava.model.DocumentSnapshot;
import com.github.shikejing.codecompletionservicejava.model.Edit;
import com.github.shikejing.codecompletionservicejava.model.EditOutcome;
import com.github.shikejing.codecompletionservicejava.model.Position;
import com.github.shikejing.codecompletionservicejava.model.TextRange;
import com.github.shikejing.codecompletionservicejava.model.VersionPolicy;

class DocumentManagerSyncTest {

    private DocumentManager manager;
    private ServiceProperties properties;

    @BeforeEach
    void setUp() {
        properties = new ServiceProperties();
        manager = new DocumentManager(properties);
    }

    @Test
    void openStoresFullText() {
        EditOutcome outcome = manager.applyEdit("u", Edit.open(0, "hello world"));
        assertThat(outcome.status()).isEqualTo(EditOutcome.Status.APPLIED);
        DocumentSnapshot snapshot = manager.getSnapshot("u");
        assertThat(snapshot.text()).isEqualTo("hello world");
        assertThat(snapshot.version()).isZero();
    }

    @Test
    void rangeReplacementAppliesIncrementally() {
        manager.applyEdit("u", Edit.open(0, "abcdef"));
        EditOutcome outcome = manager.applyEdit("u", Edit.change(1, "e1",
                new TextRange(new Position(0, 1), new Position(0, 3)), "XY"));
        assertThat(outcome.status()).isEqualTo(EditOutcome.Status.APPLIED);
        assertThat(manager.getSnapshot("u").text()).isEqualTo("aXYdef");
        assertThat(manager.getSnapshot("u").version()).isEqualTo(1);
    }

    @Test
    void duplicateEditIsIdempotent() {
        manager.applyEdit("u", Edit.open(0, "abcdef"));
        Edit edit = Edit.change(1, "same-id",
                new TextRange(new Position(0, 0), new Position(0, 1)), "Z");
        EditOutcome first = manager.applyEdit("u", edit);
        EditOutcome second = manager.applyEdit("u", edit);
        assertThat(first.status()).isEqualTo(EditOutcome.Status.APPLIED);
        assertThat(second.status()).isEqualTo(EditOutcome.Status.DUPLICATE);
        assertThat(manager.getSnapshot("u").text()).isEqualTo("Zbcdef");
    }

    @Test
    void staleVersionIsRejected() {
        manager.applyEdit("u", Edit.open(5, "abc"));
        EditOutcome stale = manager.applyEdit("u", Edit.change(3, "old",
                new TextRange(new Position(0, 0), new Position(0, 1)), "X"));
        assertThat(stale.status()).isEqualTo(EditOutcome.Status.REJECTED_STALE);
        assertThat(manager.getSnapshot("u").text()).isEqualTo("abc");
    }

    @Test
    void sameVersionDifferentEditIsRejectedNotSilentlyAccepted() {
        manager.applyEdit("u", Edit.open(0, "abc"));
        manager.applyEdit("u", Edit.change(1, "a",
                new TextRange(new Position(0, 0), new Position(0, 1)), "X"));
        EditOutcome collision = manager.applyEdit("u", Edit.change(1, "b",
                new TextRange(new Position(0, 0), new Position(0, 1)), "Y"));
        assertThat(collision.status()).isEqualTo(EditOutcome.Status.REJECTED_STALE);
        assertThat(manager.getSnapshot("u").text()).isEqualTo("Xbc");
    }

    @Test
    void versionGapIsRejectedInStrictMode() {
        manager.applyEdit("u", Edit.open(0, "abc"));
        EditOutcome gap = manager.applyEdit("u", Edit.change(5, "jump",
                new TextRange(new Position(0, 0), new Position(0, 1)), "X"));
        assertThat(gap.status()).isEqualTo(EditOutcome.Status.REJECTED_VERSION_GAP);
    }

    @Test
    void versionGapIsAcceptedInLenientGapMode() {
        properties.setVersionPolicy(VersionPolicy.LENIENT_GAP);
        manager.applyEdit("u", Edit.open(0, "abc"));
        EditOutcome gap = manager.applyEdit("u", Edit.change(5, "jump",
                new TextRange(new Position(0, 0), new Position(0, 1)), "X"));
        assertThat(gap.status()).isEqualTo(EditOutcome.Status.APPLIED);
        assertThat(manager.getSnapshot("u").version()).isEqualTo(5);
    }

    @Test
    void overlappingEditsAtDistinctVersionsComposeOnLatestText() {
        manager.applyEdit("u", Edit.open(0, "0123456789"));
        manager.applyEdit("u", Edit.change(1, "a",
                new TextRange(new Position(0, 0), new Position(0, 4)), "AAAA"));
        // second overlap is relative to the *current* committed text
        EditOutcome second = manager.applyEdit("u", Edit.change(2, "b",
                new TextRange(new Position(0, 2), new Position(0, 6)), "BB"));
        assertThat(second.status()).isEqualTo(EditOutcome.Status.APPLIED);
        assertThat(manager.getSnapshot("u").text()).isEqualTo("AABB6789");
    }

    @Test
    void outOfOrderDeliveryDoesNotMoveStateBackwards() {
        manager.applyEdit("u", Edit.open(0, "abc"));
        manager.applyEdit("u", Edit.change(1, "first",
                new TextRange(new Position(0, 0), new Position(0, 1)), "X"));
        manager.applyEdit("u", Edit.change(2, "second",
                new TextRange(new Position(0, 0), new Position(0, 1)), "Y"));
        // an older edit (version 1) is re-delivered late and must be ignored
        EditOutcome late = manager.applyEdit("u", Edit.change(1, "first",
                new TextRange(new Position(0, 0), new Position(0, 1)), "Z"));
        assertThat(late.status()).isEqualTo(EditOutcome.Status.DUPLICATE);
        // a genuinely different stale edit is rejected
        EditOutcome stale = manager.applyEdit("u", Edit.change(1, "other",
                new TextRange(new Position(0, 0), new Position(0, 1)), "Z"));
        assertThat(stale.status()).isEqualTo(EditOutcome.Status.REJECTED_STALE);
        assertThat(manager.getSnapshot("u").text()).isEqualTo("Ybc");
    }

    @Test
    void invalidRangeIsRejectedAndStateStaysUntouched() {
        manager.applyEdit("u", Edit.open(0, "abc"));
        EditOutcome bad = manager.applyEdit("u", Edit.change(1, "bad",
                new TextRange(new Position(5, 0), new Position(5, 3)), "X"));
        assertThat(bad.status()).isEqualTo(EditOutcome.Status.REJECTED_INVALID_RANGE);
        assertThat(manager.getSnapshot("u").version()).isZero();
    }

    @Test
    void closeMarksDocumentClosedAndRejectsFurtherChanges() {
        manager.applyEdit("u", Edit.open(0, "abc"));
        assertThat(manager.applyEdit("u", Edit.close(1)).status())
                .isEqualTo(EditOutcome.Status.APPLIED);
        assertThat(manager.getSnapshot("u")).isNull();
        EditOutcome change = manager.applyEdit("u", Edit.change(2, "x",
                new TextRange(new Position(0, 0), new Position(0, 1)), "X"));
        assertThat(change.status()).isEqualTo(EditOutcome.Status.REJECTED_CLOSED);
    }

    @Test
    void multilineEditsUseLineAndCharacterOffsets() {
        manager.applyEdit("u", Edit.open(0, "line0\nline1\nline2"));
        EditOutcome outcome = manager.applyEdit("u", Edit.change(1, "m",
                new TextRange(new Position(1, 0), new Position(1, 5)), "LINE1"));
        assertThat(outcome.status()).isEqualTo(EditOutcome.Status.APPLIED);
        assertThat(manager.getSnapshot("u").text()).isEqualTo("line0\nLINE1\nline2");
    }
}
