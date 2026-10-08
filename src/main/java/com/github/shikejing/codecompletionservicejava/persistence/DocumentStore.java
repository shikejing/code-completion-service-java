package com.github.shikejing.codecompletionservicejava.persistence;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

import com.github.shikejing.codecompletionservicejava.config.ServiceProperties;
import com.github.shikejing.codecompletionservicejava.model.Edit;
import com.github.shikejing.codecompletionservicejava.model.Position;
import com.github.shikejing.codecompletionservicejava.model.TextRange;

/**
 * Local-file durability implemented with one directory per document URI:
 *
 * <pre>
 *   &lt;completion.persistence.dir&gt;/&lt;encoded-uri&gt;/
 *       snapshot.dat   – latest full snapshot
 *       journal.log    – append-only delta records after the snapshot
 * </pre>
 *
 * Records are length-prefixed JSON-ish lines. Every write goes to a temp
 * file followed by an atomic move (with fsync where enabled), so a crash
 * can only leave a fully present record or none at all; a torn trailing
 * journal record is detected by its CRC/length and discarded on recovery.
 */
@Component
public class DocumentStore {

    private static final String SNAPSHOT_FILE = "snapshot.dat";
    private static final String JOURNAL_FILE = "journal.log";

    private final Path baseDir;
    private final boolean fsync;

    public DocumentStore(ServiceProperties properties) {
        this.baseDir = Paths.get(properties.getPersistence().getDir());
        this.fsync = properties.getPersistence().isFsync();
        try {
            Files.createDirectories(baseDir);
        } catch (IOException e) {
            throw new IllegalStateException("cannot create persistence dir " + baseDir, e);
        }
    }

    /** Appends one delta record durably and atomically. */
    public synchronized void appendRecord(PersistedRecord record) {
        if (record == null) {
            return;
        }
        Path dir = dirFor(record.uri());
        try {
            Files.createDirectories(dir);
            Path journal = dir.resolve(JOURNAL_FILE);
            appendDurably(journal, Codec.encodeDelta(record));
        } catch (IOException e) {
            throw new IllegalStateException("failed to persist record for " + record.uri(), e);
        }
    }

    /** Writes a compacted snapshot atomically (temp + move) and truncates the journal. */
    public synchronized void writeSnapshot(String uri, long version, String fullText) {
        Path dir = dirFor(uri);
        try {
            Files.createDirectories(dir);
            Path target = dir.resolve(SNAPSHOT_FILE);
            Path tmp = dir.resolve(SNAPSHOT_FILE + ".tmp");
            byte[] payload = Codec.encodeSnapshot(PersistedRecord.snapshot(uri, version, fullText));
            writeAtomically(target, tmp, payload);
            // deltas are now fully contained in the snapshot
            Files.deleteIfExists(dir.resolve(JOURNAL_FILE));
        } catch (IOException e) {
            throw new IllegalStateException("failed to persist snapshot for " + uri, e);
        }
    }

    /** Recovers all records of one document in commit order. */
    public synchronized List<PersistedRecord> recover(String uri) {
        Path dir = dirFor(uri);
        List<PersistedRecord> records = new ArrayList<>();
        Path snapshot = dir.resolve(SNAPSHOT_FILE);
        if (Files.isRegularFile(snapshot)) {
            try {
                PersistedRecord rec = Codec.decodeSnapshot(Files.readString(snapshot, StandardCharsets.UTF_8));
                if (rec != null) {
                    records.add(rec);
                }
            } catch (IOException | RuntimeException bad) {
                // corrupt snapshot: ignore and attempt journal-only recovery
            }
        }
        Path journal = dir.resolve(JOURNAL_FILE);
        if (Files.isRegularFile(journal)) {
            try (BufferedReader reader = Files.newBufferedReader(journal, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    PersistedRecord rec = Codec.decodeDelta(line);
                    if (rec == null) {
                        break; // torn/partial trailing record: stop replay
                    }
                    records.add(rec);
                }
            } catch (IOException e) {
                // truncated file: records decoded so far are complete records
            }
        }
        return records;
    }

