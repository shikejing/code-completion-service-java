package com.github.shikejing.codecompletionservicejava;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.github.shikejing.codecompletionservicejava.config.ServiceProperties;
import com.github.shikejing.codecompletionservicejava.document.DocumentManager;
import com.github.shikejing.codecompletionservicejava.model.DocumentSnapshot;
import com.github.shikejing.codecompletionservicejava.model.Edit;
import com.github.shikejing.codecompletionservicejava.model.EditOutcome;
import com.github.shikejing.codecompletionservicejava.model.Position;
import com.github.shikejing.codecompletionservicejava.model.TextRange;

class ConcurrencyTest {

    private static String versionText(int version, int marker) {
        // text length grows deterministically; every version has a unique token
        return "class V" + version + " { int x" + marker + "; }\n";
    }

    @Test
    void queriesAlwaysObserveACompleteCommittedVersion() throws Exception {
        ServiceProperties props = new ServiceProperties();
        props.setVersionPolicy(com.github.shikejing.codecompletionservicejava.model.VersionPolicy.LENIENT_GAP);
        DocumentManager manager = new DocumentManager(props);
        manager.applyEdit("u", Edit.open(0, versionText(0, 0)));

        int writers = 4;
        int editsPerWriter = 50;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        Set<String> observedTexts = ConcurrentHashMap.newKeySet();
        AtomicInteger applied = new AtomicInteger();

        for (int w = 0; w < writers; w++) {
            int writerId = w;
            pool.submit(() -> {
                try { start.await(); } catch (InterruptedException ignored) {}
                for (int i = 0; i < editsPerWriter; i++) {
                    int seq = applied.incrementAndGet();
                    String full = versionText(seq, seq);
                    // full replacement relative to whatever the current version is:
                    // use fullText range-less change keyed by a strictly increasing
                    // version; only one edit per version can win, which is the
                    // linearizability guarantee under test.
                    EditOutcome outcome = manager.applyEdit("u",
                            new Edit(seq, "edit-" + seq, Edit.Kind.CHANGE, null, null, full));
                    if (outcome.status() == EditOutcome.Status.APPLIED) {
                        observedTexts.add(full);
                    }
                }
            });
        }
        // readers: every snapshot text must be exactly one well-formed version
        List<Throwable> readerErrors = new ArrayList<>();
        for (int r = 0; r < 4; r++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 400; i++) {
                        DocumentSnapshot snapshot = manager.getSnapshot("u");
                        String text = snapshot.text();
                        if (!text.matches("class V\\d+ \\{ int x\\d+; }\\n")) {
                            throw new AssertionError("mixed/partial state: " + text);
                        }
                        // version number must agree with the text token
                        int vStart = text.indexOf('V') + 1;
                        int vEnd = vStart;
                        while (vEnd < text.length() && Character.isDigit(text.charAt(vEnd))) {
                            vEnd++;
                        }
                        int textVersion = Integer.parseInt(text.substring(vStart, vEnd));
                        long declared = snapshot.version();
                        if (declared != 0 && textVersion != declared) {
                            throw new AssertionError(
                                    "version " + declared + " mismatches text " + textVersion);
                        }
                    }
                } catch (Throwable t) {
                    synchronized (readerErrors) { readerErrors.add(t); }
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        assertThat(readerErrors).isEmpty();

        DocumentSnapshot finalSnapshot = manager.getSnapshot("u");
        // the final version must be the highest applied sequence
        assertThat(finalSnapshot.text()).startsWith("class V" + finalSnapshot.version() + " ");
    }

    @Test
    void noInFlightReadsCanObserveFieldLevelMixUnderRangeEdits() throws Exception {
        ServiceProperties props = new ServiceProperties();
        DocumentManager manager = new DocumentManager(props);
        String base = "0123456789";
        manager.applyEdit("u", Edit.open(0, base));

        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch start = new CountDownLatch(1);
        List<Throwable> errors = new ArrayList<>();
        AtomicInteger nextVersion = new AtomicInteger(1);

        for (int w = 0; w < 3; w++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 100; i++) {
                        int v = nextVersion.getAndIncrement();
                        EditOutcome outcome = manager.applyEdit("u",
                                Edit.change(v, "id-" + v,
                                        new TextRange(new Position(0, 0), new Position(0, 1)),
                                        String.valueOf(v % 10)));
                        // STRICT requires consecutive versions; contention makes
                        // only the in-order winner apply, that is acceptable
                        assertThat(outcome.status()).isIn(
                                EditOutcome.Status.APPLIED,
                                EditOutcome.Status.REJECTED_STALE,
                                EditOutcome.Status.REJECTED_VERSION_GAP);
                    }
                } catch (Throwable t) {
                    synchronized (errors) { errors.add(t); }
                }
            });
        }
        for (int r = 0; r < 3; r++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 500; i++) {
                        DocumentSnapshot snapshot = manager.getSnapshot("u");
                        String text = snapshot.text();
                        if (text.length() != 10 || !text.matches("\\d{10}")) {
                            throw new AssertionError("partial state: '" + text + "'");
                        }
                    }
                } catch (Throwable t) {
                    synchronized (errors) { errors.add(t); }
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        assertThat(errors).isEmpty();
    }
}
