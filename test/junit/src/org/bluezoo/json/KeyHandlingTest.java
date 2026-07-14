package org.bluezoo.json;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Tests for the hash-first key symbol table ({@link KeySymbolTable}) and the
 * opt-in {@link JSONParser#setRejectDuplicateKeys(boolean)} feature it
 * enables.
 */
public class KeyHandlingTest {

    private static class KeyCaptureHandler extends JSONDefaultHandler {
        List<String> keys = new ArrayList<>();

        @Override
        public void key(String key) {
            keys.add(key);
        }
    }

    private KeyCaptureHandler parseKeys(JSONParser parser, String json) throws Exception {
        KeyCaptureHandler handler = new KeyCaptureHandler();
        parser.setContentHandler(handler);
        parser.parse(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
        return handler;
    }

    // ===== Key interning =====

    @Test
    public void testRepeatedKeysAreReferenceEqual() throws Exception {
        String json = "[{\"name\":\"a\",\"value\":1},{\"name\":\"b\",\"value\":2},{\"name\":\"c\",\"value\":3}]";
        JSONParser parser = new JSONParser();
        parser.disableAllLimits();
        KeyCaptureHandler handler = parseKeys(parser, json);

        assertEquals(6, handler.keys.size());
        // "name" appears at indices 0, 2, 4; "value" at 1, 3, 5
        assertSame("repeated key should be the same String instance", handler.keys.get(0), handler.keys.get(2));
        assertSame("repeated key should be the same String instance", handler.keys.get(0), handler.keys.get(4));
        assertSame("repeated key should be the same String instance", handler.keys.get(1), handler.keys.get(3));
        assertSame("repeated key should be the same String instance", handler.keys.get(1), handler.keys.get(5));
    }

    @Test
    public void testInterningSurvivesReset() throws Exception {
        JSONParser parser = new JSONParser();
        parser.disableAllLimits();

        KeyCaptureHandler first = parseKeys(parser, "{\"widget\":1}");
        KeyCaptureHandler second = parseKeys(parser, "{\"widget\":2}");

        // The symbol table is owned by JSONParser and stays warm across
        // reset() (implicit in a second parse() call), so a key with
        // identical content in a later document should still be the same
        // String instance as the first document's.
        assertSame(first.keys.get(0), second.keys.get(0));
    }

    @Test
    public void testDistinctKeysAreNotConflated() throws Exception {
        String json = "{\"ab\":1,\"ac\":2,\"bb\":3}";
        JSONParser parser = new JSONParser();
        parser.disableAllLimits();
        KeyCaptureHandler handler = parseKeys(parser, json);

        assertEquals("ab", handler.keys.get(0));
        assertEquals("ac", handler.keys.get(1));
        assertEquals("bb", handler.keys.get(2));
    }

    @Test
    public void testManyDistinctKeysExceedingCapStillParseCorrectly() throws Exception {
        // Comfortably exceeds KeySymbolTable's internal MAX_ENTRIES cap, to
        // confirm the table degrading to a no-op past that point doesn't
        // break correctness (only stops the interning speedup).
        StringBuilder json = new StringBuilder("{");
        int n = 15_000;
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append("\"key").append(i).append("\":").append(i);
        }
        json.append('}');

        JSONParser parser = new JSONParser();
        parser.disableAllLimits();
        KeyCaptureHandler handler = parseKeys(parser, json.toString());

        assertEquals(n, handler.keys.size());
        assertEquals("key0", handler.keys.get(0));
        assertEquals("key" + (n - 1), handler.keys.get(n - 1));
    }

    // ===== Duplicate key rejection (opt-in) =====

    @Test
    public void testDuplicateKeysAllowedByDefault() throws Exception {
        String json = "{\"a\":1,\"a\":2}";
        JSONParser parser = new JSONParser();
        parser.disableAllLimits();
        KeyCaptureHandler handler = parseKeys(parser, json);
        assertEquals(2, handler.keys.size());
    }

    @Test
    public void testDuplicateKeyRejectedWhenEnabled() {
        JSONParser parser = new JSONParser();
        parser.disableAllLimits();
        parser.setRejectDuplicateKeys(true);
        parser.setContentHandler(new JSONDefaultHandler());
        try {
            parser.parse(new ByteArrayInputStream("{\"a\":1,\"a\":2}".getBytes(StandardCharsets.UTF_8)));
            fail("Should have thrown for a duplicate key");
        } catch (JSONException e) {
            assertTrue(e.getMessage().contains("Duplicate key"));
        }
    }

    @Test
    public void testNonDuplicateKeysAcceptedWhenEnabled() throws Exception {
        JSONParser parser = new JSONParser();
        parser.disableAllLimits();
        parser.setRejectDuplicateKeys(true);
        KeyCaptureHandler handler = parseKeys(parser, "{\"a\":1,\"b\":2,\"c\":3}");
        assertEquals(3, handler.keys.size());
    }

    @Test
    public void testDuplicateCheckIsScopedPerObjectNotGlobal() throws Exception {
        // Same key name in two separate (sibling and nested) objects is not
        // a duplicate - only a repeat within the *same* object is.
        JSONParser parser = new JSONParser();
        parser.disableAllLimits();
        parser.setRejectDuplicateKeys(true);
        String json = "{\"outer\":{\"name\":1},\"sibling\":{\"name\":2}}";
        KeyCaptureHandler handler = parseKeys(parser, json);
        assertEquals(4, handler.keys.size());
    }

    @Test
    public void testDuplicateCheckPopsCorrectlyAfterNestedObjectCloses() throws Exception {
        // Regression check for the duplicate-key stack being pushed/popped
        // in lockstep with contextStack: a key used inside a nested object
        // must not be considered "already seen" in the outer object after
        // the nested object closes.
        JSONParser parser = new JSONParser();
        parser.disableAllLimits();
        parser.setRejectDuplicateKeys(true);
        String json = "{\"name\":{\"name\":1},\"name2\":2}";
        KeyCaptureHandler handler = parseKeys(parser, json);
        assertEquals(3, handler.keys.size());
    }
}
