package org.bluezoo.json;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.Random;

import static org.junit.Assert.*;

/**
 * Tests the streaming string methods of {@link JSONWriter}, the counterpart
 * of the parser's startStringValue / stringValueContent / endStringValue.
 */
public class JSONWriterStreamingTest {

    private static final String SAMPLE = "plain \"quoted\" back\\slash \n\t\u0001 café € 😀 end";

    private static String viaWriteString(String value) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        JSONWriter writer = new JSONWriter(out);
        writer.writeStartArray();
        writer.writeString(value);
        writer.writeEndArray();
        writer.close();
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String viaStreaming(String value, int... cuts) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        JSONWriter writer = new JSONWriter(out);
        writer.writeStartArray();
        writer.writeStartString();
        int from = 0;
        for (int cut : cuts) {
            writer.writeStringContent(value.substring(from, cut));
            from = cut;
        }
        writer.writeStringContent(value.substring(from));
        writer.writeEndString();
        writer.writeEndArray();
        writer.close();
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    @Test
    public void streamedStringEqualsWrittenString() throws Exception {
        assertEquals(viaWriteString(SAMPLE), viaStreaming(SAMPLE));
    }

    @Test
    public void everySingleSplitPointGivesTheSameOutput() throws Exception {
        // including a split between the two halves of the surrogate pair
        String expected = viaWriteString(SAMPLE);
        for (int cut = 0; cut <= SAMPLE.length(); cut++) {
            assertEquals("cut at " + cut, expected, viaStreaming(SAMPLE, cut));
        }
    }

    @Test
    public void oneCharacterAtATimeGivesTheSameOutput() throws Exception {
        int[] cuts = new int[SAMPLE.length() - 1];
        for (int i = 0; i < cuts.length; i++) {
            cuts[i] = i + 1;
        }
        assertEquals(viaWriteString(SAMPLE), viaStreaming(SAMPLE, cuts));
    }

    @Test
    public void emptyStringIsJustQuotes() throws Exception {
        assertEquals("[\"\"]", viaStreaming(""));
    }

    @Test
    public void loneSurrogatesAreWrittenAsWriteStringWritesThem() throws Exception {
        for (String lone : new String[] {"\ud83d", "a\ud83d", "\ud83db", "\ude00", "\ud83d😀"}) {
            String expected = viaWriteString(lone);
            for (int cut = 0; cut <= lone.length(); cut++) {
                assertEquals(lone.length() + " chars cut at " + cut, expected, viaStreaming(lone, cut));
            }
        }
    }

    @Test
    public void streamedStringIsSeparatedAndIndentedLikeAnyValue() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        JSONWriter writer = new JSONWriter(out, IndentConfig.spaces2());
        writer.writeStartObject();
        writer.writeKey("a");
        writer.writeStartString();
        writer.writeStringContent("x");
        writer.writeStringContent("y");
        writer.writeEndString();
        writer.writeKey("b");
        writer.writeString("z");
        writer.writeEndObject();
        writer.close();
        assertEquals("{\n  \"a\": \"xy\",\n  \"b\": \"z\"\n}", new String(out.toByteArray(), StandardCharsets.UTF_8));
    }

    @Test
    public void bufferDoesNotGrowWithStringLength() throws Exception {
        // 32MB through a 4KB buffer: the buffer must be sent on, not enlarged
        final long[] written = new long[1];
        WritableByteChannel sink = new WritableByteChannel() {
            @Override
            public int write(ByteBuffer src) {
                int n = src.remaining();
                written[0] += n;
                src.position(src.limit());
                return n;
            }

            @Override
            public boolean isOpen() {
                return true;
            }

            @Override
            public void close() {
            }
        };
        JSONWriter writer = new JSONWriter(sink, 4096);
        StringBuilder piece = new StringBuilder();
        for (int i = 0; i < 8000; i++) {
            piece.append("aé€\"");
        }
        writer.writeStartString();
        for (int i = 0; i < 400; i++) {
            writer.writeStringContent(piece);
        }
        writer.writeEndString();
        writer.close();

        Field f = JSONWriter.class.getDeclaredField("buffer");
        f.setAccessible(true);
        assertEquals(4096, ((ByteBuffer) f.get(writer)).capacity());
        assertTrue("all of it was written", written[0] > 400L * 8000 * 4);
    }

    @Test
    public void parserCanFeedWriterWithoutBufferingEitherSide() throws Exception {
        // Parse a document and write it straight back out, string by string
        StringBuilder doc = new StringBuilder("{\"k\":[\"");
        Random rnd = new Random(7);
        String alphabet = "ab\"\\\né€😀 ";
        StringBuilder expectedText = new StringBuilder();
        for (int i = 0; i < 3000; i++) {
            int a = rnd.nextInt(alphabet.length());
            char c = alphabet.charAt(a);
            if (Character.isHighSurrogate(c)) {
                expectedText.append(c).append(alphabet.charAt(a + 1));
            } else if (!Character.isLowSurrogate(c)) {
                expectedText.append(c);
            }
        }
        String text = expectedText.toString();
        String escaped = viaWriteString(text); // ["..."] with JSON escaping
        doc.append(escaped, 2, escaped.length() - 2).append("\",1]}");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        final JSONWriter[] current = new JSONWriter[1]; // a writer is single-use: one per document
        JSONParser parser = new JSONParser();
        parser.setContentHandler(new JSONDefaultHandler() {
            @Override
            public void startObject() throws JSONException {
                io(() -> current[0].writeStartObject());
            }

            @Override
            public void endObject() throws JSONException {
                io(() -> current[0].writeEndObject());
            }

            @Override
            public void startArray() throws JSONException {
                io(() -> current[0].writeStartArray());
            }

            @Override
            public void endArray() throws JSONException {
                io(() -> current[0].writeEndArray());
            }

            @Override
            public void key(String key) throws JSONException {
                io(() -> current[0].writeKey(key));
            }

            @Override
            public void numberValue(Number number) throws JSONException {
                io(() -> current[0].writeNumber(number));
            }

            @Override
            public void startStringValue() throws JSONException {
                io(() -> current[0].writeStartString());
            }

            @Override
            public void stringValueContent(CharSequence content) throws JSONException {
                io(() -> current[0].writeStringContent(content));
            }

            @Override
            public void endStringValue() throws JSONException {
                io(() -> current[0].writeEndString());
            }
        });

        byte[] bytes = doc.toString().getBytes(StandardCharsets.UTF_8);
        for (int chunk : new int[] {1, 5, 64}) {
            out.reset();
            current[0] = new JSONWriter(out);
            parser.reset();
            ByteBuffer buffer = ByteBuffer.allocate(chunk + 256);
            for (int off = 0; off < bytes.length; off += chunk) {
                buffer.put(bytes, off, Math.min(chunk, bytes.length - off));
                buffer.flip();
                parser.receive(buffer);
                buffer.compact();
            }
            parser.close();
            current[0].close();
            assertEquals("chunk=" + chunk, doc.toString(), new String(out.toByteArray(), StandardCharsets.UTF_8));
        }
    }

    private interface IO {
        void run() throws java.io.IOException;
    }

    private static void io(IO action) throws JSONException {
        try {
            action.run();
        } catch (java.io.IOException e) {
            throw new JSONException("write failed", e);
        }
    }
}
