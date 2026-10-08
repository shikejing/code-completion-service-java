package com.github.shikejing.codecompletionservicejava;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.github.shikejing.codecompletionservicejava.config.ServiceProperties;
import com.github.shikejing.codecompletionservicejava.document.DocumentManager;
import com.github.shikejing.codecompletionservicejava.model.Edit;
import com.github.shikejing.codecompletionservicejava.model.EditOutcome;

class ResourceBoundTest {

    @Test
    void openDocumentsAreBoundedByMaxDocuments() {
        ServiceProperties props = new ServiceProperties();
        props.getLimits().setMaxDocuments(3);
        DocumentManager manager = new DocumentManager(props);

        assertThat(manager.applyEdit("a", Edit.open(0, "x")).status())
                .isEqualTo(EditOutcome.Status.APPLIED);
        assertThat(manager.applyEdit("b", Edit.open(0, "x")).status())
                .isEqualTo(EditOutcome.Status.APPLIED);
        assertThat(manager.applyEdit("c", Edit.open(0, "x")).status())
                .isEqualTo(EditOutcome.Status.APPLIED);
        EditOutcome rejected = manager.applyEdit("d", Edit.open(0, "x"));
        assertThat(rejected.status()).isEqualTo(EditOutcome.Status.REJECTED_LIMIT);

        // closing one frees a slot
        manager.applyEdit("c", Edit.close(1));
        manager.evictClosedIfNeeded();
        assertThat(manager.applyEdit("d", Edit.open(0, "x")).status())
                .isEqualTo(EditOutcome.Status.APPLIED);
    }

    @Test
    void oversizedDocumentIsRejected() {
        ServiceProperties props = new ServiceProperties();
        props.getLimits().setMaxDocumentChars(4);
        DocumentManager manager = new DocumentManager(props);
        EditOutcome outcome = manager.applyEdit("u", Edit.open(0, "too long"));
        assertThat(outcome.status()).isEqualTo(EditOutcome.Status.REJECTED_INVALID_RANGE);
    }
}
