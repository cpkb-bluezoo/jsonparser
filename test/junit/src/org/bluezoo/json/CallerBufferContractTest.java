package org.bluezoo.json;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

/**
 * Tests the buffer contract of {@link JSONParser#receive}: the parser holds
 * no bytes of its own. Anything it cannot use yet stays unconsumed in the
 * caller's buffer, and the caller compacts and presents it again with more
 * data.
 */
public class CallerBufferContractTest {

    private static ByteBuffer readable(int capacity, byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.allocate(capacity);
        buffer.put(bytes).flip();
        return buffer;
    }

    private static ByteBuffer readable(String text) {
        return readable(256, text.getBytes(StandardCharsets.UTF_8));
    }

    private static JSONParser parser() {
        JSONParser parser = new JSONParser();
        parser.setContentHandler(new JSONDefaultHandler());
        return parser;
    }

    @Test
    public void everythingIsConsumedWhenTheInputEndsOnATokenBoundary() throws Exception {
        ByteBuffer buffer = readable("{\"a\":[1,\"two\",true,null]} ");
        parser().receive(buffer);
        assertFalse(buffer.hasRemaining());
    }

    @Test
    public void incompleteKeyStaysInTheCallersBuffer() throws Exception {
        ByteBuffer buffer = readable("{\"abc");
        parser().receive(buffer);
        assertEquals("the opening quote and the key so far are unconsumed", 4, buffer.remaining());
        assertEquals('"', buffer.get(buffer.position()));
    }

    @Test
    public void incompleteLiteralStaysInTheCallersBuffer() throws Exception {
        ByteBuffer buffer = readable("[tr");
        parser().receive(buffer);
        assertEquals(2, buffer.remaining());
        assertEquals('t', buffer.get(buffer.position()));
    }

    @Test
    public void incompleteEscapeStaysInTheCallersBuffer() throws Exception {
        ByteBuffer buffer = readable("[\"ab\\u00");
        parser().receive(buffer);
        assertEquals("the escape from its backslash", 4, buffer.remaining());
        assertEquals('\\', buffer.get(buffer.position()));
    }

    @Test
    public void incompleteUtf8SequenceStaysInTheCallersBuffer() throws Exception {
        byte[] bytes = {'[', '"', 'a', (byte) 0xE2, (byte) 0x82};
        ByteBuffer buffer = readable(16, bytes);
        parser().receive(buffer);
        assertEquals(2, buffer.remaining());
        assertEquals((byte) 0xE2, buffer.get(buffer.position()));
    }

    @Test
    public void incompleteByteOrderMarkStaysInTheCallersBuffer() throws Exception {
        ByteBuffer buffer = readable(16, new byte[] {(byte) 0xEF, (byte) 0xBB});
        parser().receive(buffer);
        assertEquals(2, buffer.remaining());
    }

    @Test
    public void unconsumedBytesPresentedAgainCompleteTheToken() throws Exception {
        final StringBuilder keys = new StringBuilder();
        JSONParser parser = new JSONParser();
        parser.setContentHandler(new JSONDefaultHandler() {
            @Override
            public void key(String key) {
                keys.append(key);
            }
        });
        ByteBuffer buffer = ByteBuffer.allocate(64);
        buffer.put("{\"abc".getBytes(StandardCharsets.UTF_8)).flip();
        parser.receive(buffer);
        buffer.compact();
        buffer.put("def\":1}".getBytes(StandardCharsets.UTF_8)).flip();
        parser.receive(buffer);
        parser.close();
        assertEquals("abcdef", keys.toString());
    }

    @Test
    public void closeRejectsBytesLeftUnconsumedAndSaysSo() throws Exception {
        // Any of these would be rejected anyway (as an unclosed object, array
        // or string, or as no data); the leftover bytes give the precise reason
        for (String truncated : new String[] {"{\"abc", "[tr", "[\"ab\\u00", "{\"a\":nu"}) {
            JSONParser parser = parser();
            parser.receive(readable(truncated));
            try {
                parser.close();
                fail("'" + truncated + "' is incomplete and must be rejected");
            } catch (JSONException expected) {
                assertTrue("'" + truncated + "': " + expected.getMessage(),
                        expected.getMessage().contains("incomplete token"));
            }
        }
    }

