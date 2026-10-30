package org.bluezoo.json;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Tests the absolute positions reported by {@link JSONLocator}, which follow
 * the rule for SAX's {@code org.xml.sax.Locator}: line and column numbers
 * start at 1, and the position is where the event ends, the first character
 * after the text associated with it. A column counts Java {@code char}
 * values (UTF-16 units) since the last line end.
 */
public class LocatorPositionTest {

    /** Records every event with the locator position reported for it, as {@code name@line:column}. */
    private static final class Recorder extends JSONDefaultHandler {
        final List<String> events = new ArrayList<>();
        private JSONLocator locator;

        @Override
        public void setLocator(JSONLocator locator) {
            this.locator = locator;
        }

        @Override
        public boolean needsWhitespace() {
            return true;
        }

        private void add(String name) {
            events.add(name + "@" + locator.getLineNumber() + ":" + locator.getColumnNumber());
        }

        @Override
        public void startObject() {
            add("{");
        }

        @Override
        public void endObject() {
            add("}");
        }

        @Override
        public void startArray() {
            add("[");
        }

        @Override
        public void endArray() {
            add("]");
        }

        @Override
        public void key(String key) {
            add("key");
        }

        @Override
        public void startStringValue() {
            add("start");
        }

        @Override
        public void stringValueContent(CharSequence content) {
            add("content");
        }

        @Override
        public void endStringValue() {
            add("end");
        }

        @Override
        public void numberValue(Number number) {
            add("number");
        }

        @Override
        public void booleanValue(boolean value) {
            add("boolean");
        }

        @Override
        public void nullValue() {
            add("null");
        }

        @Override
        public void whitespace(String whitespace) {
            add("ws");
        }
    }

    private static List<String> positions(String json, int chunk) throws JSONException {
        Recorder handler = new Recorder();
        JSONParser parser = new JSONParser();
        parser.setContentHandler(handler);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        int size = chunk == 0 ? bytes.length : chunk;
        ByteBuffer buffer = ByteBuffer.allocate(size + 64);
        for (int off = 0; off < bytes.length; off += size) {
            buffer.put(bytes, off, Math.min(size, bytes.length - off));
            buffer.flip();
            parser.receive(buffer);
            buffer.compact();
        }
        parser.close();
        return handler.events;
    }

    private static void assertPositions(String json, String... expected) throws JSONException {
        assertEquals(json, Arrays.asList(expected), positions(json, 0));
    }

    @Test
    public void structuralTokensEndAfterThemselves() throws Exception {
        // [=1 1=2 ]=3
        assertPositions("[1]", "[@1:2", "number@1:3", "]@1:4");
        assertPositions("[]", "[@1:2", "]@1:3");
        // {=1 "=2 k=3 "=4 :=5 true=6-9 }=10
        assertPositions("{\"k\":true}", "{@1:2", "key@1:5", "boolean@1:10", "}@1:11");
    }

    @Test
    public void aKeyEndsAfterItsClosingQuoteNotAfterTheColon() throws Exception {
        assertPositions("{\"abc\" : 1}", "{@1:2", "key@1:7", "ws@1:8", "ws@1:10", "number@1:11", "}@1:12");
    }

    @Test
    public void literalsEndAfterTheirLastCharacter() throws Exception {
        assertPositions("[null]", "[@1:2", "null@1:6", "]@1:7");
        assertPositions("[false]", "[@1:2", "boolean@1:7", "]@1:8");
    }

    @Test
    public void aStringValueIsReportedAsItsQuotesAndContentEnd() throws Exception {
        // [=1 "=2 a=3 b=4 "=5 ]=6
        assertPositions("[\"ab\"]", "[@1:2", "start@1:3", "content@1:5", "end@1:6", "]@1:7");
        assertPositions("\"hi\"", "start@1:2", "content@1:4", "end@1:5");
        assertPositions("[\"\"]", "[@1:2", "start@1:3", "end@1:4", "]@1:5");
    }

