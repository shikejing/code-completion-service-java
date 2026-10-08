package com.github.shikejing.codecompletionservicejava;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.github.shikejing.codecompletionservicejava.config.ServiceProperties;
import com.github.shikejing.codecompletionservicejava.model.DocumentSnapshot;
import com.github.shikejing.codecompletionservicejava.model.Edit;
import com.github.shikejing.codecompletionservicejava.model.Position;
import com.github.shikejing.codecompletionservicejava.model.TextRange;
import com.github.shikejing.codecompletionservicejava.persistence.DocumentStore;
import com.github.shikejing.codecompletionservicejava.persistence.PersistedRecord;
import com.github.shikejing.codecompletionservicejava.persistence.RecoveryService;

class PersistenceRecoveryTest {

    private ServiceProperties propsWith(Path dir) {
        ServiceProperties props = new ServiceProperties();
        props.getPersistence().setDir(dir.toString());
        return props;
    }

    @Test
    void snapshotThenDeltaReplayRestoresLatestText(@TempDir Path dir) {
        ServiceProperties props = propsWith(dir);
        DocumentStore store = new DocumentStore(props);

        String text0 = "abcdef";
        store.writeSnapshot("u", 0, text0);
        Edit e1 = Edit.change(1, "e1",
                new TextRange(new Position(0, 1), new Position(0, 3)), "XY");
        store.appendRecord(PersistedRecord.delta("u", 1, List.of(e1)));

        RecoveryService recovery = new RecoveryService(new DocumentStore(props));
        Map<String, DocumentSnapshot> all = recovery.recoverAll();
        assertThat(all).containsKey("u");
        assertThat(all.get("u").text()).isEqualTo("aXYdef");
        assertThat(all.get("u").version()).isEqualTo(1);
    }

    @Test
    void tornTrailingRecordIsDiscardedNotPartiallyApplied(@TempDir Path dir) throws Exception {
        ServiceProperties props = propsWith(dir);
        DocumentStore store = new DocumentStore(props);
        store.writeSnapshot("u", 0, "abc");
        Edit e1 = Edit.change(1, "e1",
                new TextRange(new Position(0, 0), new Position(0, 1)), "X");
        store.appendRecord(PersistedRecord.delta("u", 1, List.of(e1)));

        // corrupt the journal by truncating the last line (simulated torn write)
        Path journal = store.dirFor("u").resolve("journal.log");
        String content = Files.readString(journal, StandardCharsets.UTF_8);
        String torn = content.substring(0, content.length() - 4);
        Files.writeString(journal, torn, StandardCharsets.UTF_8);

        RecoveryService recovery = new RecoveryService(new DocumentStore(props));
        DocumentSnapshot snapshot = recovery.recoverOne("u");
        // torn delta ignored, snapshot state intact
        assertThat(snapshot.text()).isEqualTo("abc");
        assertThat(snapshot.version()).isZero();
    }

    @Test
    void closeRemovesDurableState(@TempDir Path dir) {
        ServiceProperties props = propsWith(dir);
        DocumentStore store = new DocumentStore(props);
        store.writeSnapshot("u", 0, "abc");
        assertThat(store.recover("u")).isNotEmpty();
        store.remove("u");
        assertThat(store.recover("u")).isEmpty();
        assertThat(store.recoverUris()).doesNotContain("u");
    }

    @Test
    void multipleDocumentsRecoverIndependently(@TempDir Path dir) {
        ServiceProperties props = propsWith(dir);
        DocumentStore store = new DocumentStore(props);
        store.writeSnapshot("a", 0, "AAA");
        store.writeSnapshot("b", 0, "BBB");
        RecoveryService recovery = new RecoveryService(new DocumentStore(props));
        Map<String, DocumentSnapshot> all = recovery.recoverAll();
        assertThat(all.get("a").text()).isEqualTo("AAA");
        assertThat(all.get("b").text()).isEqualTo("BBB");
    }

    @Test
    void snapshotCompactionWinsOverOlderJournal(@TempDir Path dir) {
        ServiceProperties props = propsWith(dir);
        DocumentStore store = new DocumentStore(props);
        store.writeSnapshot("u", 0, "abc");
        Edit e1 = Edit.change(1, "e1",
                new TextRange(new Position(0, 0), new Position(0, 1)), "X");
        store.appendRecord(PersistedRecord.delta("u", 1, List.of(e1)));
        // a later snapshot supersedes journal content
        store.writeSnapshot("u", 2, "final");
        DocumentSnapshot snapshot = new RecoveryService(new DocumentStore(props)).recoverOne("u");
        assertThat(snapshot.text()).isEqualTo("final");
        assertThat(snapshot.version()).isEqualTo(2);
    }
}
