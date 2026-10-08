package com.github.shikejing.codecompletionservicejava;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import com.github.shikejing.codecompletionservicejava.client.LocalEditorClient;
import com.github.shikejing.codecompletionservicejava.document.DocumentManager;

/**
 * Commits edits against one process, shuts the Spring context down, boots a
 * second one against the same persistence directory and verifies the
 * recovered text/version equal the last committed state.
 */
class RestartRecoveryIntegrationTest {

    @TempDir
    Path dataDir;

    private ConfigurableApplicationContext start(int[] portHolder) {
        ConfigurableApplicationContext context = new SpringApplicationBuilder(Application.class)
                .run("--server.port=0",
                        "--server.address=127.0.0.1",
                        "--completion.persistence.dir=" + dataDir.toString());
        portHolder[0] = context.getEnvironment().getProperty("local.server.port", Integer.class);
        return context;
    }

    @Test
    void recoveredStateEqualsLastCommittedVersion() {
        int[] port = new int[1];
        ConfigurableApplicationContext first = start(port);
        try {
            LocalEditorClient client = new LocalEditorClient("http://127.0.0.1:" + port[0]);
            String uri = "file:///Restart.java";
            client.openDocument(uri, 0, "class R { int a; }");
            client.change(uri, 1, "e1", 0, 14, 0, 15, "b");
            client.change(uri, 2, "e2", 0, 14, 0, 15, "c");
            DocumentManager manager = first.getBean(DocumentManager.class);
            assertThat(manager.getSnapshot(uri).text()).isEqualTo("class R { int c; }");
            assertThat(manager.getSnapshot(uri).version()).isEqualTo(2);
        } finally {
            first.close();
        }

        int[] port2 = new int[1];
        ConfigurableApplicationContext second = start(port2);
        try {
            DocumentManager manager = second.getBean(DocumentManager.class);
            var snapshot = manager.getSnapshot("file:///Restart.java");
            assertThat(snapshot).isNotNull();
            assertThat(snapshot.text()).isEqualTo("class R { int c; }");
            assertThat(snapshot.version()).isEqualTo(2);

            // replay same batch after restart must stay identical
            LocalEditorClient client = new LocalEditorClient("http://127.0.0.1:" + port2[0]);
            var response = client.complete("file:///Restart.java", 0, 15, null);
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("\"label\":\"c\"");
        } finally {
            second.close();
        }
    }
}
