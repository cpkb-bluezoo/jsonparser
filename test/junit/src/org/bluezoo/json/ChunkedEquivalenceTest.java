package org.bluezoo.json;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Random;
import java.util.function.IntSupplier;

import static org.junit.Assert.*;

/**
 * Oracle for the streaming contract: how a document is split into
 * {@code receive()} chunks must never change what the parser reports.
 * <p>
 * Every document (the whole JSONTestSuite corpus plus the inline documents
 * below) is parsed once in a single chunk to get the reference result, then
 * again with fixed chunk sizes down to one byte, and with seeded random
 * chunk sizes. The event stream (including the locator position reported
 * with each non-whitespace event) and the accept/reject outcome must be
 * identical every time.
 * <p>
 * The caller side follows the documented buffer lifecycle (flip, receive,
 * compact) and never re-presents consumed bytes, so this holds whether the
 * parser carries a partial token itself or leaves it unconsumed in the
 * caller's buffer.
 * <p>
 * Rejected documents are compared on outcome only. Once string content is
 * delivered incrementally, events for a string that later turns out to be
 * malformed are legitimately provisional and may differ with chunking.
 */
@RunWith(Parameterized.class)
public class ChunkedEquivalenceTest {

    private static final String TEST_DIR = "JSONTestSuite/test_parsing";
    private static final int[] CHUNK_SIZES = {1, 2, 3, 5, 7, 64, 4096};
    private static final int RANDOM_RUNS = 3;
    private static final int MAX_RANDOM_CHUNK = 17;

    // Room beyond the chunk for bytes the parser leaves unconsumed.
    private static final int SLACK = 64 * 1024;

    private final String name;
    private final byte[] doc;

    public ChunkedEquivalenceTest(String name, byte[] doc) {
        this.name = name;
        this.doc = doc;
    }

    @Parameters(name = "{0}")
    public static Collection<Object[]> data() throws IOException {
        List<Object[]> params = new ArrayList<>();

        File dir = new File(TEST_DIR);
        File[] files = dir.listFiles((d, n) -> n.endsWith(".json"));
        if (files == null || files.length == 0) {
            fail("No test files found in: " + TEST_DIR);
        }
        Arrays.sort(files);
        for (File f : files) {
            params.add(new Object[] {f.getName(), Files.readAllBytes(f.toPath())});
        }

        for (String[] d : inlineDocuments()) {
            params.add(new Object[] {"inline:" + d[0], d[1].getBytes(StandardCharsets.UTF_8)});
        }
        params.add(new Object[] {"inline:invalid-utf8-continuation", raw("[\"", 0x80, "\"]")});
        params.add(new Object[] {"inline:truncated-utf8-before-quote", raw("[\"", 0xE2, 0x82, "\"]")});
        params.add(new Object[] {"inline:overlong-utf8", raw("[\"", 0xC0, 0xAF, "\"]")});
        params.add(new Object[] {"inline:control-char-in-string", raw("[\"a", 0x01, "b\"]")});
        return params;
    }