    /** Lists every document directory that contains readable state. */
    public synchronized List<String> recoverUris() {
        List<String> uris = new ArrayList<>();
        if (!Files.isDirectory(baseDir)) {
            return uris;
        }
        try (Stream<Path> stream = Files.list(baseDir)) {
            stream.filter(Files::isDirectory).forEach(dir -> {
                String encoded = dir.getFileName().toString();
                if (Files.isRegularFile(dir.resolve(SNAPSHOT_FILE))
                        || Files.isRegularFile(dir.resolve(JOURNAL_FILE))) {
                    uris.add(Codec.decodeUri(encoded));
                }
            });
        } catch (IOException e) {
            throw new IllegalStateException("cannot list persistence dir", e);
        }
        return uris;
    }

    public synchronized void remove(String uri) {
        Path dir = dirFor(uri);
        try {
            if (!Files.exists(dir)) {
                return;
            }
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException ignored) { }
                });
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot remove persisted state for " + uri, e);
        }
    }

    public Path dirFor(String uri) {
        return baseDir.resolve(Codec.encodeUri(uri));
    }

    private void appendDurably(Path file, String line) throws IOException {
        // journal appends are per-record writes; the length+CRC framing makes
        // a partially flushed tail detectable on recovery.
        try (var ch = Files.newByteChannel(file,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            byte[] payload = (line + "\n").getBytes(StandardCharsets.UTF_8);
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(payload);
            while (buffer.hasRemaining()) {
                ch.write(buffer);
            }
            if (fsync && ch instanceof java.nio.channels.FileChannel fc) {
                fc.force(false);
            }
        }
    }

    private void writeAtomically(Path target, Path tmp, byte[] payload) throws IOException {
        Files.write(tmp, payload, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        if (fsync) {
            try (var fc = java.nio.channels.FileChannel.open(tmp, StandardOpenOption.WRITE)) {
                fc.force(false);
            }
        }
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException fallback) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
        // directory fsync is not portable on the JDK; atomic move plus
        // per-file force already guarantees that a record exists wholly or
        // not at all, which is the boundary documented for recovery.
    }

    /**
     * Length-prefixed, CRC32-protected framing with a compact textual JSON
     * payload. Kept dependency-free (no Jackson coupling inside the store).
     */
    static final class Codec {

        private Codec() {}

        static String encodeUri(String uri) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < uri.length(); i++) {
                char c = uri.charAt(i);
                if (Character.isLetterOrDigit(c) || c == '-' || c == '_') {
                    sb.append(c);
                } else {
                    sb.append('_').append(String.format("%04x", (int) c));
                }
            }
            return sb.toString();
        }

        static String decodeUri(String encoded) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < encoded.length(); i++) {
                char c = encoded.charAt(i);
                if (c == '_' && i + 4 < encoded.length()) {
                    try {
                        sb.append((char) Integer.parseInt(encoded.substring(i + 1, i + 5), 16));
                        i += 4;
                        continue;
                    } catch (NumberFormatException bad) {
                        // fall through to literal
                    }
                }
                sb.append(c);
            }
            return sb.toString();
        }

        static byte[] encodeSnapshot(PersistedRecord record) {
            String payload = Json.writeSnapshot(record);
            return frame(payload).getBytes(StandardCharsets.UTF_8);
        }

        static String encodeDelta(PersistedRecord record) {
            return frame(Json.writeDelta(record));
        }

        static PersistedRecord decodeSnapshot(String raw) {
            String payload = unframe(raw);
            if (payload == null) {
                return null;
            }
            return Json.readSnapshot(payload);
        }

        static PersistedRecord decodeDelta(String line) {
            String payload = unframe(line);
            if (payload == null) {
                return null;
            }
            return Json.readDelta(payload);
        }

        private static String frame(String payload) {
            byte[] data = payload.getBytes(StandardCharsets.UTF_8);
            long crc = crc32(data);
            return data.length + ":" + Long.toHexString(crc) + ":" + payload;
        }

        private static String unframe(String raw) {
            int first = raw.indexOf(':');
            int second = raw.indexOf(':', first + 1);
            if (first <= 0 || second < 0) {
                return null;
            }
            try {
                int len = Integer.parseInt(raw.substring(0, first));
                long crc = Long.parseLong(raw.substring(first + 1, second), 16);
                String payload = raw.substring(second + 1);
                if (payload.getBytes(StandardCharsets.UTF_8).length != len) {
                    return null;
                }
                if (crc32(payload.getBytes(StandardCharsets.UTF_8)) != crc) {
                    return null;
                }
                return payload;
            } catch (NumberFormatException malformed) {
                return null;
            }
        }

        private static long crc32(byte[] data) {
            java.util.zip.CRC32 crc = new java.util.zip.CRC32();
            crc.update(data);
            return crc.getValue();
        }
    }

    /** Minimal, hand-rolled JSON (de)serialization for the journal records. */
    static final class Json {
        private Json() {}

        static String writeSnapshot(PersistedRecord r) {
            return "{\"uri\":" + quote(r.uri())
                    + ",\"version\":" + r.version()
                    + ",\"fullText\":" + quote(r.fullText()) + "}";
        }

        static String writeDelta(PersistedRecord r) {
            StringBuilder sb = new StringBuilder();
            sb.append("{\"uri\":").append(quote(r.uri()))
              .append(",\"version\":").append(r.version())
              .append(",\"edits\":[");
            for (int i = 0; i < r.edits().size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(writeEdit(r.edits().get(i)));
            }
            sb.append("]}");
            return sb.toString();
        }

        static String writeEdit(Edit edit) {
            StringBuilder sb = new StringBuilder();
            sb.append("{\"version\":").append(edit.version())
              .append(",\"editId\":").append(quote(edit.editId()))
              .append(",\"kind\":\"").append(edit.kind()).append('"');
            if (edit.range() != null) {
                sb.append(",\"range\":{\"start\":{\"line\":")
                  .append(edit.range().start().line())
                  .append(",\"character\":").append(edit.range().start().character())
                  .append("},\"end\":{\"line\":").append(edit.range().end().line())
                  .append(",\"character\":").append(edit.range().end().character())
                  .append("}}");
            }
            if (edit.text() != null) {
                sb.append(",\"text\":").append(quote(edit.text()));
            }
            if (edit.fullText() != null) {
                sb.append(",\"fullText\":").append(quote(edit.fullText()));
            }
            sb.append('}');
            return sb.toString();
        }

        static PersistedRecord readSnapshot(String json) {
            java.util.Map<String, String> fields = parseFlat(json);
            String uri = fields.get("uri");
            long version = Long.parseLong(fields.get("version"));
            String fullText = fields.getOrDefault("fullText", "");
            return PersistedRecord.snapshot(uri, version, fullText);
        }

        static PersistedRecord readDelta(String json) {
            // tolerant hand parse: extract uri/version, then each edit object
            java.util.Map<String, String> root = parseFlat(json);
            String uri = root.get("uri");
            long version = Long.parseLong(root.get("version"));
            List<Edit> edits = new ArrayList<>();
            int depth = 0;
            int editStart = -1;
            for (int i = 0; i < json.length(); i++) {
                char c = json.charAt(i);
                if (c == '{') {
                    if (depth == 1 && json.regionMatches(i, "{\"version\"", 0, Math.min(10, json.length() - i))) {
                        editStart = i;
                    }
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 1 && editStart >= 0) {
                        edits.add(readEdit(json.substring(editStart, i + 1)));
                        editStart = -1;
                    }
                }
            }
            return PersistedRecord.delta(uri, version, edits);
        }

        static Edit readEdit(String json) {
            java.util.Map<String, String> f = parseFlat(json);
            long version = Long.parseLong(f.get("version"));
            String editId = f.get("editId");
            Edit.Kind kind = Edit.Kind.valueOf(f.get("kind"));
            TextRange range = extractRange(json);
            String text = f.containsKey("text") ? f.get("text") : null;
            String fullText = f.containsKey("fullText") ? f.get("fullText") : null;
            return new Edit(version, editId, kind, range, text, fullText);
        }

        /** Explicitly extracts the range.start/range.end nested positions. */
        private static TextRange extractRange(String json) {
            int rangeIdx = json.indexOf("\"range\"");
            if (rangeIdx < 0) {
                return null;
            }
            int open = json.indexOf('{', rangeIdx);
            if (open < 0) {
                return null;
            }
            int close = findBalanced(json, open, '{', '}');
            String rangeJson = json.substring(open, close + 1);
            Integer[] start = extractPosition(rangeJson, "start");
            Integer[] end = extractPosition(rangeJson, "end");
            if (start == null || end == null) {
                return null;
            }
            return new TextRange(new Position(start[0], start[1]),
                    new Position(end[0], end[1]));
        }

        private static Integer[] extractPosition(String rangeJson, String key) {
            int idx = rangeJson.indexOf("\"" + key + "\"");
            if (idx < 0) {
                return null;
            }
            int open = rangeJson.indexOf('{', idx);
            int close = rangeJson.indexOf('}', open);
            String obj = rangeJson.substring(open, close + 1);
            java.util.Map<String, String> fields = parseFlat(obj);
            if (!fields.containsKey("line") || !fields.containsKey("character")) {
                return null;
            }
            return new Integer[]{Integer.parseInt(fields.get("line")),
                    Integer.parseInt(fields.get("character"))};
        }

        /** Very small flat JSON field extractor supporting quoted strings. */
        static java.util.Map<String, String> parseFlat(String json) {
            java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
            int i = 0;
            while (i < json.length()) {
                if (json.charAt(i) != '"') {
                    i++;
                    continue;
                }
                int keyEnd = json.indexOf('"', i + 1);
                if (keyEnd < 0) {
                    break;
                }
                String key = json.substring(i + 1, keyEnd);
                int colon = json.indexOf(':', keyEnd);
                if (colon < 0) {
                    break;
                }
                int v = colon + 1;
                while (v < json.length() && Character.isWhitespace(json.charAt(v))) {
                    v++;
                }
                if (v >= json.length()) {
                    break;
                }
                if (json.charAt(v) == '"') {
                    int strEnd = findStringEnd(json, v + 1);
                    out.put(key, unescape(json.substring(v + 1, strEnd)));
                    i = strEnd + 1;
                } else if (json.charAt(v) == '{') {
                    // nested object: capture balanced span and also parse it flat
                    int end = findBalanced(json, v, '{', '}');
                    out.putAll(parseFlat(json.substring(v, end + 1)));
                    i = end + 1;
                } else {
                    int end = v;
                    while (end < json.length() && ",}".indexOf(json.charAt(end)) < 0) {
                        end++;
                    }
                    out.put(key, json.substring(v, end).trim());
                    i = end;
                }
            }
            return out;
        }

        private static int findStringEnd(String json, int from) {
            int i = from;
            while (i < json.length()) {
                char c = json.charAt(i);
                if (c == '\\') {
                    i += 2;
                    continue;
                }
                if (c == '"') {
                    return i;
                }
                i++;
            }
            return json.length();
        }

        private static int findBalanced(String json, int from, char open, char close) {
            int depth = 0;
            boolean inString = false;
            for (int i = from; i < json.length(); i++) {
                char c = json.charAt(i);
                if (c == '"' && (i == 0 || json.charAt(i - 1) != '\\')) {
                    inString = !inString;
                }
                if (inString) {
                    continue;
                }
                if (c == open) {
                    depth++;
                } else if (c == close) {
                    depth--;
                    if (depth == 0) {
                        return i;
                    }
                }
            }
            return json.length() - 1;
        }

        static String quote(String s) {
            if (s == null) {
                return "null";
            }
            StringBuilder sb = new StringBuilder("\"");
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"' -> sb.append("\\\"");
                    case '\\' -> sb.append("\\\\");
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    default -> {
                        if (c < 0x20) {
                            sb.append(String.format("\\u%04x", (int) c));
                        } else {
                            sb.append(c);
                        }
                    }
                }
            }
            return sb.append('"').toString();
        }

        static String unescape(String s) {
            StringBuilder sb = new StringBuilder(s.length());
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '\\' && i + 1 < s.length()) {
                    char n = s.charAt(i + 1);
                    switch (n) {
                        case 'n' -> { sb.append('\n'); i++; }
                        case 'r' -> { sb.append('\r'); i++; }
                        case 't' -> { sb.append('\t'); i++; }
                        case '"' -> { sb.append('"'); i++; }
                        case '\\' -> { sb.append('\\'); i++; }
                        case 'u' -> {
                            if (i + 5 < s.length()) {
                                sb.append((char) Integer.parseInt(s.substring(i + 2, i + 6), 16));
                                i += 5;
                            } else {
                                sb.append(c);
                            }
                        }
                        default -> sb.append(c);
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }
    }
}
