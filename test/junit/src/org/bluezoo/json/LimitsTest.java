package org.bluezoo.json;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

/**
 * Tests for the configurable resource-exhaustion guard limits
 * ({@link ParserLimits}, exposed via {@link JSONParser}'s
 * {@code setMaxXxx} methods). Covers: each limit actually rejects input
 * that exceeds it and accepts input exactly at it, {@code <= 0} disables a
 * limit, and the documented industry-standard defaults are what's actually
 * wired in by default.
 */
public class LimitsTest {

    private static String nestedArray(int depth) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            sb.append('[');
        }
        sb.append('1');
        for (int i = 0; i < depth; i++) {
            sb.append(']');
        }
        return sb.toString();
    }

    private static String digits(int count) {
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            sb.append('9'); // avoids the leading-zero grammar rule entirely
        }
        return sb.toString();
    }

    private static String quotedString(int length, char fill) {
        StringBuilder sb = new StringBuilder(length + 2);
        sb.append('"');
        for (int i = 0; i < length; i++) {
            sb.append(fill);
        }
        sb.append('"');
        return sb.toString();
    }

    private void expectSuccess(JSONParser parser, String json) throws Exception {
        parser.setContentHandler(new JSONDefaultHandler());
        parser.parse(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    }

    private void expectFailure(JSONParser parser, String json) {
        parser.setContentHandler(new JSONDefaultHandler());
        try {
            parser.parse(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
            fail("Expected a JSONException for: " + json.substring(0, Math.min(60, json.length())));
        } catch (JSONException expected) {
            // pass
        }
    }

    // ===== Nesting depth =====

    @Test
    public void testMaxNestingDepthCustom() throws Exception {
        JSONParser parser = new JSONParser();
        parser.setMaxNestingDepth(3);
        expectSuccess(parser, nestedArray(3));
        parser.reset();
        expectFailure(parser, nestedArray(4));
    }

    @Test
    public void testMaxNestingDepthDisabled() throws Exception {
        JSONParser parser = new JSONParser();
        parser.disableAllLimits();
        expectSuccess(parser, nestedArray(2000));
    }

    @Test
    public void testMaxNestingDepthDefault() throws Exception {
        JSONParser parser = new JSONParser();
        expectSuccess(parser, nestedArray(1000));
        parser.reset();
        expectFailure(parser, nestedArray(1001));
    }

    // ===== Number length =====

    @Test
    public void testMaxNumberLengthCustom() throws Exception {
        JSONParser parser = new JSONParser();
        parser.setMaxNumberLength(5);
        expectSuccess(parser, digits(5));
        parser.reset();
        expectFailure(parser, digits(6));
    }

    @Test
    public void testMaxNumberLengthDisabled() throws Exception {
        JSONParser parser = new JSONParser();
        parser.disableAllLimits();
        expectSuccess(parser, digits(2000));
    }

    @Test
    public void testMaxNumberLengthDefault() throws Exception {
        JSONParser parser = new JSONParser();
        expectSuccess(parser, digits(1000));
        parser.reset();
        expectFailure(parser, digits(1001));
    }

    // ===== String length =====

    @Test
    public void testMaxStringLengthCustom() throws Exception {
        JSONParser parser = new JSONParser();
        parser.setMaxStringLength(5);
        expectSuccess(parser, quotedString(5, 'a'));
        parser.reset();
        expectFailure(parser, quotedString(6, 'a'));
    }

    @Test
    public void testMaxStringLengthDisabled() throws Exception {
        JSONParser parser = new JSONParser();
        parser.disableAllLimits();
        expectSuccess(parser, quotedString(100_000, 'a'));
    }

    // ===== Key/name length =====

    @Test
    public void testMaxNameLengthCustom() throws Exception {
        JSONParser parser = new JSONParser();
        parser.setMaxNameLength(5);
        expectSuccess(parser, "{" + quotedString(5, 'k') + ":1}");
        parser.reset();
        expectFailure(parser, "{" + quotedString(6, 'k') + ":1}");
    }

    @Test
    public void testMaxNameLengthDoesNotAffectStringValues() throws Exception {
        // A long string VALUE should not be constrained by maxNameLength -
        // only maxStringLength applies to values, maxNameLength to keys.
        JSONParser parser = new JSONParser();
        parser.setMaxNameLength(5);
        parser.setMaxStringLength(0);
        expectSuccess(parser, "{" + quotedString(5, 'k') + ":" + quotedString(50, 'v') + "}");
    }

    // ===== Document length =====

    @Test
    public void testMaxDocumentLengthCustom() throws Exception {
        String json = "[1,2,3]"; // exactly 7 bytes
        JSONParser parser = new JSONParser();
        parser.setMaxDocumentLength(7);
        expectSuccess(parser, json);
        parser.reset();
        parser.setMaxDocumentLength(6);
        expectFailure(parser, json);
    }

    @Test
    public void testMaxDocumentLengthDisabledByDefault() throws Exception {
        // maxDocumentLength is unlimited (0) even at JSONParser defaults,
        // matching Jackson - confirm a sizeable document is unaffected
        // without touching any setter.
        JSONParser parser = new JSONParser();
        expectSuccess(parser, quotedString(100_000, 'a'));
    }

    // ===== Token count =====

    @Test
    public void testMaxTokenCountCustom() throws Exception {
        JSONParser parser = new JSONParser();
        parser.setMaxTokenCount(100);
        expectFailure(parser, bigArray(1000));
        parser.reset();
        parser.setMaxTokenCount(10_000);
        expectSuccess(parser, bigArray(1000));
    }

    @Test
    public void testMaxTokenCountDisabledByDefault() throws Exception {
        JSONParser parser = new JSONParser();
        expectSuccess(parser, bigArray(10_000));
    }

    private static String bigArray(int elements) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < elements; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(i);
        }
        sb.append(']');
        return sb.toString();
    }

    // ===== disableAllLimits() =====

    @Test
    public void testDisableAllLimits() throws Exception {
        JSONParser parser = new JSONParser();
        parser.setMaxNestingDepth(2);
        parser.setMaxStringLength(2);
        parser.setMaxNumberLength(2);
        parser.setMaxNameLength(2);

        expectFailure(parser, nestedArray(5));

        parser.reset();
        parser.disableAllLimits();
        expectSuccess(parser, nestedArray(5));
        parser.reset();
        expectSuccess(parser, quotedString(50, 'a'));
        parser.reset();
        expectSuccess(parser, digits(50));
    }
}
