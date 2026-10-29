package org.bluezoo.json;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Tests the line/column positions reported through {@link JSONLocator},
 * independent of whether columns start at 0 or 1: only line numbers and
 * column differences between events are asserted. Each case is checked
 * whole and in small chunks, with and without whitespace events, since
 * those take different paths through the tokenizer.
 */
public class LocatorTest {

    private static final int WHOLE = 0;
    private static final int[] CHUNKS = {WHOLE, 1, 2, 3, 5};

    /** Line and column recorded at each numberValue event. */
    private static List<int[]> numberPositions(String json, int chunk, boolean wantWhitespace) throws Exception {
        List<int[]> positions = new ArrayList<>();
        JSONParser parser = new JSONParser();
        parser.setContentHandler(new JSONDefaultHandler() {
            private JSONLocator locator;

            @Override
            public void setLocator(JSONLocator locator) {
                this.locator = locator;
            }

            @Override
            public boolean needsWhitespace() {
                return wantWhitespace;
            }

            @Override
            public void numberValue(Number number) {
                positions.add(new int[] {locator.getLineNumber(), locator.getColumnNumber()});
            }
        });

        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        int size = chunk == WHOLE ? bytes.length : chunk;
        ByteBuffer buffer = ByteBuffer.allocate(size + 256);
        for (int off = 0; off < bytes.length; off += size) {
            buffer.put(bytes, off, Math.min(size, bytes.length - off));
            buffer.flip();
            parser.receive(buffer);
            buffer.compact();
        }
        parser.close();
        return positions;
    }

    private interface Check {
        void verify(List<int[]> positions, String label);
    }

    private static void forEveryMode(String json, Check check) throws Exception {
        for (int chunk : CHUNKS) {
            for (boolean ws : new boolean[] {false, true}) {
                String label = "chunk=" + chunk + " whitespaceEvents=" + ws;
                check.verify(numberPositions(json, chunk, ws), label);
            }
        }
    }

    @Test
    public void spacesAndTabsAdvanceTheColumn() throws Exception {
        // "1" is 1 char, then space, comma, tab: the next number is 4 columns on
        forEveryMode("[1 ,\t2]", (p, label) ->
                assertEquals(label, 4, p.get(1)[1] - p.get(0)[1]));
    }

    @Test
    public void everyCharOfAWhitespaceRunAdvancesTheColumn() throws Exception {
        forEveryMode("[1,    2]", (p, label) ->
                assertEquals(label, 6, p.get(1)[1] - p.get(0)[1]));
    }

    @Test
    public void crlfIsOneLineBreak() throws Exception {
        forEveryMode("[1,\r\n2,\r\n3]", (p, label) -> {
            assertEquals(label, 1, p.get(0)[0]);
            assertEquals(label, 2, p.get(1)[0]);
            assertEquals(label, 3, p.get(2)[0]);
        });
    }

    @Test
    public void loneCrIsOneLineBreak() throws Exception {
        forEveryMode("[1,\r2,\r3]", (p, label) -> {
            assertEquals(label, 2, p.get(1)[0]);
            assertEquals(label, 3, p.get(2)[0]);
        });
    }

    @Test
    public void loneLfIsOneLineBreak() throws Exception {
        forEveryMode("[1,\n2,\n3]", (p, label) -> {
            assertEquals(label, 2, p.get(1)[0]);
            assertEquals(label, 3, p.get(2)[0]);
        });
    }

    @Test
    public void crFollowedByLfAcrossAnyNumberOfBlankLines() throws Exception {
        // CRLF, then a lone LF, then a lone CR: three breaks, not four or five
        forEveryMode("[1,\r\n\n\r2]", (p, label) ->
                assertEquals(label, 4, p.get(1)[0]));
    }

    @Test
    public void stringContentAdvancesTheColumnBySourceCharacters() throws Exception {
        // Distance from the 1 to the 2 is: "1" "," <string> ","
        // plain: "abc" is 5 source characters
        forEveryMode("[1,\"abc\",2]", (p, label) ->
                assertEquals(label, 1 + 1 + 5 + 1, p.get(1)[1] - p.get(0)[1]));
        // multi-byte UTF-8: e-acute, euro sign and an emoji are 1 + 1 + 2 UTF-16 units, plus quotes
        forEveryMode("[1,\"\u00e9\u20ac\ud83d\ude00\",2]", (p, label) ->
                assertEquals(label, 1 + 1 + 6 + 1, p.get(1)[1] - p.get(0)[1]));
        // escapes count as the raw characters written: \n is 2, \u00e9 is 6, a pair is 12
        forEveryMode("[1,\"\\n\\u00e9\\ud83d\\ude00\",2]", (p, label) ->
                assertEquals(label, 1 + 1 + (1 + 2 + 6 + 12 + 1) + 1, p.get(1)[1] - p.get(0)[1]));
        // an empty string is just its quotes
        forEveryMode("[1,\"\",2]", (p, label) ->
                assertEquals(label, 1 + 1 + 2 + 1, p.get(1)[1] - p.get(0)[1]));
    }

    @Test
    public void columnRestartsAfterALineBreak() throws Exception {
        // Indented by 3 on line 2, not indented on line 3: 3 columns apart
        forEveryMode("[\n   1,\n2]", (p, label) -> {
            assertEquals(label, 2, p.get(0)[0]);
            assertEquals(label, 3, p.get(1)[0]);
            assertEquals(label, 3, p.get(0)[1] - p.get(1)[1]);
        });
    }
}
