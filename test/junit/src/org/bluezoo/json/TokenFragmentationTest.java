package org.bluezoo.json;

import org.junit.Test;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Tests token fragmentation across receive() boundaries.
 * This is critical for streaming parsers to handle incomplete tokens correctly.
 */
public class TokenFragmentationTest {

    /**
     * Helper class to capture parse events
     */
    private static class CaptureHandler extends JSONDefaultHandler {
        List<String> events = new ArrayList<>();
        
        @Override
        public void startObject() {
            events.add("START_OBJECT");
        }
        
        @Override
        public void endObject() {
            events.add("END_OBJECT");
        }
        
        @Override
        public void startArray() {
            events.add("START_ARRAY");
        }
        
        @Override
        public void endArray() {
            events.add("END_ARRAY");
        }
        
        @Override
        public void key(String key) {
            events.add("KEY:" + key);
        }
        
        @Override
        public void stringValue(String value) {
            events.add("STRING:" + value);
        }
        
        @Override
        public void numberValue(Number value) {
            events.add("NUMBER:" + value);
        }
        
        @Override
        public void booleanValue(boolean value) {
            events.add("BOOLEAN:" + value);
        }
        
        @Override
        public void nullValue() {
            events.add("NULL");
        }
    }
    
    /**
     * Helper to send JSON in fragments of specified size
     */
    private CaptureHandler parseFragmented(String json, int fragmentSize) throws Exception {
        CaptureHandler handler = new CaptureHandler();
        JSONParser parser = new JSONParser();
        parser.setContentHandler(handler);
        
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        int offset = 0;
        
        while (offset < bytes.length) {
            int len = Math.min(fragmentSize, bytes.length - offset);
            ByteBuffer chunk = ByteBuffer.wrap(bytes, offset, len);
            parser.receive(chunk);
            offset += len;
        }
        
        parser.close();
        return handler;
    }
    
    /**
     * Helper to send JSON byte-by-byte
     */
    private CaptureHandler parseByteByByte(String json) throws Exception {
        return parseFragmented(json, 1);
    }

    // ===== Number Fragmentation =====

    @Test
    public void testNumberFragmentedInInteger() throws Exception {
        // Split "12345" as "12" + "345"
        CaptureHandler handler = parseFragmented("[12345]", 3);
        assertTrue(handler.events.contains("NUMBER:12345"));
    }

    @Test
    public void testNumberFragmentedAtDecimal() throws Exception {
        // Split "123.456" at various points
        CaptureHandler handler1 = parseFragmented("[123.456]", 4); // "123" + ".456]"
        assertTrue(handler1.events.contains("NUMBER:123.456"));
        
        CaptureHandler handler2 = parseFragmented("[123.456]", 5); // "123." + "456]"
        assertTrue(handler2.events.contains("NUMBER:123.456"));
    }

    @Test
    public void testNumberFragmentedInFraction() throws Exception {
        // Split "123.456789" in the fractional part
        CaptureHandler handler = parseFragmented("[123.456789]", 6); // "123.45" + "6789]"
        assertTrue(handler.events.contains("NUMBER:123.456789"));
    }

    @Test
    public void testNumberFragmentedAtExponent() throws Exception {
        // Split "123e45" at various points
        CaptureHandler handler1 = parseFragmented("[123e45]", 4); // "123" + "e45]"
        assertTrue(handler1.events.contains("NUMBER:1.23E47"));
        
        CaptureHandler handler2 = parseFragmented("[123e45]", 5); // "123e" + "45]"
        assertTrue(handler2.events.contains("NUMBER:1.23E47"));
    }

    @Test
    public void testNumberFragmentedAtExponentSign() throws Exception {
        // Split "123e+45" around the sign
        CaptureHandler handler1 = parseFragmented("[123e+45]", 5); // "123e" + "+45]"
        assertTrue(handler1.events.contains("NUMBER:1.23E47"));
        
        CaptureHandler handler2 = parseFragmented("[123e+45]", 6); // "123e+" + "45]"
        assertTrue(handler2.events.contains("NUMBER:1.23E47"));
    }

    @Test
    public void testNumberFragmentedInExponent() throws Exception {
        // Split "123e456" in the exponent part
        CaptureHandler handler = parseFragmented("[123e456]", 6); // "123e4" + "56]"
        // Large exponents may be represented in scientific notation differently
        String event = handler.events.stream()
            .filter(e -> e.startsWith("NUMBER:"))
            .findFirst()
            .orElse(null);
        assertNotNull("Should have parsed a number", event);
        // The number should contain 123 with a large exponent
        assertTrue("Expected large exponent, got: " + event, event.contains("E"));
    }