    private static String[][] inlineDocuments() {
        StringBuilder longValue = new StringBuilder();
        for (int i = 0; i < 2000; i++) {
            // 1, 2, 3 and 4 byte UTF-8 sequences in one repeating unit
            longValue.append("a\u00e9\u20ac\ud83d\ude00");
        }
        StringBuilder longKey = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            longKey.append("k\u00e9\u20ac");
        }
        StringBuilder escapes = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            escapes.append("abcdef\\n").append("gh\\u00e9").append("\\ud83d\\ude00");
        }
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            deep.append('[');
        }
        deep.append("1");
        for (int i = 0; i < 200; i++) {
            deep.append(']');
        }
        StringBuilder many = new StringBuilder("[");
        for (int i = 0; i < 300; i++) {
            if (i > 0) {
                many.append(',');
            }
            many.append('"');
            for (int j = 0; j < i % 23; j++) {
                many.append((char) ('a' + (j % 26)));
            }
            many.append('"');
        }
        many.append(']');

        return new String[][] {
            {"multibyte-values", "[\"caf\u00e9\",\"\u20ac100\",\"\ud83d\ude00 smile\",\"\u4e2d\u6587\"]"},
            {"multibyte-keys", "{\"caf\u00e9\":1,\"\u20ac\":2,\"\ud83d\ude00\":3}"},
            {"escapes", "[\"\\n\\t\\r\\b\\f\\\\\\/\\\"\",\"\\u00e9\\u20ac\",\"\\ud83d\\ude00\","
                    + "\"x\\ud83dy\",\"\\ude00\",\"\\u0000\"]"},
            {"escaped-key", "{\"k\\u00e9y\\n\":\"v\\ud83d\\ude00\"}"},
            {"numbers", "[0,-0,1,-1,12345,2147483647,2147483648,-2147483648,-2147483649,"
                    + "9223372036854775807,9223372036854775808,-9223372036854775808,"
                    + "123456789012345678901234567890,0.5,-0.5,1.5e3,1E-2,1e+2,123.456e-7,"
                    + "0.1234567890123456789,1e400,1e-400,3.141592653589793,0e0,-0.0]"},
            {"root-number-int", "42"},
            {"root-number-real", "-17.5e2"},
            {"root-zero", "0"},
            {"root-string", "\"hello \u00e9\""},
            {"root-true", "true"},
            {"literals", "[true,false,null,[true,[false,[null]]]]"},
            {"whitespace-crlf", "{\r\n  \"a\" : [ 1 ,\r\n 2 ] ,\n\t\"b\"\r:\rnull\r\n}\r\n"},
            {"bom", "\ufeff{\"a\":[1,2,3]}"},
            {"empty-string-and-key", "{\"\":\"\"}"},
            {"deep-nesting", deep.toString()},
            {"many-small-strings", many.toString()},
            {"long-value", "{\"k\":\"" + longValue + "\"}"},
            {"long-key", "{\"" + longKey + "\":1}"},
            {"long-escaped-value", "[\"" + escapes + "\"]"},
        };
    }

    /** Builds a byte document from a mix of ASCII Strings and int byte values. */
    private static byte[] raw(Object... parts) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (Object p : parts) {
            if (p instanceof Integer) {
                out.write((Integer) p);
            } else {
                byte[] b = ((String) p).getBytes(StandardCharsets.US_ASCII);
                out.write(b, 0, b.length);
            }
        }
        return out.toByteArray();
    }

    /**
     * Records the event stream in a form that does not depend on how the
     * input was chunked. This is the one place that knows how string values
     * are delivered; if the handler API changes, only this class changes.
     */
    private static final class Recorder extends JSONDefaultHandler {
        final List<String> events = new ArrayList<>();
        private final StringBuilder ws = new StringBuilder();
        private JSONLocator locator;

        @Override
        public void setLocator(JSONLocator locator) {
            this.locator = locator;
        }

        @Override
        public boolean needsWhitespace() {
            return true;
        }

        @Override
        public void whitespace(String whitespace) {
            // A whitespace run may legitimately arrive in several pieces.
            ws.append(whitespace);
        }

        private void flushWhitespace() {
            if (ws.length() > 0) {
                events.add("WS:" + ws.toString().replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t"));
                ws.setLength(0);
            }
        }

        private String position() {
            return locator == null ? "" : "@" + locator.getLineNumber() + ":" + locator.getColumnNumber();
        }

        private void add(String event) {
            flushWhitespace();
            events.add(event + position());
        }

        void finish() {
            flushWhitespace();
        }

        @Override
        public void startObject() {
            add("START_OBJECT");
        }

        @Override
        public void endObject() {
            add("END_OBJECT");
        }

        @Override
        public void startArray() {
            add("START_ARRAY");
        }

        @Override
        public void endArray() {
            add("END_ARRAY");
        }

        @Override
        public void key(String key) {
            add("KEY:" + key);
        }

        // The one place that knows strings arrive in pieces. The locator
        // position reported with a string is where it started, so these
        // positions stay comparable with those of the other tokens.
        private StringBuilder string;
        private String stringStart;
        private char lastChar;

        @Override
        public void startStringValue() {
            string = new StringBuilder();
            stringStart = position();
            lastChar = 0;
        }

        @Override
        public void stringValueContent(CharSequence content) {
            if (content.length() == 0) {
                throw new AssertionError("Empty string content was delivered");
            }
            if (Character.isHighSurrogate(lastChar) && Character.isLowSurrogate(content.charAt(0))) {
                throw new AssertionError("A surrogate pair was split across two content calls");
            }
            string.append(content);
            lastChar = content.charAt(content.length() - 1);
        }

        @Override
        public void endStringValue() {
            flushWhitespace();
            events.add("STRING:" + string + stringStart);
            string = null;
        }

        @Override
        public void numberValue(Number value) {
            add("NUMBER:" + value.getClass().getSimpleName() + ":" + value);
        }

        @Override
        public void booleanValue(boolean value) {
            add("BOOLEAN:" + value);
        }

        @Override
        public void nullValue() {
            add("NULL");
        }
    }

    private static final class Outcome {
        final boolean accepted;
        final List<String> events;
        final String error;

        Outcome(boolean accepted, List<String> events, String error) {
            this.accepted = accepted;
            this.events = events;
            this.error = error;
        }
    }

    private static Outcome parse(byte[] doc, IntSupplier chunkSize, int maxChunk) {
        Recorder handler = new Recorder();
        JSONParser parser = new JSONParser();
        parser.disableAllLimits();
        parser.setContentHandler(handler);

        ByteBuffer buffer = ByteBuffer.allocate(maxChunk + SLACK);
        int offset = 0;
        try {
            while (offset < doc.length) {
                int n = Math.min(Math.min(chunkSize.getAsInt(), doc.length - offset), buffer.remaining());
                if (n == 0) {
                    throw new AssertionError("Receive buffer full of unconsumed bytes at offset " + offset);
                }
                buffer.put(doc, offset, n);
                offset += n;
                buffer.flip();
                parser.receive(buffer);
                buffer.compact();
            }
            parser.close();
            handler.finish();
            return new Outcome(true, handler.events, null);
        } catch (JSONException e) {
            handler.finish();
            return new Outcome(false, handler.events, e.getMessage());
        }
    }

    private String compare(String label, Outcome ref, Outcome got) {
        if (ref.accepted != got.accepted) {
            return label + ": " + (ref.accepted ? "accepted whole but rejected chunked (" + got.error + ")"
                    : "rejected whole (" + ref.error + ") but accepted chunked");
        }
        if (!ref.accepted) {
            return null;
        }
        int n = Math.min(ref.events.size(), got.events.size());
        for (int i = 0; i < n; i++) {
            if (!ref.events.get(i).equals(got.events.get(i))) {
                return label + ": event " + i + " differs: whole=" + abbreviate(ref.events.get(i))
                        + " chunked=" + abbreviate(got.events.get(i));
            }
        }
        if (ref.events.size() != got.events.size()) {
            return label + ": event count differs: whole=" + ref.events.size() + " chunked=" + got.events.size();
        }
        return null;
    }

    private static String abbreviate(String s) {
        return s.length() <= 80 ? s : s.substring(0, 77) + "...";
    }

    @Test
    public void chunkingDoesNotChangeBehaviour() {
        Outcome reference = parse(doc, () -> doc.length, doc.length);

        List<String> failures = new ArrayList<>();
        for (int size : CHUNK_SIZES) {
            String f = compare("chunk=" + size, reference, parse(doc, () -> size, size));
            if (f != null) {
                failures.add(f);
            }
        }
        for (int run = 0; run < RANDOM_RUNS; run++) {
            Random rnd = new Random(name.hashCode() * 31L + run);
            String f = compare("random#" + run, reference,
                    parse(doc, () -> 1 + rnd.nextInt(MAX_RANDOM_CHUNK), MAX_RANDOM_CHUNK));
            if (f != null) {
                failures.add(f);
            }
        }

        if (!failures.isEmpty()) {
            fail(name + " differs from its single-chunk parse:\n  " + String.join("\n  ", failures));
        }
    }
}
