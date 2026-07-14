/*
 * JSONTokenizer.java
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
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;

/**
 * A JSON tokenizer that operates on ByteBuffer for streaming parsing.
 * This follows the rules given in ECMA 404 / RFC 8259.
 * <p>
 * The tokenizer calls JSONContentHandler methods directly as tokens are recognized.
 * <p>
 * JSON's structural grammar - braces, brackets, colon, comma, whitespace,
 * digits, {@code -+.eE}, the literals {@code true}/{@code false}/{@code null},
 * and the string delimiters {@code "} and {@code \} - are all ASCII byte
 * values. UTF-8 is self-synchronizing: none of those byte values can ever
 * appear as a continuation or lead byte of a multi-byte sequence. That means
 * all of this can be tokenized directly against raw bytes with no decoding
 * at all - full UTF-8 decoding is only ever needed for the actual content of
 * a string value/key, and only for the parts of it that are non-ASCII.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class JSONTokenizer implements JSONLocator {

    private final JSONContentHandler handler;
    private StringBuilder escapeBuilder; // Lazily allocated for strings with escapes
    private boolean needsWhitespace; // Whether handler needs whitespace events

    private int lineNumber = 1;
    private int columnNumber = 0;
    private boolean closed = false;

    // Track context for key vs value
    private Deque<Context> contextStack = new ArrayDeque<>();
    private State state = State.EXPECT_VALUE;  // Root level expects a value

    // Minimal parsing state for structural validation
    Deque<Token> depthStack = new ArrayDeque<>();  // Track nesting depth
    boolean seenAnyToken = false;  // Track if we've seen any non-whitespace token
    boolean afterComma = false;  // Track if we just saw a comma

    // Hash-first key interning (see KeySymbolTable) - owned by JSONParser so
    // it stays warm across multiple documents parsed by the same instance.
    private final KeySymbolTable keySymbolTable;

    // Adaptive bail-out: a document with high key cardinality and little/no
    // repetition (e.g. a single flat object with 100,000 distinct field
    // names) gets zero benefit from interning but would otherwise still pay
    // for a hash computation, a failed lookup, and an insert on every single
    // key. If the first KEY_INTERN_DISABLE_THRESHOLD attempts produce no
    // hits at all, stop trying for the rest of this document (a normal
    // repeating-schema document shows repeats well before this many distinct
    // keys have gone by). Scoped to this tokenizer (one document) - a
    // high-cardinality document doesn't poison interning for a later,
    // repetition-heavy one parsed by the same reused JSONParser.
    private static final int KEY_INTERN_DISABLE_THRESHOLD = 64;
    private int keyInternAttempts;
    private int keyInternHits;
    private boolean keyInternDisabled;

    // Opt-in strict duplicate-key detection (default off - see
    // JSONParser#setRejectDuplicateKeys). One set per currently-open object,
    // pushed/popped in lockstep with contextStack; null entirely when the
    // feature is disabled, so there's no cost unless opted in.
    private final boolean rejectDuplicateKeys;
    private Deque<HashSet<String>> duplicateKeyStack;

    // Resource-exhaustion guards (see ParserLimits) - shared with JSONParser
    // and (for maxNestingDepth/maxNumberLength/maxStringLength/maxNameLength)
    // enforced here; tokenCount is this tokenizer's own running count for
    // the current document (a tokenizer is recreated per document).
    private final ParserLimits limits;
    private long tokenCount;

    private enum Context {
        ARRAY, OBJECT
    }

    private enum State {
        EXPECT_VALUE,      // Expecting any value
        EXPECT_KEY,        // Expecting a key (string in object)
        EXPECT_COLON,      // Expecting colon after key
        AFTER_VALUE        // After value, expecting comma or close bracket
    }

    /**
     * Creates a tokenizer with the given handler.
     *
     * @param handler the content handler to call with parsing events
     * @param keySymbolTable the key interning table (shared/reused across
     *        documents parsed by the same {@link JSONParser})
     * @param rejectDuplicateKeys whether to throw on a repeated key within
     *        the same object
     * @param limits resource-exhaustion guard limits (shared with the
     *        owning {@link JSONParser})
     */
    JSONTokenizer(JSONContentHandler handler, KeySymbolTable keySymbolTable, boolean rejectDuplicateKeys,
                  ParserLimits limits) {
        this.handler = handler;
        this.keySymbolTable = keySymbolTable;
        this.rejectDuplicateKeys = rejectDuplicateKeys;
        this.limits = limits;
        if (rejectDuplicateKeys) {
            this.duplicateKeyStack = new ArrayDeque<>();
        }

        if (handler != null) {
            handler.setLocator(this);
            this.needsWhitespace = handler.needsWhitespace();
        }
    }

    @Override
    public int getLineNumber() {
        return lineNumber;
    }

    @Override
    public int getColumnNumber() {
        return columnNumber;
    }

    /**
     * Signal that the input stream is closed.
     */
    void setClosed(boolean closed) {
        this.closed = closed;
    }

    boolean isClosed() {
        return closed;
    }

    /**
     * Process tokens from the ByteBuffer, calling handler methods.
     * Buffer must be in read mode (position at start, limit at end).
     * Returns when buffer is exhausted or an incomplete token is encountered.
     * Buffer position will be updated to reflect consumed bytes.
     */
    void receive(ByteBuffer data) throws JSONException {
        while (data.hasRemaining()) {
            // Save position for backtracking
            int saveLineNumber = lineNumber;
            int saveColumnNumber = columnNumber;
            int startPos = data.position();
            data.mark();

            byte b = data.get();

            // Check if this is whitespace (which handles its own line/column tracking)
            boolean isWhitespace = (b == ' ' || b == '\n' || b == '\t' || b == '\r');

            boolean processed = processToken(b, data);

            if (!processed) {
                // Incomplete token - backtrack
                data.reset();
                lineNumber = saveLineNumber;
                columnNumber = saveColumnNumber;
                return;
            }

            // Update column number based on bytes consumed
            // (but not for whitespace, which handles its own line/column tracking,
            // and not for strings, which handle their own column tracking since a
            // string's byte length and its UTF-16 source-character count can differ
            // once multi-byte UTF-8 content or escape sequences are involved)
            if (!isWhitespace && b != '"') {
                int bytesConsumed = data.position() - startPos;
                columnNumber += bytesConsumed;
            }
        }
    }

    /**
     * Process a single token starting with byte b.
     * Returns true if token was complete, false if need more data.
     */
    private boolean processToken(byte b, ByteBuffer data) throws JSONException {
        switch (b) {
            case '"':
                // String can be a key or a value
                if (state == State.EXPECT_COLON || state == State.AFTER_VALUE) {
                    throw new JSONException("Unexpected string");
                }
                boolean isKey = (state == State.EXPECT_KEY);
                if (!processString(data, isKey)) {
                    return false;
                }
                seenAnyToken = true;
                if (limits.maxTokenCount > 0 && ++tokenCount > limits.maxTokenCount) {
                    throw new JSONException("Maximum token count exceeded: " + limits.maxTokenCount);
                }
                afterComma = false;
                if (isKey) {
                    state = State.EXPECT_COLON;
                } else {
                    state = State.AFTER_VALUE;
                }
                return true;

            case ',':
                if (state != State.AFTER_VALUE) {
                    throw new JSONException("Unexpected ','");
                }
                if (contextStack.isEmpty()) {
                    throw new JSONException("Unexpected comma at root level");
                }
                seenAnyToken = true;
                if (limits.maxTokenCount > 0 && ++tokenCount > limits.maxTokenCount) {
                    throw new JSONException("Maximum token count exceeded: " + limits.maxTokenCount);
                }
                afterComma = true;
                // After comma: expect key in object, value in array
                state = (contextStack.peek() == Context.OBJECT) ? State.EXPECT_KEY : State.EXPECT_VALUE;
                return true;

            case ':':
                if (state != State.EXPECT_COLON) {
                    throw new JSONException("Unexpected ':'");
                }
                seenAnyToken = true;
                if (limits.maxTokenCount > 0 && ++tokenCount > limits.maxTokenCount) {
                    throw new JSONException("Maximum token count exceeded: " + limits.maxTokenCount);
                }
                afterComma = false;
                state = State.EXPECT_VALUE;
                return true;

            case '{':
                if (state != State.EXPECT_VALUE) {
                    throw new JSONException("Unexpected '{'");
                }
                if (limits.maxNestingDepth > 0 && depthStack.size() >= limits.maxNestingDepth) {
                    throw new JSONException("Maximum nesting depth exceeded: " + limits.maxNestingDepth);
                }
                handler.startObject();
                contextStack.push(Context.OBJECT);
                depthStack.push(Token.START_OBJECT);
                if (rejectDuplicateKeys) {
                    duplicateKeyStack.push(new HashSet<>());
                }
                seenAnyToken = true;
                if (limits.maxTokenCount > 0 && ++tokenCount > limits.maxTokenCount) {
                    throw new JSONException("Maximum token count exceeded: " + limits.maxTokenCount);
                }
                afterComma = false;
                state = State.EXPECT_KEY;  // Objects start expecting key (or '}')
                return true;

            case '}':
                if (contextStack.isEmpty() || contextStack.peek() != Context.OBJECT) {
                    throw new JSONException("Unexpected '}'");
                }
                // Can close empty object (EXPECT_KEY) or after a value (AFTER_VALUE)
                if (state != State.EXPECT_KEY && state != State.AFTER_VALUE) {
                    throw new JSONException("Unexpected '}'");
                }
                // Cannot close after comma
                if (afterComma) {
                    throw new JSONException("Trailing comma before '}'");
                }
                handler.endObject();
                contextStack.pop();
                depthStack.pop();
                if (rejectDuplicateKeys) {
                    duplicateKeyStack.pop();
                }
                seenAnyToken = true;
                if (limits.maxTokenCount > 0 && ++tokenCount > limits.maxTokenCount) {
                    throw new JSONException("Maximum token count exceeded: " + limits.maxTokenCount);
                }
                afterComma = false;
                state = State.AFTER_VALUE;
                return true;

            case '[':
                if (state != State.EXPECT_VALUE) {
                    throw new JSONException("Unexpected '['");
                }
                if (limits.maxNestingDepth > 0 && depthStack.size() >= limits.maxNestingDepth) {
                    throw new JSONException("Maximum nesting depth exceeded: " + limits.maxNestingDepth);
                }
                handler.startArray();
                contextStack.push(Context.ARRAY);
                depthStack.push(Token.START_ARRAY);
                seenAnyToken = true;
                if (limits.maxTokenCount > 0 && ++tokenCount > limits.maxTokenCount) {
                    throw new JSONException("Maximum token count exceeded: " + limits.maxTokenCount);
                }
                afterComma = false;
                state = State.EXPECT_VALUE;  // Arrays start expecting value (or ']')
                return true;

            case ']':
                if (contextStack.isEmpty() || contextStack.peek() != Context.ARRAY) {
                    throw new JSONException("Unexpected ']'");
                }
                // Can close empty array (EXPECT_VALUE) or after a value (AFTER_VALUE)
                if (state != State.EXPECT_VALUE && state != State.AFTER_VALUE) {
                    throw new JSONException("Unexpected ']'");
                }
                // Cannot close after comma
                if (afterComma) {
                    throw new JSONException("Trailing comma before ']'");
                }
                handler.endArray();
                contextStack.pop();
                depthStack.pop();
                seenAnyToken = true;
                if (limits.maxTokenCount > 0 && ++tokenCount > limits.maxTokenCount) {
                    throw new JSONException("Maximum token count exceeded: " + limits.maxTokenCount);
                }
                afterComma = false;
                state = State.AFTER_VALUE;
                return true;

            case ' ':
            case '\n':
            case '\t':
            case '\r':
                return processWhitespace(b, data);

            case 't': // true
                if (state != State.EXPECT_VALUE) {
                    throw new JSONException("Unexpected 'true'");
                }
                if (!processLiteral(data, "rue", true)) {
                    return false;
                }
                seenAnyToken = true;
                if (limits.maxTokenCount > 0 && ++tokenCount > limits.maxTokenCount) {
                    throw new JSONException("Maximum token count exceeded: " + limits.maxTokenCount);
                }
                afterComma = false;
                state = State.AFTER_VALUE;
                return true;

            case 'f': // false
                if (state != State.EXPECT_VALUE) {
                    throw new JSONException("Unexpected 'false'");
                }
                if (!processLiteral(data, "alse", false)) {
                    return false;
                }
                seenAnyToken = true;
                if (limits.maxTokenCount > 0 && ++tokenCount > limits.maxTokenCount) {
                    throw new JSONException("Maximum token count exceeded: " + limits.maxTokenCount);
                }
                afterComma = false;
                state = State.AFTER_VALUE;
                return true;

            case 'n': // null
                if (state != State.EXPECT_VALUE) {
                    throw new JSONException("Unexpected 'null'");
                }
                if (!processLiteral(data, "ull", null)) {
                    return false;
                }
                seenAnyToken = true;
                if (limits.maxTokenCount > 0 && ++tokenCount > limits.maxTokenCount) {
                    throw new JSONException("Maximum token count exceeded: " + limits.maxTokenCount);
                }
                afterComma = false;
                state = State.AFTER_VALUE;
                return true;

            default:
                // number
                if (b == '-' || (b >= '0' && b <= '9')) {
                    if (state != State.EXPECT_VALUE) {
                        throw new JSONException("Unexpected number");
                    }
                    if (!processNumber(b, data)) {
                        return false;
                    }
                    seenAnyToken = true;
                    if (limits.maxTokenCount > 0 && ++tokenCount > limits.maxTokenCount) {
                        throw new JSONException("Maximum token count exceeded: " + limits.maxTokenCount);
                    }
                    afterComma = false;
                    state = State.AFTER_VALUE;
                    return true;
                }
                throw new JSONException("Unexpected character: " + (char) (b & 0xFF));
        }
    }

    /**
     * Process a string token.
     * Opening quote already consumed.
     * <p>
     * Scanning for the closing quote, backslash, and control characters is
     * done directly against raw bytes - safe per the class-level UTF-8
     * self-synchronization note, since none of those bytes can appear
     * inside a multi-byte sequence. Only the actual content between
     * delimiters is ever decoded, via {@link #decodeSpan}, and only once
     * its extent is fully known.
     */
    private boolean processString(ByteBuffer data, boolean isKey) throws JSONException {
        if (data.hasArray()) {
            return processStringArray(data, isKey);
        }
        return processStringGeneric(data, isKey);
    }

    /**
     * Array-backed fast path for {@link #processString}: scans for the
     * closing quote/escapes/control-characters via direct indexing into the
     * buffer's backing array instead of {@code ByteBuffer}'s bounds-checked
     * relative {@code get()} - the same trade {@link UTF8Decoder} already
     * makes. {@code data}'s position is kept in sync at every point another
     * method ({@link #decodeSpan}, {@link #processEscapeSequence}) needs to
     * read from it, and on the success return; it does not need to be kept
     * in sync on a "need more data" return, since {@link #receive} discards
     * it via {@code reset()} unconditionally in that case.
     */
    private boolean processStringArray(ByteBuffer data, boolean isKey) throws JSONException {
        byte[] arr = data.array();
        int off = data.arrayOffset();
        int startPos = data.position(); // buffer-relative, just after opening quote
        int arrLimit = off + data.limit();
        int p = off + startPos; // array-absolute scan cursor

        StringBuilder builder = null;
        int spanStart = startPos; // buffer-relative
        int srcCharCount = 0;
        boolean sawNonAscii = false;
        // Only meaningful (and only computed) for keys with no escapes and
        // while interning hasn't been adaptively disabled for this document
        // - see KeySymbolTable and KEY_INTERN_DISABLE_THRESHOLD. Excludes
        // the terminating quote: updated only for bytes confirmed to be
        // content, below.
        boolean tryIntern = isKey && !keyInternDisabled;
        int keyHash = tryIntern ? KeySymbolTable.initialHash() : 0;

        int lengthLimit = isKey ? limits.maxNameLength : limits.maxStringLength;

        while (p < arrLimit) {
            int bytePos = p - off; // buffer-relative position of this byte
            byte b = arr[p];
            p++;

            // Raw byte position is a conservative (never under-protective)
            // proxy for decoded character count - checked here, mid-scan,
            // so a pathologically long unescaped string is rejected without
            // needing to be fully buffered first.
            if (lengthLimit > 0 && bytePos - startPos > lengthLimit) {
                throw new JSONException((isKey ? "Maximum key length exceeded: " : "Maximum string length exceeded: ")
                        + lengthLimit);
            }

            if (b == '"') {
                data.position(p - off);
                String value;
                int endPos = bytePos;
                if (builder == null) {
                    if (tryIntern) {
                        int keyOff = off + startPos;
                        int keyLen = endPos - startPos;
                        value = keySymbolTable.lookup(arr, keyOff, keyLen, keyHash);
                        keyInternAttempts++;
                        if (value != null) {
                            keyInternHits++;
                        } else {
                            value = decodeSpan(data, startPos, endPos, sawNonAscii);
                            keySymbolTable.put(arr, keyOff, keyLen, keyHash, value);
                        }
                        if (keyInternHits == 0 && keyInternAttempts >= KEY_INTERN_DISABLE_THRESHOLD) {
                            keyInternDisabled = true;
                        }
                    } else {
                        value = decodeSpan(data, startPos, endPos, sawNonAscii);
                    }
                    srcCharCount = value.length();
                } else {
                    if (endPos > spanStart) {
                        String tail = decodeSpan(data, spanStart, endPos, sawNonAscii);
                        builder.append(tail);
                        srcCharCount += tail.length();
                    }
                    value = builder.toString();
                }

                if (isKey) {
                    if (rejectDuplicateKeys && !duplicateKeyStack.peek().add(value)) {
                        throw new JSONException("Duplicate key: " + value);
                    }
                    handler.key(value);
                } else {
                    handler.stringValue(value);
                }
                columnNumber += 2 + srcCharCount;
                return true;
            }

            if (b < 0) {
                sawNonAscii = true;
            }
            if (tryIntern) {
                keyHash = KeySymbolTable.hashByte(keyHash, b);
            }

            if (b == '\\') {
                if (builder == null) {
                    if (escapeBuilder == null) {
                        escapeBuilder = new StringBuilder(256);
                    } else if (escapeBuilder.capacity() > 16384) {
                        escapeBuilder = new StringBuilder(256);
                    } else {
                        escapeBuilder.setLength(0);
                    }
                    builder = escapeBuilder;
                    spanStart = startPos;
                }

                if (bytePos > spanStart) {
                    String span = decodeSpan(data, spanStart, bytePos, sawNonAscii);
                    builder.append(span);
                    srcCharCount += span.length();
                }

                // processEscapeSequence reads via the relative ByteBuffer API,
                // so data's position must be accurate before calling it.
                data.position(p - off);
                int escapeStart = bytePos;
                Character escaped = processEscapeSequence(data);
                if (escaped == null) {
                    return false; // Need more data
                }
                builder.append(escaped.charValue());
                srcCharCount += data.position() - escapeStart;
                spanStart = data.position();
                p = off + data.position(); // resync array cursor past the escape
            } else if (b >= 0 && b < 0x20) {
                throw new JSONException("Unescaped control character in string");
            }
        }

        if (!closed) {
            return false; // Need more data
        }

        throw new JSONException("Unclosed string");
    }

    /**
     * Fallback path for {@link #processString} using the relative buffer
     * API - correct for direct buffers, which the public
     * {@link JSONParser#receive(ByteBuffer)} API could in principle be
     * called with even though nothing in this project constructs one.
     */
    private boolean processStringGeneric(ByteBuffer data, boolean isKey) throws JSONException {
        int startPos = data.position(); // just after opening quote
        StringBuilder builder = null;
        int spanStart = startPos; // start of the not-yet-flushed raw span
        int srcCharCount = 0; // UTF-16 source units consumed, for locator column tracking
        // Tracked as a side effect of the scan below (which already visits
        // every byte anyway), so decodeSpan doesn't need its own separate
        // scan of the same bytes just to decide ASCII-vs-decode strategy.
        boolean sawNonAscii = false;
        int lengthLimit = isKey ? limits.maxNameLength : limits.maxStringLength;

        while (data.hasRemaining()) {
            int bytePos = data.position();
            byte b = data.get();
            if (b < 0) {
                sawNonAscii = true;
            }

            // See processStringArray for why this checks raw byte position.
            if (lengthLimit > 0 && bytePos - startPos > lengthLimit) {
                throw new JSONException((isKey ? "Maximum key length exceeded: " : "Maximum string length exceeded: ")
                        + lengthLimit);
            }

            if (b == '"') {
                // Complete string - extract value
                String value;
                int endPos = bytePos; // before closing quote
                if (builder == null) {
                    // No escapes - decode the whole span directly
                    value = decodeSpan(data, startPos, endPos, sawNonAscii);
                    srcCharCount = value.length();
                } else {
                    if (endPos > spanStart) {
                        String tail = decodeSpan(data, spanStart, endPos, sawNonAscii);
                        builder.append(tail);
                        srcCharCount += tail.length();
                    }
                    value = builder.toString();
                }

                // Call appropriate handler method
                if (isKey) {
                    if (rejectDuplicateKeys && !duplicateKeyStack.peek().add(value)) {
                        throw new JSONException("Duplicate key: " + value);
                    }
                    handler.key(value);
                } else {
                    handler.stringValue(value);
                }
                columnNumber += 2 + srcCharCount; // +2 for the opening/closing quotes
                return true;
            } else if (b == '\\') {
                // Hit an escape - need to use StringBuilder from now on
                if (builder == null) {
                    // Allocate builder with smart capacity management
                    if (escapeBuilder == null) {
                        escapeBuilder = new StringBuilder(256);
                    } else if (escapeBuilder.capacity() > 16384) {
                        // StringBuilder grew too large, reallocate to avoid keeping large buffer
                        escapeBuilder = new StringBuilder(256);
                    } else {
                        escapeBuilder.setLength(0);
                    }
                    builder = escapeBuilder;
                    spanStart = startPos;
                }

                // Flush any raw span accumulated before this backslash
                if (bytePos > spanStart) {
                    String span = decodeSpan(data, spanStart, bytePos, sawNonAscii);
                    builder.append(span);
                    srcCharCount += span.length();
                }

                // Process escape sequence (backslash already consumed)
                int escapeStart = bytePos; // position of the backslash
                Character escaped = processEscapeSequence(data);
                if (escaped == null) {
                    return false; // Need more data
                }
                builder.append(escaped.charValue());
                srcCharCount += data.position() - escapeStart; // raw escape bytes are always ASCII
                spanStart = data.position();
            } else if (b >= 0 && b < 0x20) {
                // b >= 0 excludes bytes with the high bit set (0x80-0xFF), which
                // as a signed byte are negative but are valid UTF-8 continuation/
                // lead bytes, not control characters.
                throw new JSONException("Unescaped control character in string");
            }
            // Otherwise: regular byte (including bytes of a multi-byte UTF-8
            // sequence), part of the current span - no action needed here,
            // it will be picked up when the span is next flushed.
        }

        // Ran out of buffer
        if (!closed) {
            return false; // Need more data
        }

        throw new JSONException("Unclosed string");
    }

    /**
     * Decodes the byte span {@code [start, end)} of {@code data} to a
     * String. Uses a cheap 1:1 byte-to-char conversion if the span is pure
     * ASCII (the common case), falling back to a strict UTF-8 decode
     * (rejecting malformed sequences, matching the previous behavior) only
     * if it isn't. {@code sawNonAscii} is supplied by the caller (which
     * already scans these same bytes once to find string boundaries/escapes)
     * rather than re-scanned here.
     */
    private static String decodeSpan(ByteBuffer data, int start, int end, boolean sawNonAscii) throws JSONException {
        int len = end - start;
        if (len == 0) {
            return "";
        }
        boolean ascii = !sawNonAscii;
        if (ascii) {
            if (data.hasArray()) {
                return new String(data.array(), data.arrayOffset() + start, len, StandardCharsets.ISO_8859_1);
            }
            char[] chars = new char[len];
            for (int i = 0; i < len; i++) {
                chars[i] = (char) data.get(start + i);
            }
            return new String(chars);
        }
        ByteBuffer slice = data.duplicate();
        slice.limit(end).position(start);
        CharBuffer out = CharBuffer.allocate(len); // len bytes >= len chars, always
        UTF8Decoder.decode(slice, out, true);
        out.flip();
        return out.toString();
    }

    /**
     * Process an escape sequence.
     * Backslash already consumed.
     */
    private Character processEscapeSequence(ByteBuffer data) throws JSONException {
        if (!data.hasRemaining()) {
            if (!closed) {
                return null; // Need more data
            }
            throw new JSONException("Unexpected EOF in escape sequence");
        }

        byte b = data.get();

        switch (b) {
            case '"':
                return '"';
            case '\\':
                return '\\';
            case '/':
                return '/';
            case 'b':
                return '\b';
            case 'f':
                return '\f';
            case 'n':
                return '\n';
            case 'r':
                return '\r';
            case 't':
                return '\t';
            case 'u':
                return processUnicodeEscape(data);
            default:
                throw new JSONException("Invalid escape sequence: \\" + (char) (b & 0xFF));
        }
    }

    /**
     * Process a Unicode escape sequence \\uXXXX.
     * The \\u already consumed.
     */
    private Character processUnicodeEscape(ByteBuffer data) throws JSONException {
        int value = 0;

        for (int i = 0; i < 4; i++) {
            if (!data.hasRemaining()) {
                if (!closed) {
                    return null; // Need more data
                }
                throw new JSONException("Incomplete Unicode escape");
            }

            byte b = data.get();

            int digit = unhex(b);
            value = (value << 4) | digit;
        }

        return (char) value;
    }

    /**
     * Convert a hex digit to its numeric value.
     */
    private int unhex(byte b) throws JSONException {
        if (b >= '0' && b <= '9') {
            return b - '0';
        } else if (b >= 'A' && b <= 'F') {
            return b - 'A' + 10;
        } else if (b >= 'a' && b <= 'f') {
            return b - 'a' + 10;
        }
        throw new JSONException("Invalid hex digit: " + (char) (b & 0xFF));
    }

    /**
     * Process whitespace starting with first byte.
     */
    private boolean processWhitespace(byte first, ByteBuffer data) throws JSONException {
        // Update line/column for first character
        if (first == '\n') {
            lineNumber++;
            columnNumber = 0;
        } else if (first == '\r') {
            lineNumber++;
            columnNumber = 0;
        }

        if (!needsWhitespace) {
            // Handler doesn't need whitespace - just consume it
            while (data.hasRemaining()) {
                int savedPos = data.position();
                byte b = data.get();

                if (b == ' ' || b == '\t') {
                    columnNumber++;
                } else if (b == '\n') {
                    lineNumber++;
                    columnNumber = 0;
                } else if (b == '\r') {
                    lineNumber++;
                    columnNumber = 0;
                } else {
                    // Not whitespace - backtrack
                    data.position(savedPos);
                    break;
                }
            }
            return true;
        }

        // Handler needs whitespace - extract string
        int startPos = data.position() - 1; // Before first byte

        while (data.hasRemaining()) {
            int savedPos = data.position();
            byte b = data.get();

            if (b == ' ' || b == '\t') {
                columnNumber++;
            } else if (b == '\n') {
                lineNumber++;
                columnNumber = 0;
            } else if (b == '\r') {
                lineNumber++;
                columnNumber = 0;
            } else {
                // Not whitespace - backtrack
                data.position(savedPos);
                break;
            }
        }

        // Whitespace is always ASCII, so this is always the cheap fast path
        String ws = decodeSpan(data, startPos, data.position(), false);
        handler.whitespace(ws);
        return true;
    }

    /**
     * Process a literal (true, false, null).
     * First character already consumed, remaining contains rest of literal.
     */
    private boolean processLiteral(ByteBuffer data, String remaining, Boolean boolValue) throws JSONException {
        if (data.hasArray()) {
            return processLiteralArray(data, remaining, boolValue);
        }
        return processLiteralGeneric(data, remaining, boolValue);
    }

    /**
     * Array-backed fast path for {@link #processLiteral} - every value token
     * in a literal-dominated document (e.g. a large array of booleans/nulls)
     * goes through here, so avoiding a per-character bounds-checked
     * {@code ByteBuffer.get()} matters as much as it did for
     * {@link #processStringArray}.
     */
    private boolean processLiteralArray(ByteBuffer data, String remaining, Boolean boolValue) throws JSONException {
        byte[] arr = data.array();
        int off = data.arrayOffset();
        int pos = data.position();
        int limit = data.limit();
        int len = remaining.length();
        int avail = limit - pos;
        int checkLen = Math.min(avail, len);

        // Validate whatever bytes are available, in order - matches the
        // generic path's byte-by-byte validation (a mismatch is reported at
        // the same point either way, regardless of how many more bytes
        // happen to be buffered beyond it).
        for (int i = 0; i < checkLen; i++) {
            if (arr[off + pos + i] != remaining.charAt(i)) {
                throw new JSONException("Invalid literal");
            }
        }

        if (avail < len) {
            if (!closed) {
                return false; // Need more data
            }
            throw new JSONException("Incomplete literal");
        }

        data.position(pos + len);

        if (boolValue != null) {
            handler.booleanValue(boolValue);
        } else {
            handler.nullValue();
        }
        return true;
    }

    /**
     * Fallback path for {@link #processLiteral} using the relative buffer
     * API - correct for direct buffers (see {@link #processStringGeneric}).
     */
    private boolean processLiteralGeneric(ByteBuffer data, String remaining, Boolean boolValue) throws JSONException {
        // First character already consumed and counted
        for (int i = 0; i < remaining.length(); i++) {
            if (!data.hasRemaining()) {
                if (!closed) {
                    return false; // Need more data
                }
                throw new JSONException("Incomplete literal");
            }

            byte b = data.get();
            if (b != remaining.charAt(i)) {
                throw new JSONException("Invalid literal");
            }
        }

        // Call appropriate handler method
        if (boolValue != null) {
            handler.booleanValue(boolValue);
        } else {
            handler.nullValue();
        }
        return true;
    }

    /**
     * Process a number token.
     * First byte already consumed.
     * DO NOT call mark() - the mark is set by receive() at the start of the token.
     * <p>
     * Grammar validation/boundary-scanning is unchanged from before (still
     * byte-by-byte); what's new is that the integer, fractional, and
     * exponent digits are accumulated into {@code long}/{@code int}
     * counters as they're scanned, so the numeric value can usually be
     * composed directly with no further scan or {@code String} allocation -
     * falling back to a full {@code String} extraction only in the (rare)
     * case of an overflowing number of significant digits.
     */
    private boolean processNumber(byte first, ByteBuffer data) throws JSONException {
        int startPos = data.position() - 1; // before first byte

        boolean negative = (first == '-');

        long ival = 0;
        boolean intOverflow = false;

        byte c = first;
        if (negative) {
            if (!data.hasRemaining()) {
                if (!closed) {
                    return false; // Need more data
                }
                throw new JSONException("Invalid number: just '-'");
            }
            c = data.get();
        }

        // Integer part
        if (c == '0') {
            // After 0, next must not be a digit (no leading zeros)
            if (data.hasRemaining()) {
                int savedPos = data.position();
                byte next = data.get();
                if (next >= '0' && next <= '9') {
                    throw new JSONException("Numbers cannot have leading zeros");
                }
                // Backtrack - we'll re-read for fractional/exponent check
                data.position(savedPos);
            } else if (!closed) {
                return false; // Might be 0.5 coming
            }
        } else if (c >= '1' && c <= '9') {
            ival = c - '0';
            // Consume remaining digits
            boolean sawNonDigit = false;
            while (data.hasRemaining()) {
                int savedPos = data.position();
                byte d = data.get();
                if (d >= '0' && d <= '9') {
                    if (!intOverflow) {
                        int digit = d - '0';
                        if (ival > (Long.MAX_VALUE - digit) / 10) {
                            intOverflow = true;
                        } else {
                            ival = ival * 10 + digit;
                        }
                    }
                } else {
                    // Non-digit - backtrack
                    data.position(savedPos);
                    sawNonDigit = true;
                    break;
                }
            }

            if (!sawNonDigit && !closed) {
                return false; // Might be more digits or . or e coming
            }
        } else {
            throw new JSONException("Invalid number format");
        }

        boolean hasFraction = false;
        long dval = 0;
        long mul = 1;
        boolean fracOverflow = false;

        // Optional fractional part
        if (data.hasRemaining()) {
            int savedPos = data.position();
            byte c2 = data.get();
            if (c2 == '.') {
                hasFraction = true;

                // Must have at least one digit after decimal
                if (!data.hasRemaining()) {
                    if (!closed) {
                        return false;
                    }
                    throw new JSONException("Decimal point must be followed by digit");
                }

                byte digit = data.get();
                if (digit < '0' || digit > '9') {
                    throw new JSONException("Decimal point must be followed by digit");
                }
                dval = digit - '0';
                mul = 10;

                // Consume remaining fractional digits
                while (data.hasRemaining()) {
                    int pos = data.position();
                    byte d = data.get();
                    if (d >= '0' && d <= '9') {
                        if (!fracOverflow) {
                            int dg = d - '0';
                            if (mul > Long.MAX_VALUE / 10 || dval > (Long.MAX_VALUE - dg) / 10) {
                                fracOverflow = true;
                            } else {
                                dval = dval * 10 + dg;
                                mul *= 10;
                            }
                        }
                    } else {
                        data.position(pos);
                        break;
                    }
                }

                if (!data.hasRemaining() && !closed) {
                    return false; // Might be 'e' coming
                }
            } else {
                // Not decimal point - backtrack; the byte we just peeked at
                // (c2) will be re-examined below for a possible exponent.
                data.position(savedPos);
            }
        } else if (!closed) {
            return false; // Might be . or e coming
        }

        boolean hasExponent = false;
        boolean expNegative = false;
        int exponent = 0;
        boolean expOverflow = false;

        // Optional exponent part - checked exactly once here, regardless of
        // whether a fractional part was present above.
        if (data.hasRemaining()) {
            int savedPos = data.position();
            byte c2 = data.get();
            if (c2 == 'e' || c2 == 'E') {
                hasExponent = true;

                if (!data.hasRemaining()) {
                    if (!closed) {
                        return false;
                    }
                    throw new JSONException("Incomplete exponent");
                }
                byte sign = data.get();
                if (sign == '+' || sign == '-') {
                    expNegative = (sign == '-');
                    if (!data.hasRemaining()) {
                        if (!closed) {
                            return false;
                        }
                        throw new JSONException("Exponent must have digit");
                    }
                    sign = data.get();
                }
                if (sign < '0' || sign > '9') {
                    throw new JSONException("Exponent must have digit");
                }
                exponent = sign - '0';

                while (data.hasRemaining()) {
                    int pos = data.position();
                    byte d = data.get();
                    if (d >= '0' && d <= '9') {
                        if (!expOverflow) {
                            exponent = exponent * 10 + (d - '0');
                            if (exponent > 100_000) {
                                // Anything beyond this over/underflows to Infinity/0
                                // regardless - not worth tracking precisely, just
                                // route to the slow (String) path below.
                                expOverflow = true;
                            }
                        }
                    } else {
                        data.position(pos);
                        break;
                    }
                }

                if (!data.hasRemaining() && !closed) {
                    return false; // Might be more exponent digits
                }
            } else {
                // Not exponent - backtrack; number is complete.
                data.position(savedPos);
            }
        } else if (!closed) {
            return false; // Might be e/E coming
        }

        if (limits.maxNumberLength > 0 && data.position() - startPos > limits.maxNumberLength) {
            throw new JSONException("Maximum number length exceeded: " + limits.maxNumberLength);
        }

        // Compose and report the number
        Number num = composeNumber(data, startPos, negative, ival, intOverflow,
                hasFraction, dval, mul, fracOverflow,
                hasExponent, expNegative, exponent, expOverflow);
        handler.numberValue(num);
        return true;
    }

    /**
     * Composes the final {@link Number} from the digit accumulators built up
     * while scanning the number's grammar in {@link #processNumber}: avoid a
     * {@code String} allocation and a full {@code Double.parseDouble}/
     * {@code BigInteger} parse for the common case by composing the value
     * directly from the accumulated digits, falling back to a full
     * {@code String} extraction only when an accumulator overflowed (an
     * unusually large number of significant digits).
     */
    private Number composeNumber(ByteBuffer data, int startPos, boolean negative,
                                  long ival, boolean intOverflow,
                                  boolean hasFraction, long dval, long mul, boolean fracOverflow,
                                  boolean hasExponent, boolean expNegative, int exponent, boolean expOverflow)
            throws JSONException {
        int endPos = data.position();

        if (!hasFraction && !hasExponent) {
            // Plain integer
            if (intOverflow) {
                String numStr = decodeSpan(data, startPos, endPos, false);
                try {
                    return new BigInteger(numStr);
                } catch (NumberFormatException e) {
                    throw new JSONException("Invalid number: " + numStr, e);
                }
            }
            long value = negative ? -ival : ival;
            if (value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE) {
                return Integer.valueOf((int) value);
            }
            return Long.valueOf(value);
        }

        // Real number (has a fractional part and/or an exponent)
        if (intOverflow || fracOverflow || expOverflow) {
            String numStr = decodeSpan(data, startPos, endPos, false);
            try {
                return Double.valueOf(numStr);
            } catch (NumberFormatException e) {
                throw new JSONException("Invalid number: " + numStr, e);
            }
        }

        double mantissa = hasFraction ? (ival + (dval / (double) mul)) : (double) ival;
        double value = mantissa;
        if (hasExponent) {
            double signedExponent = expNegative ? -exponent : exponent;
            if (signedExponent < -290 || signedExponent > 290) {
                // Splitting avoids Math.pow(10, signedExponent) underflowing/
                // overflowing to exactly 0/Infinity on its own for exponents
                // near the extremes of the double range (e.g. 4.9e-324, the
                // smallest subnormal) when the mantissa should still bring
                // the final product back into representable range.
                double half = signedExponent / 2;
                value = mantissa * Math.pow(10, half) * Math.pow(10, signedExponent - half);
            } else {
                value = mantissa * Math.pow(10, signedExponent);
            }
        }
        if (negative) {
            value = -value;
        }
        return Double.valueOf(value);
    }
}
