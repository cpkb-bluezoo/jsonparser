package org.bluezoo.json;

import org.junit.Test;
import static org.junit.Assert.*;

import java.nio.ByteBuffer;

/**
 * Test BOM (Byte Order Mark) detection and handling.
 */
public class BOMTest {
    
    /**
     * Test UTF-8 BOM (EF BB BF) - should be skipped and JSON parsed normally.
     */
    @Test
    public void testUTF8BOM() throws Exception {
        JSONParser parser = new JSONParser();
        TestHandler handler = new TestHandler();
        parser.setContentHandler(handler);
        
        // UTF-8 BOM followed by simple JSON
        byte[] data = new byte[] {
            (byte)0xEF, (byte)0xBB, (byte)0xBF,  // UTF-8 BOM
            '{', '"', 'a', '"', ':', '1', '}'
        };
        
        parser.receive(ByteBuffer.wrap(data));
        parser.close();
        
        assertEquals("Should have parsed one key", 1, handler.keys.size());
        assertEquals("a", handler.keys.get(0));
        assertEquals("Should have parsed one number", 1, handler.numbers.size());
        assertEquals(1, handler.numbers.get(0).intValue());
    }
    
    /**
     * Test UTF-8 BOM split across two receive() calls.
     */
    @Test
    public void testUTF8BOMSplit() throws Exception {
        JSONParser parser = new JSONParser();
        TestHandler handler = new TestHandler();
        parser.setContentHandler(handler);
        
        // First chunk: EF BB (incomplete BOM)
        byte[] chunk1 = new byte[] {(byte)0xEF, (byte)0xBB};
        parser.receive(ByteBuffer.wrap(chunk1));
        
        // Second chunk: BF (complete BOM) followed by JSON
        byte[] chunk2 = new byte[] {
            (byte)0xBF,  // Complete the BOM
            '[', '4', '2', ']'
        };
        parser.receive(ByteBuffer.wrap(chunk2));
        parser.close();
        
        assertEquals("Should have parsed one number", 1, handler.numbers.size());
        assertEquals(42, handler.numbers.get(0).intValue());
    }
    
    /**
     * Test UTF-8 BOM split byte-by-byte across three receive() calls.
     */
    @Test
    public void testUTF8BOMSplitByteByByte() throws Exception {
        JSONParser parser = new JSONParser();
        TestHandler handler = new TestHandler();
        parser.setContentHandler(handler);
        
        // Split BOM across three calls
        parser.receive(ByteBuffer.wrap(new byte[] {(byte)0xEF}));
        parser.receive(ByteBuffer.wrap(new byte[] {(byte)0xBB}));
        parser.receive(ByteBuffer.wrap(new byte[] {(byte)0xBF}));
        
        // Then send JSON
        parser.receive(ByteBuffer.wrap("true".getBytes()));
        parser.close();
        
        assertEquals("Should have parsed one boolean", 1, handler.booleans.size());
        assertTrue(handler.booleans.get(0));
    }
    
    /**
     * Test UTF-16 LE BOM (FF FE) - should be rejected.
     */
    @Test
    public void testUTF16LEBOM() {
        JSONParser parser = new JSONParser();
        parser.setContentHandler(new TestHandler());
        
        byte[] data = new byte[] {
            (byte)0xFF, (byte)0xFE,  // UTF-16 LE BOM
            '{', '}'
        };
        
        try {
            parser.receive(ByteBuffer.wrap(data));
            fail("Should have rejected UTF-16 LE encoding");
        } catch (JSONException e) {
            assertTrue("Should mention UTF-16", e.getMessage().contains("UTF-16"));
            assertTrue("Should mention not supported", e.getMessage().contains("not supported"));
        }
    }
    
    /**
     * Test UTF-16 BE BOM (FE FF) - should be rejected.
     */
    @Test
    public void testUTF16BEBOM() {
        JSONParser parser = new JSONParser();
        parser.setContentHandler(new TestHandler());
        
        byte[] data = new byte[] {
            (byte)0xFE, (byte)0xFF,  // UTF-16 BE BOM
            '{', '}'
        };
        
        try {
            parser.receive(ByteBuffer.wrap(data));
            fail("Should have rejected UTF-16 BE encoding");
        } catch (JSONException e) {
            assertTrue("Should mention UTF-16", e.getMessage().contains("UTF-16"));
            assertTrue("Should mention not supported", e.getMessage().contains("not supported"));
        }
    }
    
    /**
     * Test UTF-16 LE BOM split across receive() calls.
     */
    @Test
    public void testUTF16LEBOMSplit() {
        JSONParser parser = new JSONParser();
        parser.setContentHandler(new TestHandler());
        
        try {
            // First byte of UTF-16 LE BOM
            parser.receive(ByteBuffer.wrap(new byte[] {(byte)0xFF}));
            // Second byte completes the BOM
            parser.receive(ByteBuffer.wrap(new byte[] {(byte)0xFE, '{', '}'}));
            fail("Should have rejected UTF-16 LE encoding");
        } catch (JSONException e) {
            assertTrue("Should mention UTF-16", e.getMessage().contains("UTF-16"));
        }
    }
    
