/*
 * UTF8Decoder.java
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

import java.nio.ByteBuffer;
import java.nio.CharBuffer;

/**
 * Hand-rolled UTF-8 to UTF-16 decoder, replacing the generic
 * {@code java.nio.charset.CharsetDecoder} previously used by {@link JSONParser}.
 * <p>
 * Real JSON is dominated by long runs of plain ASCII: structural characters,
 * keys, numbers, punctuation, and (usually) most string content. The fast
 * path here is a tight widening copy loop for consecutive ASCII bytes,
 * falling back to explicit 2/3/4-byte sequence decoding only when a
 * non-ASCII byte is actually encountered.
 * <p>
 * This decoder is stateless: an incomplete trailing multi-byte sequence is
 * simply left unconsumed at the input buffer's position, and since the
 * caller is required to preserve that buffer's content (via
 * {@link ByteBuffer#compact()}) before the next call, no state needs to be
 * held between calls.
 * <p>
 * Callers must size the output {@link CharBuffer} with at least as much
 * remaining capacity as the input has remaining bytes - UTF-8 never
 * produces more UTF-16 chars than input bytes (1:1 is the worst case, for
 * ASCII) - so this decoder never needs to report or handle overflow.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class UTF8Decoder {

    private UTF8Decoder() {
    }

    /**
     * Decodes as many complete characters as possible from {@code in} into
     * {@code out}. An incomplete trailing multi-byte sequence is left
     * unconsumed in {@code in} (its position left at the sequence's start)
     * for the caller to preserve and retry once more data arrives - unless
     * {@code endOfInput} is true, in which case a truncated trailing
     * sequence is a hard error.
     *
     * @param in the input bytes, in read mode
     * @param out the output chars, in write mode, with enough remaining
     *        capacity for {@code in.remaining()} characters
     * @param endOfInput true if no further bytes will ever follow {@code in}
     * @throws JSONException if the input contains malformed UTF-8, or ends
     *         mid-sequence while {@code endOfInput} is true
     */
    static void decode(ByteBuffer in, CharBuffer out, boolean endOfInput) throws JSONException {
        if (in.hasArray() && out.hasArray()) {
            decodeArrays(in, out, endOfInput);
        } else {
            decodeGeneric(in, out, endOfInput);
        }
    }

    /**
     * Fast path: operates directly on the buffers' backing arrays, avoiding
     * per-element bounds-checked accessor calls. This is the path taken for
     * every buffer actually allocated by {@link JSONParser} today.
     */
    private static void decodeArrays(ByteBuffer in, CharBuffer out, boolean endOfInput) throws JSONException {
        byte[] ib = in.array();
        int iOff = in.arrayOffset();
        int p = iOff + in.position();
        int limit = iOff + in.limit();

        char[] ob = out.array();
        int oOff = out.arrayOffset();
        int q = oOff + out.position();

        while (p < limit) {
            byte b0 = ib[p];
            if (b0 >= 0) {
                // ASCII fast path: widen-copy a whole run in one tight loop.
                int start = p;
                do {
                    p++;
                } while (p < limit && ib[p] >= 0);
                int len = p - start;
                for (int i = 0; i < len; i++) {
                    ob[q + i] = (char) ib[start + i];
                }
                q += len;
                continue;
            }

            int need = sequenceLength(b0);
            if (limit - p < need) {
                if (endOfInput) {
                    throw new JSONException("Truncated UTF-8 sequence");
                }
                break;
            }

            int cp;
            switch (need) {
                case 2:
                    cp = decode2(b0, ib[p + 1]);
                    break;
                case 3:
                    cp = decode3(b0, ib[p + 1], ib[p + 2]);
                    break;
                default:
                    cp = decode4(b0, ib[p + 1], ib[p + 2], ib[p + 3]);
                    break;
            }
            q = putCodePoint(ob, q, cp);
            p += need;
        }

        in.position(p - iOff);
        out.position(q - oOff);
    }

    /**
     * Fallback path using the relative buffer API - correct for direct
     * buffers, which the public {@link JSONParser#receive(ByteBuffer)} API
     * could in principle be called with even though nothing in this
     * project constructs one.
     */
    private static void decodeGeneric(ByteBuffer in, CharBuffer out, boolean endOfInput) throws JSONException {
        while (in.hasRemaining()) {
            int pos = in.position();
            byte b0 = in.get(pos);
            if (b0 >= 0) {
                in.position(pos + 1);
                out.put((char) b0);
                continue;
            }

            int need = sequenceLength(b0);
            if (in.remaining() < need) {
                if (endOfInput) {
                    throw new JSONException("Truncated UTF-8 sequence");
                }
                return;
            }

            int cp;
            switch (need) {
                case 2:
                    cp = decode2(b0, in.get(pos + 1));
                    break;
                case 3:
                    cp = decode3(b0, in.get(pos + 1), in.get(pos + 2));
                    break;
                default:
                    cp = decode4(b0, in.get(pos + 1), in.get(pos + 2), in.get(pos + 3));
                    break;
            }
            in.position(pos + need);
            if (cp <= 0xFFFF) {
                out.put((char) cp);
            } else {
                cp -= 0x10000;
                out.put((char) (0xD800 + (cp >>> 10)));
                out.put((char) (0xDC00 + (cp & 0x3FF)));
            }
        }
    }

    /** Writes a decoded code point, expanding to a surrogate pair if needed. Returns the new output index. */
    private static int putCodePoint(char[] ob, int q, int cp) {
        if (cp <= 0xFFFF) {
            ob[q++] = (char) cp;
        } else {
            cp -= 0x10000;
            ob[q++] = (char) (0xD800 + (cp >>> 10));
            ob[q++] = (char) (0xDC00 + (cp & 0x3FF));
        }
        return q;
    }

    /**
     * Returns the total sequence length (2, 3, or 4) for a non-ASCII lead
     * byte, per the UTF-8 bit layout. Throws for any byte that can never
     * start a valid sequence: a stray continuation byte (0x80-0xBF), or the
     * obsolete/invalid 5-6 byte lead forms (0xF8-0xFF).
     */
    private static int sequenceLength(byte b0) throws JSONException {
        int b = b0 & 0xFF;
        if ((b & 0xE0) == 0xC0) {
            return 2;
        } else if ((b & 0xF0) == 0xE0) {
            return 3;
        } else if ((b & 0xF8) == 0xF0) {
            return 4;
        }
        throw new JSONException("Invalid UTF-8 lead byte: 0x" + Integer.toHexString(b));
    }

    private static int decode2(byte b0, int b1) throws JSONException {
        requireContinuation(b1);
        int cp = ((b0 & 0x1F) << 6) | (b1 & 0x3F);
        if (cp < 0x80) {
            throw new JSONException("Overlong UTF-8 sequence");
        }
        return cp;
    }

    private static int decode3(byte b0, int b1, int b2) throws JSONException {
        requireContinuation(b1);
        requireContinuation(b2);
        int cp = ((b0 & 0x0F) << 12) | ((b1 & 0x3F) << 6) | (b2 & 0x3F);
        if (cp < 0x800) {
            throw new JSONException("Overlong UTF-8 sequence");
        }
        if (cp >= 0xD800 && cp <= 0xDFFF) {
            throw new JSONException("UTF-8 encoded surrogate value: U+" + Integer.toHexString(cp));
        }
        return cp;
    }

    private static int decode4(byte b0, int b1, int b2, int b3) throws JSONException {
        requireContinuation(b1);
        requireContinuation(b2);
        requireContinuation(b3);
        int cp = ((b0 & 0x07) << 18) | ((b1 & 0x3F) << 12) | ((b2 & 0x3F) << 6) | (b3 & 0x3F);
        if (cp < 0x10000) {
            throw new JSONException("Overlong UTF-8 sequence");
        }
        if (cp > 0x10FFFF) {
            throw new JSONException("UTF-8 code point out of range: U+" + Integer.toHexString(cp));
        }
        return cp;
    }

    private static void requireContinuation(int b) throws JSONException {
        if ((b & 0xC0) != 0x80) {
            throw new JSONException("Invalid UTF-8 continuation byte");
        }
    }
}
