package org.bluezoo.json;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.*;

/**
 * Real numbers must convert exactly as {@link Double#parseDouble} does
 * (correctly rounded, to the nearest double), not approximately. This is
 * checked bit for bit against the JDK on fixed edge cases and on a seeded
 * random sample, so a failure is reproducible.
 */
public class NumberRoundingTest {

    private final JSONParser parser = new JSONParser();
    private Number last;

    public NumberRoundingTest() {
        parser.setContentHandler(new JSONDefaultHandler() {
            @Override
            public void numberValue(Number number) {
                last = number;
            }
        });
    }

    private Number parse(String number) throws Exception {
        parser.reset();
        last = null;
        parser.receive(ByteBuffer.wrap(("[" + number + "]").getBytes(StandardCharsets.US_ASCII)));
        parser.close();
        return last;
    }

    /** Returns a description of the mismatch, or null if the parser agrees with the JDK. */
    private String mismatch(String number) throws Exception {
        double expected = Double.parseDouble(number);
        Number got = parse(number);
        if (!(got instanceof Double)) {
            return number + ": expected a Double but got " + got.getClass().getSimpleName();
        }
        double actual = got.doubleValue();
        if (Double.doubleToLongBits(expected) != Double.doubleToLongBits(actual)) {
            return number + ": expected " + expected + " but got " + actual;
        }
        return null;
    }

    private void assertAllExact(List<String> numbers) throws Exception {
        List<String> failures = new ArrayList<>();
        for (String n : numbers) {
            String m = mismatch(n);
            if (m != null) {
                failures.add(m);
            }
        }
        if (!failures.isEmpty()) {
            fail(failures.size() + " of " + numbers.size() + " numbers differ from Double.parseDouble, first: "
                    + failures.subList(0, Math.min(5, failures.size())));
        }
    }

    @Test
    public void knownMisroundedValues() throws Exception {
        // Each of these was off by one ulp when composed as
        // (int + frac / 10^k) * Math.pow(10, exp)
        List<String> numbers = new ArrayList<>();
        numbers.add("4.561602440872777E-196");
        numbers.add("-2.555236359312043E-192");
        numbers.add("3.495965784945304E-63");
        numbers.add("-1.140756941392754E248");
        numbers.add("-5.433112990038016E103");
        assertAllExact(numbers);
    }

    @Test
    public void edgeCases() throws Exception {
        List<String> numbers = new ArrayList<>();
        for (String n : new String[] {
                "0.0", "-0.0", "0e0", "0.1", "0.2", "0.3", "0.30000000000000004", "1.5", "123.456",
                "1e22", "1e23", "1e-22", "1e-23", "1E+2", "123.456e-7",
                "9007199254740992.0", "9007199254740993.0", "9007199254740995.0",
                "123456789.123456789", "3.141592653589793", "2.718281828459045",
                "1.7976931348623157e308", "1.7976931348623159e308", "1e309", "1e400",
                "4.9e-324", "2.4703282292062327e-324", "2.4703282292062328e-324", "1e-400",
                "2.2250738585072014e-308", "2.2250738585072011e-308",
                "0.1234567890123456789", "0.000000000000000000001", "1e100000", "1e-100000" }) {
            numbers.add(n);
        }
        assertAllExact(numbers);
    }

    @Test
    public void uniformlyDistributedValues() throws Exception {
        Random rnd = new Random(1);
        List<String> numbers = new ArrayList<>();
        for (int i = 0; i < 30000; i++) {
            numbers.add(Double.toString(rnd.nextDouble() * 1000));
        }
        assertAllExact(numbers);
    }

    @Test
    public void randomBitPatterns() throws Exception {
        Random rnd = new Random(2);
        List<String> numbers = new ArrayList<>();
        while (numbers.size() < 30000) {
            double d = Double.longBitsToDouble(rnd.nextLong());
            if (!Double.isNaN(d) && !Double.isInfinite(d)) {
                numbers.add(Double.toString(d));
            }
        }
        assertAllExact(numbers);
    }

    private static final long[] POW10 = new long[19];

    static {
        POW10[0] = 1;
        for (int i = 1; i < POW10.length; i++) {
            POW10[i] = POW10[i - 1] * 10;
        }
    }

