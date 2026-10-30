package org.bluezoo.json;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

/**
 * A Java string can hold an unpaired surrogate, which has no UTF-8 encoding.
 * The writer must not emit the invalid bytes that would result: it writes the
 * surrogate as a {@code \\uXXXX} escape, which is valid JSON and which the
 * parser reads back as the same character. A proper pair is still written as
 * the one UTF-8 character it stands for.
 */
public class JSONWriterSurrogateTest {

    private static final String EMOJI = "\ud83d\ude00";

    private static byte[] writeString(String value) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        JSONWriter writer = new JSONWriter(out);
        writer.writeStartArray();
        writer.writeString(value);
        writer.writeEndArray();
        writer.close();
        return out.toByteArray();
    }

    private static byte[] writeKey(String key) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        JSONWriter writer = new JSONWriter(out);
        writer.writeStartObject();
        writer.writeKey(key);
        writer.writeNumber(1);
        writer.writeEndObject();
        writer.close();
        return out.toByteArray();
    }

    private static byte[] streamString(String value, int... cuts) throws Exception {
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
        return out.toByteArray();
    }

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** Reads the first string value, or the first key, of the document back with the parser. */
    private static String readBack(byte[] json, final boolean key) throws Exception {
        final StringBuilder got = new StringBuilder();
        JSONParser parser = new JSONParser();
        parser.setContentHandler(new JSONDefaultHandler() {
            @Override
            public void stringValueContent(CharSequence content) {
                if (!key) {
                    got.append(content);
                }
            }

            @Override
            public void key(String k) {
                if (key) {
                    got.append(k);
                }
            }
        });
        parser.receive(ByteBuffer.wrap(json));
        parser.close();
        return got.toString();
    }

    /** value, then the JSON text expected between the quotes. */
    private static final String[][] CASES = {
        {"\ud83d", "\\ud83d"},
        {"\ude00", "\\ude00"},
        {"x\ud83dy", "x\\ud83dy"},
        {"\ude00\ud83d", "\\ude00\\ud83d"},                  // low then high: neither is part of a pair
        {"\ud83d\ud83d\ude00", "\\ud83d" + EMOJI},            // a lone high, then a proper pair
        {"\ud83d\ude00\ude00", EMOJI + "\\ude00"},            // a proper pair, then a lone low
        {"\ud83d\ude00", EMOJI},                              // a proper pair stays one UTF-8 character
        {"\ud7ff\ue000", "\ud7ff\ue000"},                     // just outside the surrogate range: not escaped
        // the very edges of the range, alone
        {"\ud800", "\\ud800"},
        {"\udbff", "\\udbff"},
        {"\udc00", "\\udc00"},
        {"\udfff", "\\udfff"},
        // and the first and last proper pairs, which are still one UTF-8 character
        {"\ud800\udc00", "\ud800\udc00"},
        {"\udbff\udfff", "\udbff\udfff"},
    };

    @Test
    public void loneSurrogatesAreWrittenAsEscapes() throws Exception {
        for (String[] c : CASES) {
            assertEquals("value " + c[0].length() + " chars", "[\"" + c[1] + "\"]", text(writeString(c[0])));
        }
    }

    @Test
    public void loneSurrogatesInAKeyAreWrittenAsEscapes() throws Exception {
        for (String[] c : CASES) {
            assertEquals("{\"" + c[1] + "\":1}", text(writeKey(c[0])));
        }
    }

    @Test
    public void streamedContentIsEscapedTheSameWayAtEverySplit() throws Exception {
        for (String[] c : CASES) {
            String expected = "[\"" + c[1] + "\"]";
            for (int cut = 0; cut <= c[0].length(); cut++) {
                assertEquals("cut at " + cut, expected, text(streamString(c[0], cut)));
            }
            int[] everyChar = new int[Math.max(0, c[0].length() - 1)];
            for (int i = 0; i < everyChar.length; i++) {
                everyChar[i] = i + 1;
            }
            assertEquals("one char at a time", expected, text(streamString(c[0], everyChar)));
        }
    }

    @Test
    public void theOutputIsAlwaysValidUtf8() throws Exception {
        for (String[] c : CASES) {
            for (byte[] json : new byte[][] {writeString(c[0]), writeKey(c[0]), streamString(c[0], 1)}) {
                // a strict decode: malformed input is an error rather than a replacement character
                StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(json));
            }
        }
    }

    @Test
    public void whatIsWrittenIsReadBackAsTheSameString() throws Exception {
        for (String[] c : CASES) {
            assertEquals(c[0], readBack(writeString(c[0]), false));
            assertEquals(c[0], readBack(writeKey(c[0]), true));
            assertEquals(c[0], readBack(streamString(c[0], 1), false));
        }
    }
}
