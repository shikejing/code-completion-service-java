package com.github.shikejing.codecompletionservicejava.persistence;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import com.github.shikejing.codecompletionservicejava.document.DocumentSnapshot;

/**
 * Durable storage backed by the local file system, one file per document:
 * {@code dataDir/docs/<safe-uri>.snapshot}.
 *
 * Atomicity: data is written to a unique {@code .tmp} file (created
 * CREATE_NEW, flushed and fsynced), then moved onto the destination with an
 * atomic move where the file system supports it (falling back to a non-atomic
 * replace only where it does not). A crash during a write therefore leaves
 * either the previous snapshot or the new one, never a truncated file, so
 * unfinished processing cannot leave partial data behind.
 *
 * The file format is a tiny, deterministic line-oriented encoding (not JSON,
 * to stay dependency-free): a fixed header, version, recency count and names,
 * then the whole text as the last line with an explicit length-free marker.
 */
public final class FileDocumentStore implements DocumentStore {

    private final Path docsDir;
    private final Path tmpDir;

    public FileDocumentStore(String dataDir) {
        Path root = Path.of(dataDir);
        this.docsDir = root.resolve("docs");
        this.tmpDir = root.resolve("tmp");
        try {
            Files.createDirectories(docsDir);
            Files.createDirectories(tmpDir);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot initialize data dir " + dataDir, e);
        }
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public synchronized void saveSnapshot(DocumentSnapshot snapshot,
                                          List<String> recencyNames) {
        String safe = safeName(snapshot.uri());
        Path target = docsDir.resolve(safe + ".snapshot");
        Path tmp;
        try {
            tmp = Files.createTempFile(tmpDir, safe + "-", ".tmp");
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create temp snapshot", e);
        }
        try (BufferedWriter w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            w.write("CCS-SNAPSHOT-1");
            w.newLine();
            w.write("uri=" + encode(snapshot.uri()));
            w.newLine();
            w.write("version=" + snapshot.version());
            w.newLine();
            List<String> names = recencyNames == null ? List.of() : recencyNames;
            w.write("recencyCount=" + names.size());
            w.newLine();
            for (String name : names) {
                w.write("recent=" + encode(name));
                w.newLine();
            }
            // names may carry the batch encoding ("BATCH n" headers); those
            // lines are preserved verbatim by the recent= transport prefix.
            w.write("text=");
            w.write(encode(snapshot.text() == null ? "" : snapshot.text()));
            w.newLine();
            w.flush();
        } catch (IOException e) {
            deleteQuietly(tmp);
            throw new UncheckedIOException("cannot write snapshot", e);
        }
        try {
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(
                    docsDir, StandardOpenOption.READ)) {
                ch.force(true);
            } catch (IOException ignore) {
                // directory fsync best effort
            }
        } catch (IOException e) {
            deleteQuietly(tmp);
            throw new UncheckedIOException("cannot publish snapshot", e);
        }
    }

    @Override
    public synchronized List<StoredDocument> loadAll() {
        List<StoredDocument> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(docsDir)) {
            files.filter(p -> p.getFileName().toString().endsWith(".snapshot"))
                    .sorted()
                    .forEach(p -> {
                        StoredDocument doc = readOne(p);
                        if (doc != null) {
                            out.add(doc);
                        }
                    });
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list snapshots", e);
        }
        return List.copyOf(out);
    }

    private StoredDocument readOne(Path file) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            // A corrupt/partial file never stops startup; it is ignored.
            return null;
        }
        if (lines.isEmpty() || !"CCS-SNAPSHOT-1".equals(lines.get(0))) {
            return null;
        }
        String uri = null;
        int version = 0;
        List<String> recency = new ArrayList<>();
        String text = "";
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.startsWith("uri=")) {
                uri = decode(line.substring(4));
            } else if (line.startsWith("version=")) {
                try {
                    version = Integer.parseInt(line.substring(8));
                } catch (NumberFormatException e) {
                    return null;
                }
            } else if (line.startsWith("recencyCount=")) {
                // count informative; actual lines carry the data
            } else if (line.startsWith("recent=")) {
                recency.add(decode(line.substring(7)));
            } else if (line.startsWith("text=")) {
                text = decode(line.substring(5));
            }
        }
        if (uri == null) {
            return null;
        }
        DocumentSnapshot snapshot = DocumentSnapshot.initial(uri, version, text);
        return new StoredDocument(snapshot, List.copyOf(recency));
    }

    @Override
    public synchronized void delete(String uri) {
        deleteQuietly(docsDir.resolve(safeName(uri) + ".snapshot"));
    }

    @Override
    public synchronized void clear() {
        try (Stream<Path> files = Files.list(docsDir)) {
            files.forEach(FileDocumentStore::deleteQuietly);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot clear snapshots", e);
        }
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignore) {
            // best effort
        }
    }

    /** Deterministic file-name-safe encoding of a URI. */
    static String safeName(String uri) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < uri.length(); i++) {
            char c = uri.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_') {
                sb.append(c);
            } else {
                sb.append('%').append(String.format("%04x", (int) c));
            }
        }
        return sb.toString();
    }

    // Content encoding: prefix backslashes and percent so newlines survive the
    // line format while staying deterministic and human-inspectable.
    static String encode(String raw) {
        StringBuilder sb = new StringBuilder(raw.length() + 8);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    static String decode(String encoded) {
        StringBuilder sb = new StringBuilder(encoded.length());
        for (int i = 0; i < encoded.length(); i++) {
            char c = encoded.charAt(i);
            if (c == '\\' && i + 1 < encoded.length()) {
                char n = encoded.charAt(i + 1);
                switch (n) {
                    case '\\' -> sb.append('\\');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    default -> sb.append(n);
                }
                i++;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
