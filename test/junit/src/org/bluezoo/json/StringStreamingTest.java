package org.bluezoo.json;

import org.junit.Test;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Tests that string values are streamed: delivered as startStringValue, any
 * number of stringValueContent calls, then endStringValue, as the input
 * arrives and without the parser holding the whole string.
 */
public class StringStreamingTest {

    /** Records every string event, copying content (it is only valid during the call). */
    private static final class Recorder extends JSONDefaultHandler {
        final List<String> events = new ArrayList<>();
        final List<String> contents = new ArrayList<>();
        private char lastChar;

        @Override
        public void startArray() {
            events.add("[");
        }

        @Override
        public void endArray() {
            events.add("]");
        }

        @Override
        public void key(String key) {
            events.add("key:" + key);
        }

        @Override
        public void startStringValue() {
            events.add("start");
            lastChar = 0;
        }

        @Override
        public void stringValueContent(CharSequence content) {
            assertTrue("empty content was delivered", content.length() > 0);
            assertFalse("a surrogate pair was split across content calls",
                    Character.isHighSurrogate(lastChar) && Character.isLowSurrogate(content.charAt(0)));
            lastChar = content.charAt(content.length() - 1);
            String copy = content.toString();
            contents.add(copy);
            events.add("content:" + copy);
        }

        @Override
        public void endStringValue() {
            events.add("end");
        }

        String joinedContent() {
            return String.join("", contents);
        }
    }

    /** Feeds the document in chunks of the given size using the compact/flip lifecycle. */
    private static void feed(JSONParser parser, byte[] bytes, int chunk) throws JSONException {
        ByteBuffer buffer = ByteBuffer.allocate(chunk + 256);
        for (int off = 0; off < bytes.length; off += chunk) {
            buffer.put(bytes, off, Math.min(chunk, bytes.length - off));
            buffer.flip();
            parser.receive(buffer);
            buffer.compact();
        }
    }

    private static Recorder parse(String json, int chunk) throws JSONException {
        Recorder handler = new Recorder();
        JSONParser parser = new JSONParser();
        parser.disableAllLimits();
        parser.setContentHandler(handler);
        feed(parser, json.getBytes(StandardCharsets.UTF_8), chunk);
        parser.close();
        return handler;
    }

    @Test
    public void stringIsStartContentEnd() throws Exception {
        Recorder r = parse("[\"abc\"]", 1000);
        assertEquals("[,start,content:abc,end,]", String.join(",", r.events));
    }

    @Test
    public void emptyStringHasNoContent() throws Exception {
        Recorder r = parse("[\"\"]", 1);
        assertEquals("[,start,end,]", String.join(",", r.events));
    }

    @Test
    public void keysAreStillDeliveredWhole() throws Exception {
        // A key is one token even when it arrives a byte at a time; only the value is streamed
        Recorder r = new Recorder();
        JSONParser parser = new JSONParser();
        parser.setContentHandler(r);
        feed(parser, "{\"key\":\"v\"}".getBytes(StandardCharsets.UTF_8), 1);
        parser.close();
        assertEquals("key:key,start,content:v,end", String.join(",", r.events));
    }

    @Test
    public void contentIsDeliveredBeforeTheStringEnds() throws Exception {
        Recorder handler = new Recorder();
        JSONParser parser = new JSONParser();
        parser.setContentHandler(handler);

        ByteBuffer buffer = ByteBuffer.allocate(64);
        buffer.put("[\"abc".getBytes(StandardCharsets.UTF_8)).flip();
        parser.receive(buffer);

        assertEquals("abc", handler.joinedContent());
        assertFalse("the string has not ended yet", handler.events.contains("end"));
    }

    @Test
    public void longStringIsDeliveredInBoundedChunks() throws Exception {
        int chunk = 8192;
        int length = 4 * 1024 * 1024;
        StringBuilder sb = new StringBuilder("[\"");
        for (int i = 0; i < length; i++) {
            sb.append((char) ('a' + i % 26));
        }
        sb.append("\"]");

        Recorder r = parse(sb.toString(), chunk);
        assertEquals(sb.substring(2, 2 + length), r.joinedContent());
        assertTrue("delivered in many pieces", r.contents.size() >= length / chunk);
        for (String c : r.contents) {
            assertTrue("each piece fits in the receive buffer", c.length() <= chunk + 256);
        }
    }

