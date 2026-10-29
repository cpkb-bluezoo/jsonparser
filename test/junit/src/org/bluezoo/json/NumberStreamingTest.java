package org.bluezoo.json;

import org.junit.Test;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Tests that numbers are read in a single forward pass whose state survives
 * any {@code receive()} boundary, and that the number ending a document is
 * completed by {@code close()}.
 */
public class NumberStreamingTest {

    private static final int[] CHUNKS = {1, 2, 3, 1000};

    /** Parses the document in chunks and returns the numbers reported. */
    private static List<Number> parse(String json, int chunk) throws JSONException {
        List<Number> numbers = new ArrayList<>();
        JSONParser parser = new JSONParser();
        parser.setContentHandler(new JSONDefaultHandler() {
            @Override
            public void numberValue(Number number) {
                numbers.add(number);
            }
        });
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(chunk + 256);
        for (int off = 0; off < bytes.length; off += chunk) {
            buffer.put(bytes, off, Math.min(chunk, bytes.length - off));
            buffer.flip();
            parser.receive(buffer);
            buffer.compact();
        }
        parser.close();
        return numbers;
    }

    private static void assertSame(String text, Number expected) throws JSONException {
        for (int chunk : CHUNKS) {
            for (String doc : new String[] {text, "[" + text + "]", "[1," + text + " ,2]"}) {
                List<Number> got = parse(doc, chunk);
                Number n = got.get(doc.startsWith("[1,") ? 1 : 0);
                assertEquals(doc + " chunk=" + chunk + " type", expected.getClass(), n.getClass());
                if (expected instanceof Double) {
                    assertEquals(doc + " chunk=" + chunk, Double.doubleToLongBits((Double) expected),
                            Double.doubleToLongBits((Double) n));
                } else {
                    assertEquals(doc + " chunk=" + chunk, expected, n);
                }
            }
        }
    }

    @Test
    public void integersKeepTheirTypeAtEveryChunkSize() throws Exception {
        assertSame("0", 0);
        assertSame("-0", 0);
        assertSame("7", 7);
        assertSame("-7", -7);
        assertSame("2147483647", Integer.MAX_VALUE);
        assertSame("-2147483648", Integer.MIN_VALUE);
        assertSame("2147483648", 2147483648L);
        assertSame("-2147483649", -2147483649L);
        assertSame("9223372036854775807", Long.MAX_VALUE);
        assertSame("-9223372036854775807", -Long.MAX_VALUE);
    }

    @Test
    public void integersBeyondALongStayExact() throws Exception {
        assertSame("9223372036854775808", new BigInteger("9223372036854775808"));
        assertSame("-9223372036854775808", new BigInteger("-9223372036854775808"));
        assertSame("123456789012345678901234567890", new BigInteger("123456789012345678901234567890"));
        assertSame("-123456789012345678901234567890", new BigInteger("-123456789012345678901234567890"));
        StringBuilder big = new StringBuilder("9");
        for (int i = 1; i < 1000; i++) {
            big.append((char) ('0' + i % 10));
        }
        assertSame(big.toString(), new BigInteger(big.toString()));
        // exactly 18, 19 and 36 digits straddle the 18-digit chunking of the BigInteger
        assertSame("999999999999999999", 999999999999999999L);
        assertSame("1000000000000000000000000000000000000", new BigInteger("1000000000000000000000000000000000000"));
        assertSame("123456789012345678123456789012345678", new BigInteger("123456789012345678123456789012345678"));
    }

    @Test
    public void realNumbersAreCorrectlyRoundedAtEveryChunkSize() throws Exception {
        for (String text : new String[] {
                "0.0", "-0.0", "0.5", "-0.5", "1e2", "1E+2", "1e-2", "123.456e-7", "3.141592653589793",
                "1.7976931348623157e308", "4.9e-324", "2.2250738585072014e-308", "0.000000000000000000001",
                "9007199254740993.0", "1e400", "1e-400", "-1e400" }) {
            assertSame(text, Double.valueOf(text));
        }
    }

    @Test
    public void numberAtTheEndOfTheDocumentIsCompletedByClose() throws Exception {
        List<Number> numbers = new ArrayList<>();
        JSONParser parser = new JSONParser();
        parser.setContentHandler(new JSONDefaultHandler() {
            @Override
            public void numberValue(Number number) {
                numbers.add(number);
            }
        });
        ByteBuffer buffer = ByteBuffer.wrap("42".getBytes(StandardCharsets.UTF_8));
        parser.receive(buffer);
        assertTrue("42 might still continue, so it is not reported yet", numbers.isEmpty());
        assertFalse("the digits were consumed, not left for the caller", buffer.hasRemaining());
        parser.close();
        assertEquals(1, numbers.size());
        assertEquals(42, numbers.get(0));
    }

