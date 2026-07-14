/*
 * KeySymbolTable.java
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

import java.util.Arrays;

/**
 * A small open-addressing hash table mapping raw key bytes directly to the
 * decoded {@link String}, so that a repeated object key (the common case in
 * real-world JSON - a bounded set of field names repeated across many
 * objects) can skip decoding entirely on a cache hit.
 * <p>
 * This class is deliberately decode-agnostic: it only ever compares raw
 * bytes against previously-stored raw bytes. {@link JSONTokenizer} computes
 * the hash incrementally while scanning for a key's closing quote (so
 * hashing costs nothing extra), calls {@link #lookup} first, and only
 * decodes and calls {@link #put} on a miss.
 * <p>
 * Bounded by {@link #MAX_ENTRIES}: once reached, {@link #put} silently
 * becomes a no-op. This is purely a performance cliff, not a correctness
 * issue - it guards a long-lived instance (this table is owned by
 * {@link JSONParser} and stays warm across {@link JSONParser#reset()} calls,
 * i.e. across multiple parsed documents) against unbounded growth if fed a
 * pathological number of distinct "keys", whether accidental or adversarial.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class KeySymbolTable {

    /** FNV-1a offset basis and prime - see {@link #hashByte(int, byte)}. */
    private static final int FNV_OFFSET_BASIS = 0x811c9dc5;
    private static final int FNV_PRIME = 0x01000193;

    private static final int MAX_ENTRIES = 10_000;
    private static final int DEFAULT_CAPACITY = 64;

    private int[] hashes;
    private byte[][] keys;
    private String[] values;
    private int size;
    private int mask;

    KeySymbolTable() {
        allocate(DEFAULT_CAPACITY);
    }

    private void allocate(int capacity) {
        hashes = new int[capacity];
        keys = new byte[capacity][];
        values = new String[capacity];
        mask = capacity - 1;
    }

    /**
     * Returns the FNV-1a hash of {@code data} with one more byte folded in -
     * call once per byte with the running hash (start with
     * {@link #initialHash()}), in the same scan that already looks for a
     * key's closing quote.
     */
    static int hashByte(int hash, byte b) {
        return (hash ^ (b & 0xFF)) * FNV_PRIME;
    }

    static int initialHash() {
        return FNV_OFFSET_BASIS;
    }

    /**
     * Looks up the byte span {@code arr[off, off+len)} (with precomputed
     * {@code hash}). Returns the cached {@link String} on a hit (verified by
     * a byte-for-byte comparison, not just the hash), or {@code null} on a
     * miss.
     */
    String lookup(byte[] arr, int off, int len, int hash) {
        int idx = hash & mask;
        int probes = 0;
        while (values[idx] != null) {
            if (hashes[idx] == hash && byteEquals(keys[idx], arr, off, len)) {
                return values[idx];
            }
            idx = (idx + 1) & mask;
            if (++probes > mask) {
                return null;
            }
        }
        return null;
    }

    /**
     * Stores the byte span {@code arr[off, off+len)} (with precomputed
     * {@code hash}) as mapping to {@code value}, unless {@link #MAX_ENTRIES}
     * has already been reached (a no-op in that case - see the class
     * javadoc). Copies the bytes, since the source buffer's backing array is
     * reused/overwritten by later chunks of input.
     */
    void put(byte[] arr, int off, int len, int hash, String value) {
        if (size >= MAX_ENTRIES) {
            return;
        }
        if (size >= (mask + 1) - ((mask + 1) >> 2)) {
            grow();
        }
        int idx = hash & mask;
        while (values[idx] != null) {
            idx = (idx + 1) & mask;
        }
        hashes[idx] = hash;
        keys[idx] = Arrays.copyOfRange(arr, off, off + len);
        values[idx] = value;
        size++;
    }

    private void grow() {
        int[] oldHashes = hashes;
        byte[][] oldKeys = keys;
        String[] oldValues = values;
        allocate((mask + 1) * 2);
        for (int i = 0; i < oldValues.length; i++) {
            if (oldValues[i] != null) {
                int idx = oldHashes[i] & mask;
                while (values[idx] != null) {
                    idx = (idx + 1) & mask;
                }
                hashes[idx] = oldHashes[i];
                keys[idx] = oldKeys[i];
                values[idx] = oldValues[i];
            }
        }
    }

    private static boolean byteEquals(byte[] cached, byte[] arr, int off, int len) {
        if (cached.length != len) {
            return false;
        }
        for (int i = 0; i < len; i++) {
            if (cached[i] != arr[off + i]) {
                return false;
            }
        }
        return true;
    }
}