    @Test
    public void testNumberWithDecimalAndExponentFragmented() throws Exception {
        // Split "123.456e789"
        CaptureHandler handler = parseFragmented("[123.456e789]", 5); // "123." + "456e789]"
        String event = handler.events.stream()
            .filter(e -> e.startsWith("NUMBER:"))
            .findFirst()
            .orElse(null);
        assertNotNull(event);
        assertTrue(event.contains("E"));
    }

    @Test
    public void testNegativeNumberFragmented() throws Exception {
        // Split "-123.456"
        CaptureHandler handler1 = parseFragmented("[-123.456]", 2); // "-" + "123.456]"
        assertTrue(handler1.events.contains("NUMBER:-123.456"));
        
        CaptureHandler handler2 = parseFragmented("[-123.456]", 3); // "-1" + "23.456]"
        assertTrue(handler2.events.contains("NUMBER:-123.456"));
    }

    @Test
    public void testNumberByteByByte() throws Exception {
        CaptureHandler handler = parseByteByByte("[123.456e-78]");
        String event = handler.events.stream()
            .filter(e -> e.startsWith("NUMBER:"))
            .findFirst()
            .orElse(null);
        assertNotNull(event);
    }

    // ===== String Fragmentation =====

    @Test
    public void testStringFragmentedInMiddle() throws Exception {
        // Split "Hello World" in the middle
        CaptureHandler handler = parseFragmented("[\"Hello World\"]", 8); // "[\"Hello" + " World\"]"
        assertTrue(handler.events.contains("STRING:Hello World"));
    }

    @Test
    public void testStringFragmentedAtQuote() throws Exception {
        // Split at opening quote
        CaptureHandler handler1 = parseFragmented("[\"test\"]", 2); // "[\"" + "test\"]"
        assertTrue(handler1.events.contains("STRING:test"));
        
        // Split at closing quote
        CaptureHandler handler2 = parseFragmented("[\"test\"]", 7); // "[\"test" + "\"]"
        assertTrue(handler2.events.contains("STRING:test"));
    }

    @Test
    public void testStringFragmentedAtEscapeSequence() throws Exception {
        // Split at backslash
        CaptureHandler handler1 = parseFragmented("[\"Hello\\nWorld\"]", 8); // "[\"Hello\\" + "nWorld\"]"
        assertTrue(handler1.events.contains("STRING:Hello\nWorld"));
        
        // Split after backslash
        CaptureHandler handler2 = parseFragmented("[\"Hello\\nWorld\"]", 9); // "[\"Hello\\n" + "World\"]"
        assertTrue(handler2.events.contains("STRING:Hello\nWorld"));
    }

    @Test
    public void testStringFragmentedInUnicodeEscape() throws Exception {
        // Split "\\u0041" (which is 'A')
        CaptureHandler handler1 = parseFragmented("[\"\\u0041\"]", 3); // "[\"" + "\\u0041\"]"
        assertTrue(handler1.events.contains("STRING:A"));
        
        CaptureHandler handler2 = parseFragmented("[\"\\u0041\"]", 4); // "[\"\\u" + "0041\"]"
        assertTrue(handler2.events.contains("STRING:A"));
        
        CaptureHandler handler3 = parseFragmented("[\"\\u0041\"]", 5); // "[\"\\u0" + "041\"]"
        assertTrue(handler3.events.contains("STRING:A"));
        
        CaptureHandler handler4 = parseFragmented("[\"\\u0041\"]", 6); // "[\"\\u00" + "41\"]"
        assertTrue(handler4.events.contains("STRING:A"));
        
        CaptureHandler handler5 = parseFragmented("[\"\\u0041\"]", 7); // "[\"\\u004" + "1\"]"
        assertTrue(handler5.events.contains("STRING:A"));
    }

    @Test
    public void testStringFragmentedInSurrogatePair() throws Exception {
        // Split surrogate pair \\uD83D\\uDE00 (😀)
        String json = "[\"\\uD83D\\uDE00\"]";
        
        // Split between the two escapes
        CaptureHandler handler1 = parseFragmented(json, 9); // "[\"\\uD83D" + "\\uDE00\"]"
        assertTrue(handler1.events.stream().anyMatch(e -> e.contains("😀")));
        
        // Split in the middle of second escape
        CaptureHandler handler2 = parseFragmented(json, 11); // "[\"\\uD83D\\u" + "DE00\"]"
        assertTrue(handler2.events.stream().anyMatch(e -> e.contains("😀")));
    }

