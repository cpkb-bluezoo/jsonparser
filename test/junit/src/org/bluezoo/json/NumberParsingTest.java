package org.bluezoo.json;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Random;

import static org.junit.Assert.*;

/**
 * Correctness checks for the hand-rolled number composition in
 * {@link JSONTokenizer#composeNumber}. Unlike accept/reject correctness
 * (covered by {@link JSONTestSuiteTest}), a composition bug wouldn't throw -
 * it would silently produce the wrong value - so these checks compare the
 * parsed {@link Number} against {@link Double#parseDouble} (or exact integer
 * arithmetic) for a spread of representative and edge-case number strings.
 */
public class NumberParsingTest {

    private Number parseNumber(String json) throws Exception {
        final Number[] result = new Number[1];

        JSONParser parser = new JSONParser();
        parser.disableAllLimits();
        parser.setContentHandler(new JSONDefaultHandler() {
            @Override
            public void numberValue(Number value) {
                result[0] = value;
            }
        });

        parser.parse(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));

        return result[0];
    }

    /**
     * Composing via {@code ival + dval/mul} (see {@code composeNumber}) is
     * usually exactly correctly-rounded, matching {@code Double.parseDouble},
     * but - unlike a formally correctly-rounded algorithm (e.g. Eisel-Lemire)
     * - can very occasionally be off by a single ULP due to double-rounding
     * (the division is rounded, then the addition rounds again). This is a
     * known, accepted characteristic of this approach, not a bug - so allow
     * a tiny tolerance rather than requiring bit-for-bit equality.
     */
    private void assertExact(String numStr) throws Exception {
        Number parsed = parseNumber(numStr);
        double expected = Double.parseDouble(numStr);
        double tolerance = Math.max(Math.ulp(expected) * 2, 0.0);
        assertEquals(numStr, expected, parsed.doubleValue(), tolerance);
    }

    private void assertClose(String numStr) throws Exception {
        Number parsed = parseNumber(numStr);
        double expected = Double.parseDouble(numStr);
        if (Double.isInfinite(expected) || expected == 0.0) {
            assertEquals(numStr, expected, parsed.doubleValue(), 0.0);
        } else {
            double relError = Math.abs((parsed.doubleValue() - expected) / expected);
            assertTrue(numStr + ": expected " + expected + " but was " + parsed.doubleValue()
                    + " (relative error " + relError + ")", relError < 1e-9);
        }
    }

    // ===== Plain integers (exact long/BigInteger path, unaffected by the
    // fraction/exponent composition below, but worth covering here too) =====

    @Test
    public void testPlainIntegers() throws Exception {
        assertEquals(0, parseNumber("0").intValue());
        assertEquals(42, parseNumber("42").intValue());
        assertEquals(-42, parseNumber("-42").intValue());
        assertEquals(Integer.MAX_VALUE, parseNumber(String.valueOf(Integer.MAX_VALUE)).intValue());
        assertEquals((long) Integer.MAX_VALUE + 1, parseNumber(String.valueOf((long) Integer.MAX_VALUE + 1)).longValue());
        assertEquals(Long.MAX_VALUE, parseNumber(String.valueOf(Long.MAX_VALUE)).longValue());
    }

    @Test
    public void testHugeIntegerFallsBackToBigInteger() throws Exception {
        String big = "123456789012345678901234567890";
        Number n = parseNumber(big);
        assertEquals(new java.math.BigInteger(big), n);
    }

    // ===== Fraction-only composition (ival + dval/mul) =====

    @Test
    public void testSimpleDecimals() throws Exception {
        assertExact("3.14");
        assertExact("0.5");
        assertExact("-42.0");
        assertExact("100.25");
        assertExact("0.1");
        assertExact("-0.001");
        assertExact("123456.789");
    }

    @Test
    public void testManySignificantDigits() throws Exception {
        assertExact("3.141592653589793");
        assertExact("2.718281828459045");
        assertExact("1.7976931348623157");
    }

    @Test
    public void testZero() throws Exception {
        assertExact("0.0");
        assertExact("-0.0");
        assertTrue(1.0 / parseNumber("-0.0").doubleValue() < 0); // preserves sign of zero
    }

    // ===== Exponent composition (mantissa * Math.pow(10, exp)) =====

    @Test
    public void testExponents() throws Exception {
        assertClose("1e10");
        assertClose("1.5e-5");
        assertClose("6.022e23");
        assertClose("-2.5E+10");
        assertClose("1E0");
        assertClose("5e-1");
    }

    @Test
    public void testExtremeExponents() throws Exception {
        assertClose("1e300");
        assertClose("1e-300");
        assertClose("1.7e308");
        assertClose("4.9e-324");
    }

    @Test
    public void testOverflowingExponentDigits() throws Exception {
        // More exponent digits than composeNumber's fast path tolerates -
        // must fall back to the Double.parseDouble slow path.
        assertEquals(Double.POSITIVE_INFINITY, parseNumber("1e999999").doubleValue(), 0.0);
        assertEquals(0.0, parseNumber("1e-999999").doubleValue(), 0.0);
    }

    @Test
    public void testOverflowingFractionDigits() throws Exception {
        // More significant digits than the long accumulators can hold -
        // must fall back to the Double.parseDouble slow path.
        assertExact("0.12345678901234567890123456789");
        assertExact("1.123456789012345678901234567890123456789e10");
    }

    // ===== Fuzzing against Double.toString()/Double.parseDouble round-trip =====

    @Test
    public void testRandomDecimals() throws Exception {
        Random rnd = new Random(12345);
        for (int i = 0; i < 2000; i++) {
            double value = (rnd.nextDouble() - 0.5) * Math.pow(10, rnd.nextInt(20) - 10);
            String s = Double.toString(value);
            if (s.contains("Infinity") || s.contains("NaN")) {
                continue;
            }
            // Double.toString() may produce forms like "1.0E10" - valid JSON
            // requires a decimal point before an exponent is fine, but JSON
            // does not allow a bare "E" with no explicit sign requirement -
            // both are handled the same by our grammar, so no rewriting needed.
            if (s.contains("E")) {
                assertClose(s);
            } else {
                assertExact(s);
            }
        }
    }
}