    @Test
    public void multiByteCharactersAreReassembledAtEveryChunkSize() throws Exception {
        StringBuilder value = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            value.append("aé€😀");
        }
        for (int chunk = 1; chunk <= 17; chunk++) {
            Recorder r = parse("[\"" + value + "\"]", chunk);
            assertEquals("chunk=" + chunk, value.toString(), r.joinedContent());
        }
    }

    @Test
    public void escapedSurrogatePairsAreNeverSplit() throws Exception {
        StringBuilder json = new StringBuilder("[\"");
        StringBuilder expected = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            json.append("x\\ud83d\\ude00\\u00e9\\n");
            expected.append("x😀é\n");
        }
        json.append("\"]");
        for (int chunk = 1; chunk <= 19; chunk++) {
            Recorder r = parse(json.toString(), chunk);
            assertEquals("chunk=" + chunk, expected.toString(), r.joinedContent());
        }
    }

    @Test
    public void loneSurrogateEscapesPassThrough() throws Exception {
        String expected = "a\ud83dx\ude00b\ud83d";
        for (int chunk = 1; chunk <= 9; chunk++) {
            Recorder r = parse("[\"a\\ud83dx\\ude00b\\ud83d\"]", chunk);
            assertEquals("chunk=" + chunk, expected, r.joinedContent());
        }
    }

    @Test
    public void unclosedStringIsRejectedAfterItsContentWasDelivered() throws Exception {
        Recorder handler = new Recorder();
        JSONParser parser = new JSONParser();
        parser.setContentHandler(handler);
        feed(parser, "[\"abc".getBytes(StandardCharsets.UTF_8), 2);
        try {
            parser.close();
            fail("an unterminated string must be rejected");
        } catch (JSONException expected) {
            assertEquals("abc", handler.joinedContent());
        }
    }

    @Test
    public void malformedEscapeIsReportedAfterEarlierContent() throws Exception {
        Recorder handler = new Recorder();
        JSONParser parser = new JSONParser();
        parser.setContentHandler(handler);
        try {
            // A byte at a time, so "abc" is read (and delivered) before the bad escape is seen
            feed(parser, "[\"abc\\q\"]".getBytes(StandardCharsets.UTF_8), 1);
            fail("an invalid escape must be rejected");
        } catch (JSONException expected) {
            assertEquals("provisional content was already delivered", "abc", handler.joinedContent());
            assertFalse(handler.events.contains("end"));
        }
    }

    @Test
    public void malformedUtf8IsRejectedAtEveryChunkSize() throws Exception {
        // lone continuation, truncated 2/3/4-byte sequences (before the closing
        // quote, so they can never be completed), overlong, surrogate and
        // out-of-range encodings, invalid lead bytes
        int[][] bad = {
            {0x80}, {0xC3}, {0xE2, 0x82}, {0xF0, 0x9F, 0x98}, {0xC0, 0xAF}, {0xC1, 0xBF},
            {0xE0, 0x80, 0x80}, {0xED, 0xA0, 0x80}, {0xF4, 0x90, 0x80, 0x80}, {0xF8, 0x88, 0x80, 0x80, 0x80},
            {0xFF}, {'a', 0xE2, 0x82}, {0xE2, 0x82, 'a'}
        };
        for (int[] seq : bad) {
            byte[] doc = new byte[seq.length + 4];
            doc[0] = '[';
            doc[1] = '"';
            for (int i = 0; i < seq.length; i++) {
                doc[2 + i] = (byte) seq[i];
            }
            doc[2 + seq.length] = '"';
            doc[3 + seq.length] = ']';
            for (int chunk : new int[] {1, 2, 3, 1000}) {
                JSONParser parser = new JSONParser();
                parser.setContentHandler(new Recorder());
                try {
                    feed(parser, doc, chunk);
                    parser.close();
                    fail("malformed UTF-8 " + java.util.Arrays.toString(seq) + " accepted at chunk=" + chunk);
                } catch (JSONException expected) {
                    // rejected
                }
            }
        }
    }

    @Test
    public void validUtf8OfEveryLengthIsAccepted() throws Exception {
        String value = "\u0041\u00df\u0800\uffff\ud83d\ude00\udbff\udfff";
        for (int chunk = 1; chunk <= 12; chunk++) {
            assertEquals("chunk=" + chunk, value, parse("[\"" + value + "\"]", chunk).joinedContent());
        }
    }

    @Test
    public void maxStringLengthAppliesAcrossChunks() throws Exception {
        for (int chunk : new int[] {1, 3, 1000}) {
            JSONParser ok = new JSONParser();
            ok.setContentHandler(new Recorder());
            ok.setMaxStringLength(10);
            feed(ok, "[\"0123456789\"]".getBytes(StandardCharsets.UTF_8), chunk);
            ok.close();

            JSONParser tooLong = new JSONParser();
            tooLong.setContentHandler(new Recorder());
            tooLong.setMaxStringLength(10);
            try {
                feed(tooLong, "[\"01234567890\"]".getBytes(StandardCharsets.UTF_8), chunk);
                tooLong.close();
                fail("chunk=" + chunk + ": a string over the limit must be rejected");
            } catch (JSONException expected) {
                // rejected
            }
        }
    }

    private static String repeat(char c, int times) {
        char[] chars = new char[times];
        java.util.Arrays.fill(chars, c);
        return new String(chars);
    }

    /** A string mixing 1, 2, 3 and 4 byte UTF-8 and escapes, so slices of any size meet every kind of boundary. */
    private static String mixedJson(int units, StringBuilder expected) {
        StringBuilder json = new StringBuilder("[\"");
        for (int i = 0; i < units; i++) {
            switch (i % 5) {
                case 0: json.append("ab"); expected.append("ab"); break;
                case 1: json.append("\u00e9"); expected.append("\u00e9"); break;
                case 2: json.append("\u20ac"); expected.append("\u20ac"); break;
                case 3: json.append("\ud83d\ude00"); expected.append("\ud83d\ude00"); break;
                default: json.append("\\ud83d\\ude00\\n"); expected.append("\ud83d\ude00\n"); break;
            }
        }
        return json.append("\"]").toString();
    }

    private static Recorder parseWithChunkSize(String json, int stringChunkSize, int receiveChunk) throws JSONException {
        Recorder handler = new Recorder();
        JSONParser parser = new JSONParser();
        parser.disableAllLimits();
        parser.setStringChunkSize(stringChunkSize);
        parser.setContentHandler(handler);
        feed(parser, json.getBytes(StandardCharsets.UTF_8), receiveChunk);
        parser.close();
        return handler;
    }

    @Test
    public void contentPiecesNeverExceedTheChunkSize() throws Exception {
        // the whole document in one receive buffer: only the chunk size limits a piece
        StringBuilder expected = new StringBuilder();
        String json = mixedJson(4000, expected);
        for (int size : new int[] {16, 17, 31, 64, 1000}) {
            Recorder r = parseWithChunkSize(json, size, json.length() * 4);
            assertEquals("chunk size " + size, expected.toString(), r.joinedContent());
            for (String piece : r.contents) {
                assertTrue("chunk size " + size + " piece of " + piece.length(), piece.length() <= size);
            }
            assertTrue("split into many pieces", r.contents.size() >= expected.length() / size);
        }
    }

    @Test
    public void sliceBoundariesCutThroughMultiByteCharactersSafely() throws Exception {
        // One long run with no escapes in it, so it is cut into slices at
        // arbitrary bytes, many of them in the middle of a 2, 3 or 4 byte
        // character; every cut must be resumed in the next slice
        StringBuilder value = new StringBuilder();
        for (int i = 0; i < 3000; i++) {
            value.append("a\u00e9\u20ac\ud83d\ude00");
        }
        String json = "[\"" + value + "\"]";
        for (int size = 16; size <= 40; size++) {
            Recorder r = parseWithChunkSize(json, size, json.length() * 4);
            assertEquals("chunk size " + size, value.toString(), r.joinedContent());
            for (String piece : r.contents) {
                assertTrue("chunk size " + size, piece.length() <= size);
            }
        }
        // and the same when the receive buffer is itself small and cuts the run first
        for (int receive : new int[] {1, 3, 5, 7}) {
            Recorder r = parseWithChunkSize(json, 17, receive);
            assertEquals("receive " + receive, value.toString(), r.joinedContent());
        }
    }

    @Test
    public void malformedUtf8InTheMiddleOfASlicedRunIsStillRejected() throws Exception {
        // a lone continuation byte far into a long run that is delivered in slices
        byte[] prefix = ("[\"" + repeat('a', 500)).getBytes(StandardCharsets.UTF_8);
        byte[] doc = new byte[prefix.length + 4];
        System.arraycopy(prefix, 0, doc, 0, prefix.length);
        doc[prefix.length] = (byte) 0x80;
        doc[prefix.length + 1] = 'b';
        doc[prefix.length + 2] = '"';
        doc[prefix.length + 3] = ']';
        JSONParser parser = new JSONParser();
        parser.disableAllLimits();
        parser.setStringChunkSize(16);
        parser.setContentHandler(new Recorder());
        try {
            feed(parser, doc, doc.length);
            parser.close();
            fail("a stray continuation byte must be rejected");
        } catch (JSONException expected) {
            // rejected
        }
    }

    @Test
    public void chunkSizeAndReceiveChunkingAreIndependent() throws Exception {
        StringBuilder expected = new StringBuilder();
        String json = mixedJson(200, expected);
        for (int size : new int[] {16, 17, 19, 64}) {
            for (int receive = 1; receive <= 11; receive++) {
                Recorder r = parseWithChunkSize(json, size, receive);
                assertEquals("chunk size " + size + " receive " + receive, expected.toString(), r.joinedContent());
                for (String piece : r.contents) {
                    assertTrue(piece.length() <= size);
                }
            }
        }
    }

    @Test
    public void defaultChunkSizeIs32KAndIndependentOfTheReceiveBuffer() throws Exception {
        StringBuilder sb = new StringBuilder("[\"");
        for (int i = 0; i < 100_000; i++) {
            sb.append('a');
        }
        sb.append("\"]");
        Recorder handler = new Recorder();
        JSONParser parser = new JSONParser();
        parser.setContentHandler(handler);
        // a single 100KB receive buffer
        byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(bytes.length);
        buffer.put(bytes).flip();
        parser.receive(buffer);
        parser.close();

        int longest = 0;
        for (String piece : handler.contents) {
            longest = Math.max(longest, piece.length());
        }
        assertEquals(32768, longest);
        assertEquals(100_000, handler.joinedContent().length());
        assertEquals("character buffer is the chunk size, not the receive buffer size",
                32768, capacityOf(parser, "chars", false));
    }

    @Test
    public void tooSmallAChunkSizeIsRejected() throws Exception {
        JSONParser parser = new JSONParser();
        parser.setStringChunkSize(16);
        try {
            parser.setStringChunkSize(15);
            fail("a chunk must hold the longest escape or character");
        } catch (IllegalArgumentException expected) {
            // rejected
        }
    }

    @Test
    public void chunkSizeCanBeChangedBetweenStrings() throws Exception {
        Recorder handler = new Recorder();
        JSONParser parser = new JSONParser();
        parser.setContentHandler(handler);
        parser.setStringChunkSize(64);
        ByteBuffer buffer = ByteBuffer.allocate(1 << 16);
        buffer.put(("[\"" + repeat('a', 300) + "\"").getBytes(StandardCharsets.UTF_8)).flip();
        parser.receive(buffer);
        buffer.compact();
        int firstStringPieces = handler.contents.size();
        assertTrue(firstStringPieces >= 300 / 64);

        parser.setStringChunkSize(1024); // takes effect for the next string
        buffer.put((",\"" + repeat('b', 300) + "\"]").getBytes(StandardCharsets.UTF_8)).flip();
        parser.receive(buffer);
        parser.close();
        assertEquals("one piece for the second string", firstStringPieces + 1, handler.contents.size());
    }

    @Test
    public void memoryDoesNotGrowWithStringLength() throws Exception {
        // 64MB of string content in 8KB chunks: nothing the parser holds may
        // scale with that. Reflection reads the two internal buffers.
        int chunk = 8192;
        JSONParser parser = new JSONParser();
        parser.disableAllLimits();
        final long[] total = new long[1];
        parser.setContentHandler(new JSONDefaultHandler() {
            @Override
            public void stringValueContent(CharSequence content) {
                total[0] += content.length();
            }
        });

        ByteBuffer buffer = ByteBuffer.allocate(chunk);
        byte[] filler = new byte[chunk];
        java.util.Arrays.fill(filler, (byte) 'a');

        buffer.put("[\"".getBytes(StandardCharsets.UTF_8)).flip();
        parser.receive(buffer);
        buffer.compact();
        int chunks = 8192; // 64MB
        for (int i = 0; i < chunks; i++) {
            buffer.put(filler, 0, buffer.remaining());
            buffer.flip();
            parser.receive(buffer);
            buffer.compact();
        }
        buffer.put("\"]".getBytes(StandardCharsets.UTF_8)).flip();
        parser.receive(buffer);
        parser.close();

        assertTrue("all of the content was delivered", total[0] >= (long) chunks * (chunk - 2));

        assertEquals("nothing was left unconsumed", 0, buffer.remaining());
        assertEquals("character buffer is the fixed chunk size", 32768, capacityOf(parser, "chars", false));
    }

    /** Capacity of a private CharBuffer field of the parser's tokenizer (or of a ByteBuffer field of the parser). */
    private static int capacityOf(JSONParser parser, String field, boolean onParser) throws Exception {
        Object holder = parser;
        if (!onParser) {
            Field tf = JSONParser.class.getDeclaredField("tokenizer");
            tf.setAccessible(true);
            holder = tf.get(parser);
        }
        Field f = holder.getClass().getDeclaredField(field);
        f.setAccessible(true);
        Object value = f.get(holder);
        if (value == null) {
            return 0;
        }
        return value instanceof ByteBuffer ? ((ByteBuffer) value).capacity() : ((CharBuffer) value).capacity();
    }
}