    /**
     * Test UTF-32 LE BOM (FF FE 00 00) - should be rejected.
     */
    @Test
    public void testUTF32LEBOM() {
        JSONParser parser = new JSONParser();
        parser.setContentHandler(new TestHandler());
        
        byte[] data = new byte[] {
            (byte)0xFF, (byte)0xFE, (byte)0x00, (byte)0x00,  // UTF-32 LE BOM
            '{', '}'
        };
        
        try {
            parser.receive(ByteBuffer.wrap(data));
            fail("Should have rejected UTF-32 LE encoding");
        } catch (JSONException e) {
            assertTrue("Should mention UTF-32", e.getMessage().contains("UTF-32"));
            assertTrue("Should mention not supported", e.getMessage().contains("not supported"));
        }
    }
    
    /**
     * Test UTF-32 BE BOM (00 00 FE FF) - should be rejected.
     */
    @Test
    public void testUTF32BEBOM() {
        JSONParser parser = new JSONParser();
        parser.setContentHandler(new TestHandler());
        
        byte[] data = new byte[] {
            (byte)0x00, (byte)0x00, (byte)0xFE, (byte)0xFF,  // UTF-32 BE BOM
            '{', '}'
        };
        
        try {
            parser.receive(ByteBuffer.wrap(data));
            fail("Should have rejected UTF-32 BE encoding");
        } catch (JSONException e) {
            assertTrue("Should mention UTF-32", e.getMessage().contains("UTF-32"));
            assertTrue("Should mention not supported", e.getMessage().contains("not supported"));
        }
    }
    
    /**
     * Test UTF-32 LE BOM split across multiple receive() calls.
     */
    @Test
    public void testUTF32LEBOMSplit() {
        JSONParser parser = new JSONParser();
        parser.setContentHandler(new TestHandler());
        
        try {
            // Send first two bytes
            parser.receive(ByteBuffer.wrap(new byte[] {(byte)0xFF, (byte)0xFE}));
            // This looks like UTF-16 LE so far, but need to wait...
            
            // Send next two bytes to complete UTF-32 LE BOM
            parser.receive(ByteBuffer.wrap(new byte[] {(byte)0x00, (byte)0x00, '{', '}'}));
            fail("Should have rejected UTF-32 LE encoding");
        } catch (JSONException e) {
            assertTrue("Should mention UTF-32", e.getMessage().contains("UTF-32"));
        }
    }
    
    /**
     * Test UTF-32 BE BOM split byte-by-byte.
     */
    @Test
    public void testUTF32BEBOMSplitByteByByte() {
        JSONParser parser = new JSONParser();
        parser.setContentHandler(new TestHandler());
        
        try {
            parser.receive(ByteBuffer.wrap(new byte[] {(byte)0x00}));
            parser.receive(ByteBuffer.wrap(new byte[] {(byte)0x00}));
            parser.receive(ByteBuffer.wrap(new byte[] {(byte)0xFE}));
            parser.receive(ByteBuffer.wrap(new byte[] {(byte)0xFF, '{', '}'}));
            fail("Should have rejected UTF-32 BE encoding");
        } catch (JSONException e) {
            assertTrue("Should mention UTF-32", e.getMessage().contains("UTF-32"));
        }
    }
    
    /**
     * Test no BOM - should parse normally.
     */
    @Test
    public void testNoBOM() throws Exception {
        JSONParser parser = new JSONParser();
        TestHandler handler = new TestHandler();
        parser.setContentHandler(handler);
        
        parser.receive(ByteBuffer.wrap("[null]".getBytes()));
        parser.close();
        
        assertEquals("Should have parsed one null", 1, handler.nullCount);
    }
    
    /**
     * Test that normal JSON without BOM works correctly.
     */
    @Test
    public void testNoBOMWithReset() throws Exception {
        JSONParser parser = new JSONParser();
        TestHandler handler = new TestHandler();
        parser.setContentHandler(handler);
        
        // Parse first document
        parser.receive(ByteBuffer.wrap("[1,2,3]".getBytes()));
        parser.close();
        
        assertEquals("Should have parsed 3 numbers", 3, handler.numbers.size());
        
        // Reset and parse second document
        parser.reset();
        handler.reset();
        parser.receive(ByteBuffer.wrap("[true,false]".getBytes()));
        parser.close();
        
        assertEquals("Should have parsed 2 booleans", 2, handler.booleans.size());
    }
    
    /**
     * Simple test handler to track events.
     */
    private static class TestHandler extends JSONDefaultHandler {
        java.util.List<String> keys = new java.util.ArrayList<>();
        java.util.List<Number> numbers = new java.util.ArrayList<>();
        java.util.List<Boolean> booleans = new java.util.ArrayList<>();
        int nullCount = 0;
        
        @Override
        public void key(String key) {
            keys.add(key);
        }
        
        @Override
        public void numberValue(Number number) {
            numbers.add(number);
        }
        
        @Override
        public void booleanValue(boolean value) {
            booleans.add(value);
        }
        
        @Override
        public void nullValue() {
            nullCount++;
        }
        
        void reset() {
            keys.clear();
            numbers.clear();
            booleans.clear();
            nullCount = 0;
        }
    }
}