    @Test
    public void testStringByteByByte() throws Exception {
        CaptureHandler handler = parseByteByByte("[\"Hello\\nWorld\\u0041\"]");
        assertTrue(handler.events.contains("STRING:Hello\nWorldA"));
    }

    @Test
    public void testEmptyStringFragmented() throws Exception {
        CaptureHandler handler = parseFragmented("[\"\"]", 2); // "[\"" + "\"]"
        assertTrue(handler.events.contains("STRING:"));
    }

    // ===== Literal Fragmentation =====

    @Test
    public void testTrueFragmented() throws Exception {
        // Split "true"
        CaptureHandler handler1 = parseFragmented("[true]", 2); // "[t" + "rue]"
        assertTrue(handler1.events.contains("BOOLEAN:true"));
        
        CaptureHandler handler2 = parseFragmented("[true]", 3); // "[tr" + "ue]"
        assertTrue(handler2.events.contains("BOOLEAN:true"));
        
        CaptureHandler handler3 = parseFragmented("[true]", 4); // "[tru" + "e]"
        assertTrue(handler3.events.contains("BOOLEAN:true"));
    }

    @Test
    public void testFalseFragmented() throws Exception {
        // Split "false"
        CaptureHandler handler1 = parseFragmented("[false]", 2); // "[f" + "alse]"
        assertTrue(handler1.events.contains("BOOLEAN:false"));
        
        CaptureHandler handler2 = parseFragmented("[false]", 4); // "[fal" + "se]"
        assertTrue(handler2.events.contains("BOOLEAN:false"));
    }

    @Test
    public void testNullFragmented() throws Exception {
        // Split "null"
        CaptureHandler handler1 = parseFragmented("[null]", 2); // "[n" + "ull]"
        assertTrue(handler1.events.contains("NULL"));
        
        CaptureHandler handler2 = parseFragmented("[null]", 3); // "[nu" + "ll]"
        assertTrue(handler2.events.contains("NULL"));
        
        CaptureHandler handler3 = parseFragmented("[null]", 4); // "[nul" + "l]"
        assertTrue(handler3.events.contains("NULL"));
    }

    @Test
    public void testLiteralsByteByByte() throws Exception {
        CaptureHandler handler = parseByteByByte("[true,false,null]");
        assertTrue(handler.events.contains("BOOLEAN:true"));
        assertTrue(handler.events.contains("BOOLEAN:false"));
        assertTrue(handler.events.contains("NULL"));
    }

    // ===== Object and Array Fragmentation =====

    @Test
    public void testObjectFragmented() throws Exception {
        String json = "{\"key\":\"value\"}";
        
        // Split at key
        CaptureHandler handler1 = parseFragmented(json, 5); // "{\"ke" + "y\":\"value\"}"
        assertTrue(handler1.events.contains("KEY:key"));
        assertTrue(handler1.events.contains("STRING:value"));
        
        // Split at colon
        CaptureHandler handler2 = parseFragmented(json, 7); // "{\"key\"" + ":\"value\"}"
        assertTrue(handler2.events.contains("KEY:key"));
        assertTrue(handler2.events.contains("STRING:value"));
        
        // Split at value
        CaptureHandler handler3 = parseFragmented(json, 12); // "{\"key\":\"val" + "ue\"}"
        assertTrue(handler3.events.contains("KEY:key"));
        assertTrue(handler3.events.contains("STRING:value"));
    }

    @Test
    public void testNestedStructuresFragmented() throws Exception {
        String json = "{\"arr\":[1,2,3],\"obj\":{\"x\":true}}";
        
        CaptureHandler handler = parseFragmented(json, 5);
        
        assertTrue(handler.events.contains("START_OBJECT"));
        assertTrue(handler.events.contains("KEY:arr"));
        assertTrue(handler.events.contains("START_ARRAY"));
        assertTrue(handler.events.contains("NUMBER:1"));
        assertTrue(handler.events.contains("NUMBER:2"));
        assertTrue(handler.events.contains("NUMBER:3"));
        assertTrue(handler.events.contains("END_ARRAY"));
        assertTrue(handler.events.contains("KEY:obj"));
        assertTrue(handler.events.contains("KEY:x"));
        assertTrue(handler.events.contains("BOOLEAN:true"));
        assertTrue(handler.events.contains("END_OBJECT"));
    }

