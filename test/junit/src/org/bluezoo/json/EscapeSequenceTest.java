package org.bluezoo.json;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

/**
 * Comprehensive test for JSON escape sequences.
 * Tests all escape sequence types in various positions and combinations.
 */
public class EscapeSequenceTest {

    /**
     * Helper to parse a JSON string and extract the string value.
     */
    private String parseString(String json) throws Exception {
        final String[] result = new String[1];
        
        JSONParser parser = new JSONParser();
        parser.setContentHandler(new JSONDefaultHandler() {
            @Override
            public void stringValue(String value) {
                result[0] = value;
            }
        });
        
        parser.parse(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
        
        return result[0];
    }

    // ===== Basic Escape Sequences =====

    @Test
    public void testEscapeQuote() throws Exception {
        assertEquals("Hello\"World", parseString("\"Hello\\\"World\""));
    }

    @Test
    public void testEscapeBackslash() throws Exception {
        assertEquals("Hello\\World", parseString("\"Hello\\\\World\""));
    }

    @Test
    public void testEscapeSolidus() throws Exception {
        assertEquals("Hello/World", parseString("\"Hello\\/World\""));
    }

    @Test
    public void testEscapeBackspace() throws Exception {
        assertEquals("Hello\bWorld", parseString("\"Hello\\bWorld\""));
    }

    @Test
    public void testEscapeFormFeed() throws Exception {
        assertEquals("Hello\fWorld", parseString("\"Hello\\fWorld\""));
    }

    @Test
    public void testEscapeNewline() throws Exception {
        assertEquals("Hello\nWorld", parseString("\"Hello\\nWorld\""));
    }

    @Test
    public void testEscapeCarriageReturn() throws Exception {
        assertEquals("Hello\rWorld", parseString("\"Hello\\rWorld\""));
    }

    @Test
    public void testEscapeTab() throws Exception {
        assertEquals("Hello\tWorld", parseString("\"Hello\\tWorld\""));
    }

    // ===== Position Tests =====

    @Test
    public void testEscapeAtBeginning() throws Exception {
        assertEquals("\"Hello", parseString("\"\\\"Hello\""));
        assertEquals("\\Hello", parseString("\"\\\\Hello\""));
        assertEquals("\nHello", parseString("\"\\nHello\""));
    }

    @Test
    public void testEscapeInMiddle() throws Exception {
        assertEquals("Hel\"lo", parseString("\"Hel\\\"lo\""));
        assertEquals("Hel\\lo", parseString("\"Hel\\\\lo\""));
        assertEquals("Hel\nlo", parseString("\"Hel\\nlo\""));
    }

    @Test
    public void testEscapeAtEnd() throws Exception {
        assertEquals("Hello\"", parseString("\"Hello\\\"\""));
        assertEquals("Hello\\", parseString("\"Hello\\\\\""));
        assertEquals("Hello\n", parseString("\"Hello\\n\""));
    }

    // ===== Multiple Escapes =====

    @Test
    public void testMultipleEscapes() throws Exception {
        assertEquals("\"\\\n\r\t", parseString("\"\\\"\\\\\\n\\r\\t\""));
    }

    @Test
    public void testConsecutiveEscapes() throws Exception {
        assertEquals("\\\\", parseString("\"\\\\\\\\\""));
        assertEquals("\"\"\n\n", parseString("\"\\\"\\\"\\n\\n\""));
    }

    @Test
    public void testAllBasicEscapes() throws Exception {
        String result = parseString("\"\\\"\\\\/\\b\\f\\n\\r\\t\"");
        assertEquals("\"\\/\b\f\n\r\t", result);
    }

    // ===== Unicode Escapes =====

    @Test
    public void testUnicodeEscapeBasic() throws Exception {
        assertEquals("A", parseString("\"\\u0041\""));  // 'A'
        assertEquals("a", parseString("\"\\u0061\""));  // 'a'
    }

    @Test
    public void testUnicodeEscapeNonAscii() throws Exception {
        assertEquals("é", parseString("\"\\u00e9\""));  // e-acute
        assertEquals("€", parseString("\"\\u20ac\""));  // Euro sign
        assertEquals("中", parseString("\"\\u4e2d\""));  // Chinese character
    }

    @Test
    public void testUnicodeEscapePositions() throws Exception {
        assertEquals("A", parseString("\"\\u0041\""));           // Start
        assertEquals("Hello A World", parseString("\"Hello \\u0041 World\""));  // Middle
        assertEquals("HelloA", parseString("\"Hello\\u0041\""));  // End
    }

    @Test
    public void testMultipleUnicodeEscapes() throws Exception {
        assertEquals("ABC", parseString("\"\\u0041\\u0042\\u0043\""));
    }

    @Test
    public void testMixedUnicodeAndRegular() throws Exception {
        assertEquals("Hello€World", parseString("\"Hello\\u20acWorld\""));
        assertEquals("A\nB", parseString("\"\\u0041\\n\\u0042\""));
    }

    @Test
    public void testUnicodeUpperAndLowerCase() throws Exception {
        // Both uppercase and lowercase hex digits should work
        assertEquals("A", parseString("\"\\u0041\""));  // lowercase
        assertEquals("A", parseString("\"\\u0041\""));  // Already lowercase
        assertEquals("€", parseString("\"\\u20AC\""));  // uppercase C
        assertEquals("€", parseString("\"\\u20ac\""));  // lowercase c
    }

    // ===== Surrogate Pairs =====

    @Test
    public void testSurrogatePairEmoji() throws Exception {
        // 😀 (U+1F600) = high surrogate \uD83D + low surrogate \uDE00
        String json = "\"\\uD83D\\uDE00\"";
        String result = parseString(json);
        assertEquals("😀", result);
        assertEquals(2, result.length());  // Java uses UTF-16, so 2 chars
        assertEquals(0xD83D, (int)result.charAt(0));
        assertEquals(0xDE00, (int)result.charAt(1));
    }

    @Test
    public void testSurrogatePairWithText() throws Exception {
        // 😀 in the middle of text
        assertEquals("Hello😀World", parseString("\"Hello\\uD83D\\uDE00World\""));
    }

    @Test
    public void testMultipleSurrogatePairs() throws Exception {
        // 😀 (U+1F600) and 🌍 (U+1F30D)
        String json = "\"\\uD83D\\uDE00\\uD83C\\uDF0D\"";
        String result = parseString(json);
        assertTrue(result.contains("😀"));
        assertTrue(result.contains("🌍"));
    }

    @Test
    public void testSurrogatePairAtPositions() throws Exception {
        // At start
        String result1 = parseString("\"\\uD83D\\uDE00Hello\"");
        assertTrue(result1.startsWith("😀"));
        
        // At end  
        String result2 = parseString("\"Hello\\uD83D\\uDE00\"");
        assertTrue(result2.endsWith("😀"));
    }

    // ===== Control Characters (must be escaped) =====

    @Test
    public void testControlCharactersMustBeEscaped() throws Exception {
        // Control characters U+0000 through U+001F must be escaped
        assertEquals("\u0001", parseString("\"\\u0001\""));
        assertEquals("\u001F", parseString("\"\\u001F\""));
    }

    @Test
    public void testNullCharacter() throws Exception {
        assertEquals("\u0000", parseString("\"\\u0000\""));
    }

    // ===== Edge Cases =====

    @Test
    public void testEmptyString() throws Exception {
        assertEquals("", parseString("\"\""));
    }

    @Test
    public void testOnlyEscapes() throws Exception {
        assertEquals("\"\n\t", parseString("\"\\\"\\n\\t\""));
    }

    @Test
    public void testEscapeFollowedByNormalChar() throws Exception {
        assertEquals("\"a\\b\nc", parseString("\"\\\"a\\\\b\\nc\""));
    }

    @Test
    public void testUnicodeZero() throws Exception {
        assertEquals("\u0000", parseString("\"\\u0000\""));
    }

    @Test
    public void testUnicodeMaxBMP() throws Exception {
        // U+FFFF is the last character in the Basic Multilingual Plane
        assertEquals("\uFFFF", parseString("\"\\uFFFF\""));
    }

    // ===== Invalid Escape Sequences (should fail) =====

    @Test
    public void testInvalidEscapeSequence() throws Exception {
        try {
            parseString("\"\\x\"");  // \x is not valid
            fail("Should have thrown JSONException for invalid escape sequence");
        } catch (JSONException e) {
            assertTrue(e.getMessage().contains("escape"));
        }
    }

    @Test
    public void testIncompleteUnicodeEscape() throws Exception {
        try {
            parseString("\"\\u004\"");  // Only 3 hex digits
            fail("Should have thrown JSONException for incomplete unicode escape");
        } catch (Exception e) {
            // Expected - either JSONException or EOFException
        }
    }

    @Test
    public void testInvalidHexInUnicode() throws Exception {
        try {
            parseString("\"\\u00XY\"");  // X and Y are not hex digits
            fail("Should have thrown JSONException for invalid hex in unicode escape");
        } catch (JSONException e) {
            assertTrue(e.getMessage().toLowerCase().contains("hex"));
        }
    }

    @Test
    public void testUnescapedControlCharacter() throws Exception {
        try {
            // Control character must be escaped
            parseString("\"\u0001\"");
            fail("Should have thrown JSONException for unescaped control character");
        } catch (JSONException e) {
            assertTrue(e.getMessage().toLowerCase().contains("control"));
        }
    }

    @Test
    public void testBackslashAtEnd() throws Exception {
        try {
            parseString("\"Hello\\\"");  // Incomplete escape at end
            fail("Should have thrown exception for incomplete escape");
        } catch (Exception e) {
            // Expected - either JSONException or EOFException
        }
    }

    // ===== Real-World Examples =====

    @Test
    public void testJsonWithQuotesAndBackslashes() throws Exception {
        String json = "\"He said: \\\"It's a backslash: \\\\\\\"\"";
        assertEquals("He said: \"It's a backslash: \\\"", parseString(json));
    }

    @Test
    public void testFilePath() throws Exception {
        assertEquals("C:\\Users\\test\\file.txt", 
            parseString("\"C:\\\\Users\\\\test\\\\file.txt\""));
    }

    @Test
    public void testJsonString() throws Exception {
        // A JSON string containing JSON
        String json = "\"{\\\"key\\\": \\\"value\\\"}\"";
        assertEquals("{\"key\": \"value\"}", parseString(json));
    }

    @Test
    public void testMultilineText() throws Exception {
        assertEquals("Line 1\nLine 2\nLine 3", 
            parseString("\"Line 1\\nLine 2\\nLine 3\""));
    }

    @Test
    public void testUrlWithSlashes() throws Exception {
        assertEquals("https://example.com/path/to/resource", 
            parseString("\"https:\\/\\/example.com\\/path\\/to\\/resource\""));
    }
}