    @Test
    public void droppingUnconsumedBytesIsDetected() throws Exception {
        JSONParser parser = parser();
        parser.receive(readable("{\"abc")); // 4 bytes left unconsumed
        try {
            parser.receive(readable("\":1}")); // presented without them
            fail("the unconsumed bytes were not presented again");
        } catch (JSONException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("unconsumed"));
        }
    }

    @Test
    public void presentingFewerBytesThanWereLeftIsDetected() throws Exception {
        JSONParser parser = parser();
        parser.receive(readable("{\"abc")); // 4 bytes left unconsumed
        try {
            parser.receive(readable("\"a")); // only 2 presented
            fail("fewer bytes than were left unconsumed");
        } catch (JSONException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("unconsumed"));
        }
    }

    @Test
    public void clearingInsteadOfCompactingIsDetected() throws Exception {
        // The commonest mistake: clear() throws the unconsumed bytes away, and
        // a longer read afterwards would hide it from a length check alone
        JSONParser parser = parser();
        ByteBuffer buffer = ByteBuffer.allocate(64);
        buffer.put("{\"abc".getBytes(StandardCharsets.UTF_8)).flip();
        parser.receive(buffer);
        assertEquals(4, buffer.remaining());
        buffer.clear();
        buffer.put("def\":1,\"another\":2}".getBytes(StandardCharsets.UTF_8)).flip();
        try {
            parser.receive(buffer);
            fail("the unconsumed bytes were thrown away");
        } catch (JSONException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("unconsumed"));
        }
    }

    @Test
    public void aParserCanBeReusedAfterADocumentThatEndedMidToken() throws Exception {
        JSONParser parser = parser();
        parser.receive(readable("{\"abc"));
        try {
            parser.close();
            fail("incomplete");
        } catch (JSONException expected) {
            // rejected
        }
        parser.reset();
        ByteBuffer buffer = readable("[1,2,3]");
        parser.receive(buffer);
        parser.close();
        assertFalse(buffer.hasRemaining());
    }

    @Test
    public void aBufferTooSmallForTheNextTokenIsReported() throws Exception {
        JSONParser parser = parser();
        parser.disableAllLimits();
        byte[] doc = "{\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\":1}".getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(16);
        int off = 0;
        try {
            while (off < doc.length) {
                int n = Math.min(buffer.remaining(), doc.length - off);
                if (n == 0) {
                    fail("the buffer is full of an unfinished token and the parser did not say so");
                }
                buffer.put(doc, off, n);
                off += n;
                buffer.flip();
                parser.receive(buffer);
                buffer.compact();
            }
            fail("a 44 byte key cannot fit a 16 byte buffer");
        } catch (JSONException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().toLowerCase().contains("buffer"));
        }
    }

    @Test
    public void parseFromAStreamUsesOneFixedBuffer() throws Exception {
        StringBuilder doc = new StringBuilder("{");
        for (int i = 0; i < 500; i++) {
            doc.append(i == 0 ? "" : ",").append("\"key").append(i).append("\":[").append(i * 1.5)
                    .append(",\"value number ").append(i).append("\",true,null]");
        }
        doc.append("}");
        final int[] keys = new int[1];
        JSONParser parser = new JSONParser(64);
        parser.setContentHandler(new JSONDefaultHandler() {
            @Override
            public void key(String key) {
                keys[0]++;
            }
        });
        parser.parse(new ByteArrayInputStream(doc.toString().getBytes(StandardCharsets.UTF_8)));
        assertEquals(500, keys[0]);
    }

    @Test
    public void parseFromAStreamReportsAKeyTooBigForTheBuffer() throws Exception {
        String doc = "{\"" + "k".repeat(100) + "\":1}";
        JSONParser parser = new JSONParser(32);
        parser.disableAllLimits();
        parser.setContentHandler(new JSONDefaultHandler());
        try {
            parser.parse(new ByteArrayInputStream(doc.getBytes(StandardCharsets.UTF_8)));
            fail("a 100 byte key cannot fit a 32 byte buffer");
        } catch (JSONException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().toLowerCase().contains("buffer"));
        }
    }

    @Test
    public void maxDocumentLengthCountsEachByteOnce() throws Exception {
        // bytes left unconsumed and presented again must not be counted twice,
        // including a byte order mark split across chunks
        for (String doc : new String[] {"{\"abcdef\":123456}", "\uFEFF{\"abcdef\":123456}"}) {
            int length = doc.getBytes(StandardCharsets.UTF_8).length;
            for (int chunk : new int[] {1, 2, 3, 5, 100}) {
                JSONParser ok = parser();
                ok.setMaxDocumentLength(length);
                feed(ok, doc, chunk);
                ok.close();

                JSONParser tooBig = parser();
                tooBig.setMaxDocumentLength(length - 1);
                try {
                    feed(tooBig, doc, chunk);
                    tooBig.close();
                    fail("chunk=" + chunk + ": the document is one byte over the limit");
                } catch (JSONException expected) {
                    // rejected
                }
            }
        }
    }

    @Test
    public void onlyShortKeysAreInterned() throws Exception {
        // The same 30 short and 50 long (200 byte) keys in every object, so
        // interning pays off and stays on. Long keys must not be kept: the
        // table lives as long as the parser, and holds up to 10,000 keys.
        JSONParser parser = new JSONParser(65536);
        parser.setContentHandler(new JSONDefaultHandler());
        StringBuilder doc = new StringBuilder("[");
        for (int object = 0; object < 100; object++) {
            doc.append(object == 0 ? "{" : ",{");
            for (int k = 0; k < 30; k++) {
                doc.append(k == 0 ? "" : ",").append("\"short").append(k).append("\":1");
            }
            for (int k = 0; k < 50; k++) {
                doc.append(",\"long-").append(k).append('-').append("x".repeat(200)).append("\":1");
            }
            doc.append("}");
        }
        doc.append("]");
        parser.parse(new ByteArrayInputStream(doc.toString().getBytes(StandardCharsets.UTF_8)));

        Field f = JSONParser.class.getDeclaredField("keySymbolTable");
        f.setAccessible(true);
        Object table = f.get(parser);
        Field sizeField = table.getClass().getDeclaredField("size");
        sizeField.setAccessible(true);
        assertEquals("only the 30 short keys are interned", 30, ((Integer) sizeField.get(table)).intValue());
    }

    private static void feed(JSONParser parser, String json, int chunk) throws JSONException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(chunk + 64);
        for (int off = 0; off < bytes.length; off += chunk) {
            buffer.put(bytes, off, Math.min(chunk, bytes.length - off));
            buffer.flip();
            parser.receive(buffer);
            buffer.compact();
        }
    }
}
