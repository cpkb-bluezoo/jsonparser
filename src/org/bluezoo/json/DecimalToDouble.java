/*
 * DecimalToDouble.java
 * Copyright (C) 2025 Chris Burdess
 *
 * This file is part of jsonparser, a JSON parsing library for Java.
 * For more information please visit https://github.com/cpkb-bluezoo/jsonparser/
 *
 * jsonparser is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * jsonparser is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with jsonparser.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.bluezoo.json;

import java.math.BigInteger;

/**
 * Converts a decimal number given as an exact integer and a power of ten,
 * {@code digits * 10^e10}, to the nearest {@code double} (ties to even),
 * exactly as {@link Double#parseDouble} would, but without allocating.
 * <p>
 * Two strategies cover every input with a digit count that fits a
 * {@code long}:
 * <ul>
 * <li>Clinger's fast path: when the digits are at most 2^53 and the
 *     exponent is within +-22, both operands are exact doubles, so a single
 *     multiply or divide rounds once and is correct.</li>
 * <li>The Eisel-Lemire algorithm for everything else: a 64x128-bit
 *     multiplication by a truncated power of five. The truncated table is
 *     precise enough that the result is always decidable, with no fallback
 *     (Mushtak and Lemire, "Fast Number Parsing Without Fallback", 2023).</li>
 * </ul>
 * See also Lemire, "Number Parsing at a Gigabyte per Second" (2021). The
 * table of powers of five is generated on first use rather than stored, to
 * keep the class files small.
 * <p>
 * <strong>Acknowledgement.</strong> This is a Java implementation of the
 * algorithm as implemented in Daniel Lemire's fast_float library
 * (https://github.com/fastfloat/fast_float), whose structure it follows
 * closely: Clinger's fast path, the 128-bit table of powers of five and the way
 * the table is built, the refinement of the product with a second multiplication,
 * and the handling of halfway and subnormal results. fast_float is available
 * under the Apache License 2.0, the MIT license and the Boost Software License
 * 1.0. Nothing is inherited from its correctness, though: this port is checked
 * bit for bit against {@link Double#parseDouble} (see {@code NumberRoundingTest}).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class DecimalToDouble {

    private static final long MAX_EXACT_DIGITS = 1L << 53;
    private static final int MAX_EXACT_POW10 = 22;
    private static final double[] POW10 = {
        1e0, 1e1, 1e2, 1e3, 1e4, 1e5, 1e6, 1e7, 1e8, 1e9, 1e10, 1e11,
        1e12, 1e13, 1e14, 1e15, 1e16, 1e17, 1e18, 1e19, 1e20, 1e21, 1e22
    };

    private static final int SMALLEST_POWER_OF_FIVE = -342;
    private static final int LARGEST_POWER_OF_FIVE = 308;

    private static final int MANTISSA_EXPLICIT_BITS = 52;
    private static final int MINIMUM_EXPONENT = -1023;
    private static final int INFINITE_POWER = 0x7FF;
    private static final long INFINITY_BITS = 0x7FF0000000000000L;

    // Between these decimal exponents 5^q fits in 64 bits, so the product can
    // land exactly halfway between two doubles, which must round to even.
    private static final int MIN_EXPONENT_ROUND_TO_EVEN = -4;
    private static final int MAX_EXPONENT_ROUND_TO_EVEN = 23;

    private DecimalToDouble() {
    }

    /**
     * Returns the double nearest to {@code digits * 10^e10}, negated if
     * {@code negative}.
     *
     * @param negative whether the number is negative
     * @param digits the non-negative integer made up of all the digits
     * @param e10 the power of ten to scale it by
     */
    static double convert(boolean negative, long digits, int e10) {
        if (digits == 0) {
            return negative ? -0.0 : 0.0;
        }
        double value;
        if (digits <= MAX_EXACT_DIGITS && e10 >= -MAX_EXACT_POW10 && e10 <= MAX_EXACT_POW10) {
            value = e10 < 0 ? digits / POW10[-e10] : digits * POW10[e10];
        } else {
            value = Double.longBitsToDouble(eiselLemire(e10, digits));
        }
        return negative ? -value : value;
    }

    /** Returns the IEEE 754 bits of the double nearest to {@code w * 10^q}, for {@code w > 0}. */
    private static long eiselLemire(int q, long w) {
        if (q < SMALLEST_POWER_OF_FIVE) {
            return 0; // below half the smallest subnormal
        }
        if (q > LARGEST_POWER_OF_FIVE) {
            return INFINITY_BITS;
        }

        // Normalize so the most significant bit of w is set
        int lz = Long.numberOfLeadingZeros(w);
        w <<= lz;

        // 128-bit product of w and the truncated 128-bit value of 5^q
        long[] table = Powers.TABLE;
        int index = 2 * (q - SMALLEST_POWER_OF_FIVE);
        long hi = multiplyHigh(w, table[index]);
        long lo = w * table[index];
        // Needed only when the bits we will keep could still be disturbed
        // by a carry from the lower half of the table entry
        long precisionMask = -1L >>> (MANTISSA_EXPLICIT_BITS + 3);
        if ((hi & precisionMask) == precisionMask) {
            long secondHi = multiplyHigh(w, table[index + 1]);
            lo += secondHi;
            if (Long.compareUnsigned(secondHi, lo) > 0) {
                hi++;
            }
        }

        int upperBit = (int) (hi >>> 63);
        int shift = upperBit + 64 - MANTISSA_EXPLICIT_BITS - 3;
        long mantissa = hi >>> shift; // 54 or 55 bits, one more than needed plus a rounding bit
        // floor(log2(10^q)) + 63, approximated exactly over this range of q
        int power2 = (((152170 + 65536) * q) >> 16) + 63 + upperBit - lz - MINIMUM_EXPONENT;

        if (power2 <= 0) {
            // Subnormal, or just at the edge of normal after rounding
            if (-power2 + 1 >= 64) {
                return 0;
            }
            mantissa >>>= -power2 + 1;
            mantissa += mantissa & 1;
            mantissa >>>= 1;
            power2 = mantissa < (1L << MANTISSA_EXPLICIT_BITS) ? 0 : 1;
            // When power2 is 1 the implicit bit and the exponent's low bit coincide
            return mantissa | ((long) power2 << MANTISSA_EXPLICIT_BITS);
        }

        // Exactly halfway between two doubles: round to even, not up
        if (Long.compareUnsigned(lo, 1) <= 0
                && q >= MIN_EXPONENT_ROUND_TO_EVEN && q <= MAX_EXPONENT_ROUND_TO_EVEN
                && (mantissa & 3) == 1) {
            if ((mantissa << shift) == hi) {
                mantissa &= ~1L;
            }
        }

        mantissa += mantissa & 1;
        mantissa >>>= 1;
        if (mantissa >= (2L << MANTISSA_EXPLICIT_BITS)) {
            mantissa = 1L << MANTISSA_EXPLICIT_BITS;
            power2++;
        }
        mantissa &= ~(1L << MANTISSA_EXPLICIT_BITS);
        if (power2 >= INFINITE_POWER) {
            return INFINITY_BITS;
        }
        return mantissa | ((long) power2 << MANTISSA_EXPLICIT_BITS);
    }

    /** High 64 bits of the unsigned 128-bit product of two unsigned 64-bit values. */
    private static long multiplyHigh(long x, long y) {
        long x0 = x & 0xFFFFFFFFL;
        long x1 = x >>> 32;
        long y0 = y & 0xFFFFFFFFL;
        long y1 = y >>> 32;
        long p00 = x0 * y0;
        long p01 = x0 * y1;
        long p10 = x1 * y0;
        long p11 = x1 * y1;
        long middle = (p00 >>> 32) + (p01 & 0xFFFFFFFFL) + (p10 & 0xFFFFFFFFL);
        return p11 + (p01 >>> 32) + (p10 >>> 32) + (middle >>> 32);
    }

    /**
     * Holder for the table of 128-bit approximations of powers of five, so
     * it is only built when a number needs it. Entry {@code q} occupies
     * {@code TABLE[2 * (q + 342)]} (high 64 bits) and the next element (low
     * 64 bits), normalized so the top bit of the high word is set.
     * <p>
     * For {@code q >= 0} the entry is 5^q truncated to 128 bits. For
     * {@code q < 0} it is an upper approximation of 2^k / 5^-q (rounded up
     * and then truncated), which is what the no-fallback proof assumes.
     */
    private static final class Powers {

        static final long[] TABLE = build();

        private static long[] build() {
            long[] table = new long[2 * (LARGEST_POWER_OF_FIVE - SMALLEST_POWER_OF_FIVE + 1)];
            BigInteger five = BigInteger.valueOf(5);
            for (int q = SMALLEST_POWER_OF_FIVE; q <= LARGEST_POWER_OF_FIVE; q++) {
                BigInteger entry;
                if (q < 0) {
                    BigInteger power = five.pow(-q);
                    int z = power.bitLength(); // smallest z with 2^z >= 5^-q, as 5^-q is not a power of two
                    int b = q >= -27 ? z + 127 : 2 * z + 128;
                    entry = BigInteger.ONE.shiftLeft(b).divide(power).add(BigInteger.ONE);
                    int excess = entry.bitLength() - 128;
                    if (excess > 0) {
                        entry = entry.shiftRight(excess);
                    }
                } else {
                    entry = five.pow(q);
                    int length = entry.bitLength();
                    entry = length < 128 ? entry.shiftLeft(128 - length) : entry.shiftRight(length - 128);
                }
                int index = 2 * (q - SMALLEST_POWER_OF_FIVE);
                table[index] = entry.shiftRight(64).longValue();
                table[index + 1] = entry.longValue();
            }
            return table;
        }
    }
}