    @Test
    public void digitStringsAcrossTheWholeExponentRange() throws Exception {
        // 1 to 19 significant digits, exponents running from far below the
        // smallest subnormal to far above the largest double
        Random rnd = new Random(4);
        List<String> numbers = new ArrayList<>();
        for (int i = 0; i < 200000; i++) {
            int len = 1 + rnd.nextInt(19);
            long lo = POW10[len - 1];
            long hi = len == 19 ? Long.MAX_VALUE : POW10[len] - 1;
            long m = lo + Long.remainderUnsigned(rnd.nextLong(), hi - lo + 1);
            numbers.add(m + "e" + (rnd.nextInt(700) - 360));
        }
        assertAllExact(numbers);
    }

    @Test
    public void subnormalsAndTheEdgesOfTheNormalRange() throws Exception {
        Random rnd = new Random(5);
        List<String> numbers = new ArrayList<>();
        for (int i = 0; i < 50000; i++) {
            long fraction = rnd.nextLong() & 0x000FFFFFFFFFFFFFL;
            if (fraction != 0) {
                numbers.add(Double.toString(Double.longBitsToDouble(fraction)));
            }
            // just below the largest finite double, and either side of the smallest normal
            numbers.add(Double.toString(Double.longBitsToDouble(0x7FEFFFFFFFFFFFFFL - (rnd.nextLong() & 0xFFFFFFL))));
            numbers.add(Double.toString(Double.longBitsToDouble(0x0010000000000000L + rnd.nextInt(2000) - 1000)));
        }
        assertAllExact(numbers);
    }

    @Test
    public void exactHalfwaysRoundToEvenAndNeighboursDoNot() throws Exception {
        // Integers above 2^53 that sit exactly halfway between two doubles
        // (an odd multiple of half the spacing), and one either side of them
        Random rnd = new Random(6);
        List<String> numbers = new ArrayList<>();
        for (int k = 1; k <= 6; k++) {
            for (int i = 0; i < 5000; i++) {
                long m = (1L << 52) + (rnd.nextLong() & ((1L << 52) - 1));
                long halfway = (m << k) + (1L << (k - 1));
                for (long n = halfway - 1; n <= halfway + 1; n++) {
                    numbers.add(n + ".0");
                    numbers.add(n + "e0");
                    numbers.add((n * 10) + "e-1");
                }
            }
        }
        assertAllExact(numbers);
    }

    @Test
    public void moreThan19SignificantDigitsAreAtMostOneUlpOut() throws Exception {
        // Beyond 19 significant digits the digits cannot all be kept, so the
        // result is only guaranteed to be within one ulp of Double.parseDouble
        // (and is exact unless a rounding boundary falls inside the dropped
        // digits, which is rare)
        Random rnd = new Random(8);
        int total = 20000;
        int inexact = 0;
        for (int i = 0; i < total; i++) {
            int digits = 20 + rnd.nextInt(30);
            StringBuilder sb = new StringBuilder();
            sb.append((char) ('1' + rnd.nextInt(9)));
            for (int d = 1; d < digits; d++) {
                sb.append((char) ('0' + rnd.nextInt(10)));
            }
            int point = rnd.nextInt(digits + 1);
            sb.insert(point, point == 0 ? "0." : ".");
            if (sb.charAt(sb.length() - 1) == '.') {
                sb.append('0');
            }
            if (rnd.nextBoolean()) {
                sb.append('e').append(rnd.nextInt(600) - 300);
            }
            String number = sb.toString();
            double expected = Double.parseDouble(number);
            double actual = parse(number).doubleValue();
            long distance = Math.abs(Double.doubleToLongBits(expected) - Double.doubleToLongBits(actual));
            assertTrue(number + ": expected " + expected + " but got " + actual, distance <= 1);
            if (distance != 0) {
                inexact++;
            }
        }
        assertTrue(inexact + " of " + total + " were a ulp out; expected well under 2%", inexact < total / 50);
    }

    @Test
    public void droppedZeroDigitsLoseNothing() throws Exception {
        // Digits beyond what fits are only a problem if they are not zero
        assertEquals(1e30, parse("1000000000000000000000000000000.0").doubleValue(), 0.0);
        assertEquals(1e30, parse("1000000000000000000000000000000e0").doubleValue(), 0.0);
        assertEquals(123456789e25, parse("1234567890000000000000000000000000.000000000000000000").doubleValue(), 0.0);
    }

    @Test
    public void shortDecimalsWithExponent() throws Exception {
        Random rnd = new Random(3);
        List<String> numbers = new ArrayList<>();
        for (int i = 0; i < 30000; i++) {
            double d = Math.round(rnd.nextDouble() * 1e6) / 1e3 * Math.pow(10, rnd.nextInt(40) - 20);
            numbers.add(Double.toString(d));
        }
        assertAllExact(numbers);
    }
}