    @Test
    public void numbersEndAfterTheirLastDigit() throws Exception {
        // 1.5e10 is 6 characters, in columns 2 to 7
        assertPositions("[1.5e10]", "[@1:2", "number@1:8", "]@1:9");
        assertPositions("[-12 ]", "[@1:2", "number@1:5", "ws@1:6", "]@1:7");
    }

    @Test
    public void aNumberThatEndsTheDocumentIsReportedByCloseAtItsEnd() throws Exception {
        assertPositions("42", "number@1:3");
    }

    @Test
    public void columnsCountUtf16UnitsNotCharactersOrBytes() throws Exception {
        // [=1 "=2 e-acute=3 euro=4 emoji=5-6 "=7 ]=8
        assertPositions("[\"é€😀\"]", "[@1:2", "start@1:3", "content@1:7", "end@1:8", "]@1:9");
        // a multi-byte key: [{=1 "=2 e-acute=3 euro=4 "=5, so it ends at 6
        assertPositions("{\"é€\":1}", "{@1:2", "key@1:6", "number@1:8", "}@1:9");
    }

    @Test
    public void anEscapeCountsAsTheCharactersWrittenInTheSource() throws Exception {
        // [=1 "=2 \n=3-4 é=5-10 "=11 ]=12
        assertPositions("[\"\\n\\u00e9\"]", "[@1:2", "start@1:3", "content@1:11", "end@1:12", "]@1:13");
    }

    @Test
    public void whitespaceEndsAfterItsLastCharacterAndNewlinesStartANewLine() throws Exception {
        // line 1: [=1 space=2 1=3 ,=4 newline=5; line 2: space space 2=3 space=4 ]=5
        assertPositions("[ 1,\n  2 ]",
                "[@1:2", "ws@1:3", "number@1:4", "ws@2:3", "number@2:4", "ws@2:5", "]@2:6");
        assertPositions("\n\n[]", "ws@3:1", "[@3:2", "]@3:3");
    }

    @Test
    public void crlfIsOneLineBreak() throws Exception {
        assertPositions("[1,\r\n2]", "[@1:2", "number@1:3", "ws@2:1", "number@2:2", "]@2:3");
        assertPositions("[1,\r2]", "[@1:2", "number@1:3", "ws@2:1", "number@2:2", "]@2:3");
        assertPositions("[1,\n2]", "[@1:2", "number@1:3", "ws@2:1", "number@2:2", "]@2:3");
    }

    @Test
    public void positionsDoNotDependOnHowTheInputIsChunked() throws Exception {
        String[] documents = {
            "{\"k\":[1,2.5e3,true,null,\"str\"],\"é\":{\"x\":\"\\u00e9\\n\"}}",
            "[ 1,\r\n  \"a\\u0041\" ,\n\t{ \"k\" : -0.5 } ]",
            "[\"😀😀\",12345]"
        };
        for (String doc : documents) {
            List<String> whole = withoutPieces(positions(doc, 0));
            for (int chunk = 1; chunk <= 9; chunk++) {
                assertEquals(doc + " chunk=" + chunk, whole, withoutPieces(positions(doc, chunk)));
            }
        }
    }

    @Test
    public void aStringContentPieceEndsAfterItsOwnText() throws Exception {
        // one character at a time: each piece is reported at the end of that character
        assertEquals(Arrays.asList("[@1:2", "start@1:3", "content@1:4", "content@1:5", "end@1:6", "]@1:7"),
                positions("[\"ab\"]", 1));
    }

    /** Content and whitespace may arrive in several pieces; the rest of the events may not differ. */
    private static List<String> withoutPieces(List<String> events) {
        List<String> kept = new ArrayList<>();
        for (String e : events) {
            if (!e.startsWith("content@") && !e.startsWith("ws@")) {
                kept.add(e);
            }
        }
        return kept;
    }
}