    @Test
    public void numbersEndingTheInputAreCompletedInEveryForm() throws Exception {
        for (String text : new String[] {"0", "7", "-7", "10", "1.5", "-1.5e3", "2E-2", "0e0", "123456789012345678901234"}) {
            for (int chunk : CHUNKS) {
                List<Number> got = parse(text, chunk);
                assertEquals(text + " chunk=" + chunk, 1, got.size());
            }
        }
    }

    @Test
    public void incompleteNumbersAreRejected() throws Exception {
        for (String text : new String[] {"-", "1.", "1.e5", "1e", "1e+", "1e-", "-e5", "-.5", ".5", "+1", "0x10", "1..2", "--1"}) {
            for (int chunk : CHUNKS) {
                try {
                    parse(text, chunk);
                    fail("'" + text + "' chunk=" + chunk + " should be rejected");
                } catch (JSONException expected) {
                    // rejected
                }
                try {
                    parse("[" + text + "]", chunk);
                    fail("'[" + text + "]' chunk=" + chunk + " should be rejected");
                } catch (JSONException expected) {
                    // rejected
                }
            }
        }
    }

    @Test
    public void leadingZerosAreRejected() throws Exception {
        for (String text : new String[] {"01", "-01", "00", "0123", "00.5"}) {
            for (int chunk : CHUNKS) {
                try {
                    parse("[" + text + "]", chunk);
                    fail("'" + text + "' chunk=" + chunk + " should be rejected");
                } catch (JSONException expected) {
                    // rejected
                }
            }
        }
    }

    @Test
    public void maxNumberLengthAppliesAcrossChunks() throws Exception {
        for (int chunk : new int[] {1, 3, 1000}) {
            for (String longEnough : new String[] {"12345", "1.234", "1e123", "-1234"}) {
                JSONParser ok = new JSONParser();
                ok.setContentHandler(new JSONDefaultHandler());
                ok.setMaxNumberLength(5);
                feed(ok, "[" + longEnough + "]", chunk);
                ok.close();
            }
            for (String tooLong : new String[] {"123456", "1.2345", "1e1234", "-12345"}) {
                JSONParser bad = new JSONParser();
                bad.setContentHandler(new JSONDefaultHandler());
                bad.setMaxNumberLength(5);
                try {
                    feed(bad, "[" + tooLong + "]", chunk);
                    bad.close();
                    fail(tooLong + " chunk=" + chunk + " exceeds the limit");
                } catch (JSONException expected) {
                    // rejected
                }
            }
        }
    }

    @Test
    public void nothingLeaksFromOneNumberIntoTheNext() throws Exception {
        // Numbers of every shape, in random order in one document: each must
        // come out exactly as it does when parsed alone. A field left over
        // from the previous number (an exponent, a dropped digit, a big
        // integer) would change the next one.
        String[] shapes = {
            "0", "-0", "7", "-123", "2147483648", "9223372036854775807", "9223372036854775808",
            "123456789012345678901234567890", "-123456789012345678901234567890",
            "0.5", "-48.742780554549256", "3.141592653589793", "0.000000000000000000001",
            "4.793931e-07", "5.874274e+08", "1E2", "-1e-2", "0e0", "1.5E300", "-2.5e-300",
            "1e400", "1e-400", "0.12345678901234567890123", "12345678901234567890.5",
            "123456789012345678901234567890e-10", "1000000000000000000000000000000.0"
        };
        java.util.Random rnd = new java.util.Random(9);
        StringBuilder doc = new StringBuilder("[");
        List<String> order = new ArrayList<>();
        for (int i = 0; i < 3000; i++) {
            String shape = shapes[rnd.nextInt(shapes.length)];
            order.add(shape);
            doc.append(i == 0 ? "" : ",").append(shape);
        }
        doc.append("]");

        List<Number> alone = new ArrayList<>();
        for (String shape : shapes) {
            alone.add(parse("[" + shape + "]", 1000).get(0));
        }
        for (int chunk : new int[] {1000, 1, 7}) {
            List<Number> got = parse(doc.toString(), chunk);
            assertEquals(order.size(), got.size());
            for (int i = 0; i < order.size(); i++) {
                Number expected = alone.get(java.util.Arrays.asList(shapes).indexOf(order.get(i)));
                Number n = got.get(i);
                assertEquals("#" + i + " " + order.get(i) + " chunk=" + chunk, expected.getClass(), n.getClass());
                if (expected instanceof Double) {
                    assertEquals("#" + i + " " + order.get(i) + " chunk=" + chunk,
                            Double.doubleToLongBits((Double) expected), Double.doubleToLongBits((Double) n));
                } else {
                    assertEquals("#" + i + " " + order.get(i) + " chunk=" + chunk, expected, n);
                }
            }
        }
    }

    private static void feed(JSONParser parser, String json, int chunk) throws JSONException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(chunk + 256);
        for (int off = 0; off < bytes.length; off += chunk) {
            buffer.put(bytes, off, Math.min(chunk, bytes.length - off));
            buffer.flip();
            parser.receive(buffer);
            buffer.compact();
        }
    }
}
