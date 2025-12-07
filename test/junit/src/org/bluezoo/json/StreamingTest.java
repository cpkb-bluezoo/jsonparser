package org.bluezoo.json;

import org.junit.Test;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Test the streaming parsing behavior with chunked data.
 * This verifies that the parser correctly handles incomplete tokens
 * across multiple receive() calls.
 */
public class StreamingTest {
    
    /**
     * Test parsing a JSON object split across multiple chunks.
     */
    @Test
    public void testChunkedObject() throws Exception {
        TestHandler handler = new TestHandler();
        JSONParser parser = new JSONParser();
        parser.setContentHandler(handler);
        
        // Send JSON in small chunks to test buffering
        String json = "{\"name\":\"Alice\",\"age\":30,\"active\":true}";
        
        // Split into small chunks
        for (int i = 0; i < json.length(); i += 3) {
            int end = Math.min(i + 3, json.length());
            String chunk = json.substring(i, end);
            parser.receive(ByteBuffer.wrap(chunk.getBytes()));
        }
        
        parser.close();
        
        // Verify events
        assertEquals("Should have received 3 keys", 3, handler.keys.size());
        assertEquals("name", handler.keys.get(0));
        assertEquals("age", handler.keys.get(1));
        assertEquals("active", handler.keys.get(2));
    }
    
    /**
     * Test parsing with a string split in the middle.
     */
    @Test
    public void testSplitString() throws Exception {
        TestHandler handler = new TestHandler();
        JSONParser parser = new JSONParser();
        parser.setContentHandler(handler);
        
        // Split in the middle of a string value
        parser.receive(ByteBuffer.wrap("{\"test\":\"hel".getBytes()));
        parser.receive(ByteBuffer.wrap("lo world\"}".getBytes()));
        parser.close();
        
        assertEquals(1, handler.strings.size());
        assertEquals("hello world", handler.strings.get(0));
    }
    
    /**
     * Test parsing with a number split across chunks.
     */
    @Test
    public void testSplitNumber() throws Exception {
        TestHandler handler = new TestHandler();
        JSONParser parser = new JSONParser();
        parser.setContentHandler(handler);
        
        // Split in the middle of a number
        parser.receive(ByteBuffer.wrap("{\"value\":123".getBytes()));
        parser.receive(ByteBuffer.wrap("45}".getBytes()));
        parser.close();
        
        assertEquals(1, handler.numbers.size());
        assertEquals(12345, handler.numbers.get(0).intValue());
    }
    
    /**
     * Test parsing with escape sequence split.
     */
    @Test
    public void testSplitEscapeSequence() throws Exception {
        TestHandler handler = new TestHandler();
        JSONParser parser = new JSONParser();
        parser.setContentHandler(handler);
        
        // Split in the middle of \n escape
        parser.receive(ByteBuffer.wrap("[\"line1\\".getBytes()));
        parser.receive(ByteBuffer.wrap("nline2\"]".getBytes()));
        parser.close();
        
        assertEquals(1, handler.strings.size());
        assertEquals("line1\nline2", handler.strings.get(0));
    }
    
    /**
     * Test parsing with unicode escape split.
     */
    @Test
    public void testSplitUnicodeEscape() throws Exception {
        TestHandler handler = new TestHandler();
        JSONParser parser = new JSONParser();
        parser.setContentHandler(handler);
        
        // Split in the middle of \u0041 (A)
        parser.receive(ByteBuffer.wrap("[\"test\\u00".getBytes()));
        parser.receive(ByteBuffer.wrap("41end\"]".getBytes()));
        parser.close();
        
        assertEquals(1, handler.strings.size());
        assertEquals("testAend", handler.strings.get(0));
    }
    
    /**
     * Test that unclosed string at EOF is detected.
     */
    @Test
    public void testUnclosedStringAtEOF() {
        JSONParser parser = new JSONParser();
        parser.setContentHandler(new TestHandler());
        
        try {
            parser.receive(ByteBuffer.wrap("[\"unclosed".getBytes()));
            parser.close();
            fail("Should have thrown exception for unclosed string");
        } catch (JSONException e) {
            assertTrue(e.getMessage().contains("Unclosed string"));
        }
    }
    
    /**
     * Test byte-by-byte parsing (extreme chunking).
     */
    @Test
    public void testByteByByteParsing() throws Exception {
        TestHandler handler = new TestHandler();
        JSONParser parser = new JSONParser();
        parser.setContentHandler(handler);
        
        String json = "{\"x\":42}";
        
        // Send one byte at a time
        for (int i = 0; i < json.length(); i++) {
            parser.receive(ByteBuffer.wrap(new byte[]{(byte) json.charAt(i)}));
        }
        
        parser.close();
        
        assertEquals(1, handler.keys.size());
        assertEquals("x", handler.keys.get(0));
        assertEquals(1, handler.numbers.size());
        assertEquals(42, handler.numbers.get(0).intValue());
    }
    
    /**
     * Simple test handler that collects events.
     */
    private static class TestHandler extends JSONDefaultHandler {
        List<String> keys = new ArrayList<>();
        List<String> strings = new ArrayList<>();
        List<Number> numbers = new ArrayList<>();
        
        @Override
        public void key(String key) {
            keys.add(key);
        }
        
        @Override
        public void stringValue(String value) {
            strings.add(value);
        }
        
        @Override
        public void numberValue(Number number) {
            numbers.add(number);
        }
    }
}