    @Test
    public void testComplexDocumentByteByByte() throws Exception {
        String json = "{\"name\":\"Alice\",\"age\":30,\"active\":true,\"score\":98.6,\"tags\":[\"a\",\"b\"]}";
        
        CaptureHandler handler = parseByteByByte(json);
        
        assertTrue(handler.events.contains("KEY:name"));
        assertTrue(handler.events.contains("STRING:Alice"));
        assertTrue(handler.events.contains("KEY:age"));
        assertTrue(handler.events.contains("NUMBER:30"));
        assertTrue(handler.events.contains("KEY:active"));
        assertTrue(handler.events.contains("BOOLEAN:true"));
        assertTrue(handler.events.contains("KEY:score"));
        assertTrue(handler.events.contains("NUMBER:98.6"));
        assertTrue(handler.events.contains("KEY:tags"));
        assertTrue(handler.events.contains("STRING:a"));
        assertTrue(handler.events.contains("STRING:b"));
    }

    // ===== Whitespace Fragmentation =====

    @Test
    public void testWhitespaceFragmented() throws Exception {
        // Split whitespace between tokens
        String json = "[  1  ,  2  ]";
        
        CaptureHandler handler = parseFragmented(json, 3); // "[  " + "1  ,  2  ]"
        assertTrue(handler.events.contains("NUMBER:1"));
        assertTrue(handler.events.contains("NUMBER:2"));
    }

    @Test
    public void testNewlinesFragmented() throws Exception {
        String json = "[\n1\n,\n2\n]";
        
        CaptureHandler handler = parseFragmented(json, 2);
        assertTrue(handler.events.contains("NUMBER:1"));
        assertTrue(handler.events.contains("NUMBER:2"));
    }

    // ===== Mixed Token Types =====

    @Test
    public void testAllTokenTypesFragmented() throws Exception {
        String json = "[123,\"test\",true,false,null,{\"key\":\"value\"},[1,2]]";
        
        CaptureHandler handler = parseFragmented(json, 5);
        
        assertTrue(handler.events.contains("NUMBER:123"));
        assertTrue(handler.events.contains("STRING:test"));
        assertTrue(handler.events.contains("BOOLEAN:true"));
        assertTrue(handler.events.contains("BOOLEAN:false"));
        assertTrue(handler.events.contains("NULL"));
        assertTrue(handler.events.contains("KEY:key"));
        assertTrue(handler.events.contains("STRING:value"));
        assertTrue(handler.events.contains("NUMBER:1"));
        assertTrue(handler.events.contains("NUMBER:2"));
    }

    @Test
    public void testVeryLargeFragmentation() throws Exception {
        // Build a large JSON with many numbers
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 100; i++) {
            if (i > 0) sb.append(",");
            sb.append(i);
        }
        sb.append("]");
        
        // Parse with small fragments
        CaptureHandler handler = parseFragmented(sb.toString(), 7);
        
        assertEquals(102, handler.events.size()); // START_ARRAY + 100 numbers + END_ARRAY
    }

    // ===== Edge Cases =====

    @Test
    public void testSingleCharacterFragments() throws Exception {
        // Every single character is a separate fragment
        CaptureHandler handler = parseByteByByte("[1,\"a\",true]");
        
        assertTrue(handler.events.contains("NUMBER:1"));
        assertTrue(handler.events.contains("STRING:a"));
        assertTrue(handler.events.contains("BOOLEAN:true"));
    }

    @Test
    public void testFragmentBoundaryAtStructuralToken() throws Exception {
        // Split right at structural tokens
        String json = "{\"a\":1,\"b\":2}";
        
        CaptureHandler handler = parseFragmented(json, 1); // Every character separate
        
        assertTrue(handler.events.contains("KEY:a"));
        assertTrue(handler.events.contains("NUMBER:1"));
        assertTrue(handler.events.contains("KEY:b"));
        assertTrue(handler.events.contains("NUMBER:2"));
    }

    @Test
    public void testFragmentedLonelyValue() throws Exception {
        // A single value at root level, fragmented
        CaptureHandler handler = parseFragmented("123456", 3); // "123" + "456"
        assertTrue(handler.events.contains("NUMBER:123456"));
    }

    @Test
    public void testMultiByteUTF8Fragmented() throws Exception {
        // Fragment in the middle of multi-byte UTF-8 character
        String json = "[\"你好世界\"]"; // Chinese characters (3 bytes each in UTF-8)
        
        // Try various fragment sizes that might split UTF-8 sequences
        for (int size = 1; size <= json.length(); size++) {
            CaptureHandler handler = parseFragmented(json, size);
            assertTrue("Failed with fragment size " + size, 
                handler.events.contains("STRING:你好世界"));
        }
    }
}

