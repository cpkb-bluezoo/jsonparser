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
    private StringBuilder escapeBuilder; // Lazily allocated for keys with escapes

    // A string value is delivered to the handler as it is read, never
    // buffered: inString is true between startStringValue and
    // endStringValue, and chars holds decoded characters awaiting delivery.
    private boolean inString;
    private long stringBytes; // raw content bytes of the current string value consumed so far
    private CharBuffer chars;
    private int stringChunkSize; // capacity of chars: the most characters one content call carries

    // A number is read in a single forward pass, one byte at a time, keeping
    // only the value so far. It therefore needs no look-ahead and no
    // buffering, and can span any number of receive() calls. It ends at the
    // first byte that cannot continue it (left unconsumed), or at end of input.
    private static final int NUM_NONE = 0;
    private static final int NUM_AFTER_MINUS = 1;     // "-" seen: a digit must follow
    private static final int NUM_ZERO = 2;            // a leading "0": no more integer digits allowed
    private static final int NUM_INTEGER = 3;         // integer digits 1-9...
    private static final int NUM_FRACTION_DOT = 4;    // "." seen: a digit must follow
    private static final int NUM_FRACTION = 5;        // fraction digits
    private static final int NUM_EXPONENT_MARK = 6;   // "e" or "E" seen: a sign or digit must follow
    private static final int NUM_EXPONENT_SIGN = 7;   // exponent sign seen: a digit must follow
    private static final int NUM_EXPONENT = 8;        // exponent digits

    private int numberState = NUM_NONE;
    private boolean numberNegative;
    private boolean numberIsReal;     // has a fraction and/or an exponent
    private int numberLength;         // bytes of the number so far, for maxNumberLength and the locator
    private long mantissa;            // all integer and fraction digits read as one integer, as many as fit a long
    private boolean mantissaFull;     // a further digit would overflow mantissa, so later digits are dropped
    private int decimalShift;         // power of ten to scale mantissa by (fraction digits kept, integer digits dropped)
    private boolean droppedNonZero;   // a dropped digit was not zero, so the true value is above mantissa
    private int firstDropped;         // the first dropped digit, or -1
    private boolean exponentNegative;
    private int exponent;             // exponent digits, saturating (anything this large is infinity or zero)
    // An integer too large for a long is kept exactly, 18 digits at a time
    private BigInteger bigInteger;
    private long bigChunk;
    private int bigChunkDigits;

    private static final BigInteger TEN_POW_18 = BigInteger.valueOf(1_000_000_000_000_000_000L);
    private boolean needsWhitespace; // Whether handler needs whitespace events

    private int lineNumber = 1;
    private int columnNumber = 1;
    private boolean afterCR = false; // Last byte was a CR, so a following LF is the same line break

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
                  ParserLimits limits, int stringChunkSize) {
        this.handler = handler;
        this.stringChunkSize = stringChunkSize;
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
     * Called when no more input will arrive. Completes a number that ends
     * the document (such as the whole document {@code 42}), and rejects
     * anything left unfinished.
     */
    void endOfInput() throws JSONException {
        if (inString) {
            throw new JSONException("Unclosed string");
        }
        switch (numberState) {
            case NUM_NONE:
                return;
            case NUM_AFTER_MINUS:
                throw new JSONException("Invalid number: just '-'");
            case NUM_FRACTION_DOT:
                throw new JSONException("Decimal point must be followed by digit");
            case NUM_EXPONENT_MARK:
                throw new JSONException("Incomplete exponent");
            case NUM_EXPONENT_SIGN:
                throw new JSONException("Exponent must have digit");
            default:
                emitNumber();
        }
    }

    /**
     * Process tokens from the ByteBuffer, calling handler methods.
     * Buffer must be in read mode (position at start, limit at end).
     * Returns when buffer is exhausted or an incomplete token is encountered.
     * Buffer position will be updated to reflect consumed bytes.
     */
    void receive(ByteBuffer data) throws JSONException {
        while (data.hasRemaining()) {
            if (inString) {
                if (!continueString(data)) {
                    return;
                }
                continue;
            }
            if (numberState != NUM_NONE) {
                if (!continueNumber(data)) {
                    return;
                }
                continue;
            }

            // Save position for backtracking
            int saveLineNumber = lineNumber;
            int saveColumnNumber = columnNumber;
            data.mark();

            byte b = data.get();

            // Check if this is whitespace (which handles its own line/column tracking)
            boolean isWhitespace = (b == ' ' || b == '\n' || b == '\t' || b == '\r');
            if (!isWhitespace) {
                afterCR = false;
            }

            boolean processed = processToken(b, data);

            if (!processed) {
                // Incomplete token - backtrack
                data.reset();
                lineNumber = saveLineNumber;
                columnNumber = saveColumnNumber;
                return;
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
                if (state == State.EXPECT_KEY) {
                    // A key is a single token: it must be complete in the buffer
                    if (!processKey(data)) {
                        return false;
                    }
                    seenAnyToken = true;
                    if (limits.maxTokenCount > 0 && ++tokenCount > limits.maxTokenCount) {
                        throw new JSONException("Maximum token count exceeded: " + limits.maxTokenCount);
                    }
                    afterComma = false;
                    state = State.EXPECT_COLON;
                    return true;
                }
                // A string value is streamed: report its start now and
                // deliver its content as it arrives (see continueString)
                columnNumber++;
                handler.startStringValue();
                inString = true;
                stringBytes = 0;
                seenAnyToken = true;
                if (limits.maxTokenCount > 0 && ++tokenCount > limits.maxTokenCount) {
                    throw new JSONException("Maximum token count exceeded: " + limits.maxTokenCount);
                }
                afterComma = false;
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
                columnNumber++;
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
                columnNumber++;
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
                columnNumber++;
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
                columnNumber++;
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
                columnNumber++;
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
                columnNumber++;
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
                    startNumber(b, data);
                    seenAnyToken = true;
                    if (limits.maxTokenCount > 0 && ++tokenCount > limits.maxTokenCount) {
                        throw new JSONException("Maximum token count exceeded: " + limits.maxTokenCount);
                    }
                    afterComma = false;
                    return true;
                }
                throw new JSONException("Unexpected character: " + (char) (b & 0xFF));
        }
    }

    /**
     * Process an object key token.
     * Opening quote already consumed.
     * <p>
     * Scanning for the closing quote, backslash, and control characters is
     * done directly against raw bytes - safe per the class-level UTF-8
     * self-synchronization note, since none of those bytes can appear
     * inside a multi-byte sequence. Only the actual content between
     * delimiters is ever decoded, via {@link #decodeSpan}, and only once
     * its extent is fully known.
     */
    private boolean processKey(ByteBuffer data) throws JSONException {
        if (data.hasArray()) {
            return processKeyArray(data);
        }
        return processKeyGeneric(data);
    }

    /**
     * Array-backed fast path for {@link #processKey}: scans for the
     * closing quote/escapes/control-characters via direct indexing into the
     * buffer's backing array instead of {@code ByteBuffer}'s bounds-checked
     * relative {@code get()} - the same trade {@link UTF8Decoder} already
     * makes. {@code data}'s position is kept in sync at every point another
     * method ({@link #decodeSpan}, {@link #processEscapeSequence}) needs to
     * read from it, and on the success return; it does not need to be kept
     * in sync on a "need more data" return, since {@link #receive} discards
     * it via {@code reset()} unconditionally in that case.
     */
    private boolean processKeyArray(ByteBuffer data) throws JSONException {
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
        boolean tryIntern = !keyInternDisabled;
        int keyHash = tryIntern ? KeySymbolTable.initialHash() : 0;

        int lengthLimit = limits.maxNameLength;

        while (p < arrLimit) {
            int bytePos = p - off; // buffer-relative position of this byte
            byte b = arr[p];
            p++;

            // Raw byte position is a conservative (never under-protective)
            // proxy for decoded character count - checked here, mid-scan,
            // so a pathologically long unescaped string is rejected without
            // needing to be fully buffered first.
            if (lengthLimit > 0 && bytePos - startPos > lengthLimit) {
                throw new JSONException("Maximum key length exceeded: " + lengthLimit);
            }

            if (b == '"') {
                data.position(p - off);
                String value;
                int endPos = bytePos;
                if (builder == null) {
                    if (tryIntern && endPos - startPos <= KeySymbolTable.MAX_KEY_BYTES) {
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

                if (rejectDuplicateKeys && !duplicateKeyStack.peek().add(value)) {
                    throw new JSONException("Duplicate key: " + value);
                }
                columnNumber += 2 + srcCharCount; // +2 for the opening/closing quotes
                handler.key(value);
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

        return false; // Need more data
    }

    /**
     * Fallback path for {@link #processKey} using the relative buffer
     * API - correct for direct buffers, which the public
     * {@link JSONParser#receive(ByteBuffer)} API could in principle be
     * called with even though nothing in this project constructs one.
     */
    private boolean processKeyGeneric(ByteBuffer data) throws JSONException {
        int startPos = data.position(); // just after opening quote
        StringBuilder builder = null;
        int spanStart = startPos; // start of the not-yet-flushed raw span
        int srcCharCount = 0; // UTF-16 source units consumed, for locator column tracking
        // Tracked as a side effect of the scan below (which already visits
        // every byte anyway), so decodeSpan doesn't need its own separate
        // scan of the same bytes just to decide ASCII-vs-decode strategy.
        boolean sawNonAscii = false;
        int lengthLimit = limits.maxNameLength;

        while (data.hasRemaining()) {
            int bytePos = data.position();
            byte b = data.get();
            if (b < 0) {
                sawNonAscii = true;
            }

            // See processKeyArray for why this checks raw byte position.
            if (lengthLimit > 0 && bytePos - startPos > lengthLimit) {
                throw new JSONException("Maximum key length exceeded: " + lengthLimit);
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

                if (rejectDuplicateKeys && !duplicateKeyStack.peek().add(value)) {
                    throw new JSONException("Duplicate key: " + value);
                }
                columnNumber += 2 + srcCharCount; // +2 for the opening/closing quotes
                handler.key(value);
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
        return false; // Need more data
    }

    /**
     * Continues the string value in progress, consuming as much of
     * {@code data} as can be delivered. Nothing is buffered: plain runs are
     * decoded into {@link #chars} and handed to the handler as soon as the
     * run ends or the buffer fills, so a string of any length takes a fixed
     * amount of memory.
     * <p>
     * A fragment that cannot be completed yet (a multi-byte UTF-8 sequence
     * or an escape sequence cut off by the end of the buffer, or an escaped
     * high surrogate whose partner has not been seen) is left unconsumed
     * for the caller to present again with more data.
     *
     * @return true if the closing quote was consumed, false if more data is
     *         needed to make progress
     */
    private boolean continueString(ByteBuffer data) throws JSONException {
        if (chars == null || chars.capacity() != stringChunkSize) {
            chars = CharBuffer.allocate(stringChunkSize); // empty here, so nothing is lost
        }
        int lengthLimit = limits.maxStringLength;

        while (true) {
            int runStart = data.position();
            int end = data.limit();
            int p = scanPlainRun(data, runStart, end);

            if (p > runStart) {
                // Checked before the run is delivered, so a string over the
                // limit is rejected without its excess ever being reported
                if (lengthLimit > 0 && stringBytes + (p - runStart) > lengthLimit) {
                    throw new JSONException("Maximum string length exceeded: " + lengthLimit);
                }
                decodeRun(data, runStart, p, end);
            }

            if (p == end) {
                flushChars();
                return false; // Out of data (any partial UTF-8 tail stays unconsumed)
            }

            byte b = data.get(p);
            if (b == '"') {
                flushChars();
                data.position(p + 1);
                columnNumber++;
                inString = false;
                state = State.AFTER_VALUE;
                handler.endStringValue();
                return true;
            }
            if (b != '\\') {
                throw new JSONException("Unescaped control character in string");
            }

            // Escape sequence: needs all of its bytes to be present
            data.position(p + 1);
            Character escaped = processEscapeSequence(data);
            if (escaped == null) {
                data.position(p);
                flushChars();
                return false;
            }
            char first = escaped.charValue();
            char second = 0;
            if (Character.isHighSurrogate(first)) {
                // Deliver a surrogate pair together, so no content call ends
                // between its halves
                int follows = lowSurrogateEscapeFollows(data);
                if (follows < 0) {
                    data.position(p);
                    flushChars();
                    return false;
                }
                if (follows > 0) {
                    data.position(data.position() + 1); // past the backslash
                    second = processEscapeSequence(data).charValue();
                }
            }
            if (chars.remaining() < 2) {
                flushChars();
            }
            chars.put(first);
            if (second != 0) {
                chars.put(second);
            }
            int consumed = data.position() - p;
            stringBytes += consumed;
            columnNumber += consumed; // raw escape bytes are always ASCII
            if (lengthLimit > 0 && stringBytes > lengthLimit) {
                throw new JSONException("Maximum string length exceeded: " + lengthLimit);
            }
        }
    }

    /**
     * Returns the index of the first byte in {@code [from, to)} that ends a
     * run of plain string content (a quote, a backslash or a control
     * character), or {@code to} if there is none. UTF-8 is self-synchronizing,
     * so none of those bytes can occur inside a multi-byte sequence.
     */
    private static int scanPlainRun(ByteBuffer data, int from, int to) {
        if (data.hasArray()) {
            byte[] arr = data.array();
            int off = data.arrayOffset();
            for (int i = from; i < to; i++) {
                byte b = arr[off + i];
                if (b == '"' || b == '\\' || (b >= 0 && b < 0x20)) {
                    return i;
                }
            }
            return to;
        }
        for (int i = from; i < to; i++) {
            byte b = data.get(i);
            if (b == '"' || b == '\\' || (b >= 0 && b < 0x20)) {
                return i;
            }
        }
        return to;
    }

    /**
     * Tells whether the bytes at {@code data}'s position are an escaped low
     * surrogate ({@code \\uDC00} to {@code \\uDFFF}), following an escaped high
     * surrogate. Returns 1 if so, 0 if not (the high surrogate is on its
     * own), or -1 if too few bytes are available to tell.
     */
    private static int lowSurrogateEscapeFollows(ByteBuffer data) {
        int q = data.position();
        int available = data.limit() - q;
        if (available == 0) {
            return -1;
        }
        if (data.get(q) != '\\') {
            return 0;
        }
        if (available < 2) {
            return -1;
        }
        if (data.get(q + 1) != 'u') {
            return 0;
        }
        if (available < 6) {
            return -1;
        }
        int value = 0;
        for (int i = 2; i < 6; i++) {
            byte h = data.get(q + i);
            int digit;
            if (h >= '0' && h <= '9') {
                digit = h - '0';
            } else if (h >= 'a' && h <= 'f') {
                digit = h - 'a' + 10;
            } else if (h >= 'A' && h <= 'F') {
                digit = h - 'A' + 10;
            } else {
                return 0; // not a valid escape; it is reported when it is reached
            }
            value = (value << 4) | digit;
        }
        return (value >= 0xDC00 && value <= 0xDFFF) ? 1 : 0;
    }

    /**
     * Decodes the plain bytes {@code [from, to)} of {@code data} into
     * {@link #chars}, in slices that fit what is free in it, handing the
     * characters to the handler whenever it fills. So however much a receive
     * buffer holds, a content call carries at most {@link #stringChunkSize}
     * characters. UTF-8 never decodes to more chars than bytes, so a slice no
     * longer than the free space always fits.
     * <p>
     * A multi-byte sequence cut off by the end of a slice is decoded in the
     * next one; one cut off by the end of {@code data} itself ({@code to ==
     * end}) is left unconsumed, with the position at its start.
     *
     * @param end the limit of {@code data}, restored after each slice
     */
    private void decodeRun(ByteBuffer data, int from, int to, int end) throws JSONException {
        int pos = from;
        while (pos < to) {
            if (chars.remaining() < MIN_DECODE_ROOM) {
                flushChars(); // a slice of a few bytes is not worth the call
            }
            int sliceEnd = Math.min(to, pos + chars.remaining());
            boolean last = (sliceEnd == to);
            int before = chars.position();
            data.limit(sliceEnd);
            try {
                // A delimiter follows the run (to < end), so a truncated
                // sequence at its very end can never be completed: an error
                UTF8Decoder.decode(data, chars, last && to < end);
            } finally {
                data.limit(end);
            }
            int next = data.position();
            stringBytes += next - pos;
            columnNumber += chars.position() - before;
            pos = next;
            if (pos < sliceEnd && last) {
                return; // cut off by the end of the data: wait for more
            }
            // Otherwise a sequence cut off by the end of the slice is decoded
            // by the next one, which has room for it: fewer than
            // MIN_DECODE_ROOM free chars flushes at the top of the loop
        }
    }

    // Decoding a run in slices needs at least this much free room in chars; a
    // chunk size below it could not hold the longest escape or character
    static final int MIN_DECODE_ROOM = 16;

    /** Sets the most characters one content call may carry; takes effect from the next string. */
    void setStringChunkSize(int stringChunkSize) {
        this.stringChunkSize = stringChunkSize;
    }

    /** Hands any decoded characters to the handler and empties the buffer. */
    private void flushChars() throws JSONException {
        if (chars.position() > 0) {
            chars.flip();
            handler.stringValueContent(chars);
            chars.clear();
        }
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
            return null; // Need more data
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
                return null; // Need more data
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
        int startPos = data.position() - 1; // Before first byte
        advanceOverWhitespace(first);

        while (data.hasRemaining()) {
            int savedPos = data.position();
            byte b = data.get();

            if (b == ' ' || b == '\t' || b == '\n' || b == '\r') {
                advanceOverWhitespace(b);
            } else {
                // Not whitespace - backtrack
                data.position(savedPos);
                break;
            }
        }

        if (needsWhitespace) {
            // Whitespace is always ASCII, so this is always the cheap fast path
            String ws = decodeSpan(data, startPos, data.position(), false);
            handler.whitespace(ws);
        }
        return true;
    }

    /**
     * Updates the line/column position for one whitespace byte. CR, LF and
     * CRLF are each a single line break; {@code afterCR} carries the CR
     * across calls so the pair is not counted twice if a chunk boundary
     * falls between them.
     */
    private void advanceOverWhitespace(byte b) {
        if (b == '\n') {
            if (!afterCR) {
                lineNumber++;
            }
            columnNumber = 1;
            afterCR = false;
        } else if (b == '\r') {
            lineNumber++;
            columnNumber = 1;
            afterCR = true;
        } else {
            columnNumber++;
            afterCR = false;
        }
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
     * {@link #processKeyArray}.
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
            return false; // Need more data
        }

        data.position(pos + len);

        columnNumber += len + 1; // the rest of the literal, and its first character
        if (boolValue != null) {
            handler.booleanValue(boolValue);
        } else {
            handler.nullValue();
        }
        return true;
    }

    /**
     * Fallback path for {@link #processLiteral} using the relative buffer
     * API - correct for direct buffers (see {@link #processKeyGeneric}).
     */
    private boolean processLiteralGeneric(ByteBuffer data, String remaining, Boolean boolValue) throws JSONException {
        // First character already consumed and counted
        for (int i = 0; i < remaining.length(); i++) {
            if (!data.hasRemaining()) {
                return false; // Need more data
            }

            byte b = data.get();
            if (b != remaining.charAt(i)) {
                throw new JSONException("Invalid literal");
            }
        }

        columnNumber += remaining.length() + 1; // the rest of the literal, and its first character
        if (boolValue != null) {
            handler.booleanValue(boolValue);
        } else {
            handler.nullValue();
        }
        return true;
    }

    /**
     * Starts a number at its first byte, {@code '-'} or a digit. A number
     * that ends within {@code data} (the usual case for an integer) is
     * completed straight away; otherwise its state is kept for
     * {@link #continueNumber}.
     */
    private void startNumber(byte first, ByteBuffer data) throws JSONException {
        numberNegative = (first == '-');
        numberIsReal = false;
        numberLength = 1;
        mantissa = 0;
        mantissaFull = false;
        decimalShift = 0;
        exponent = 0;
        exponentNegative = false;
        bigInteger = null;
        if (numberNegative) {
            numberState = NUM_AFTER_MINUS;
            return;
        }
        if (first == '0') {
            numberState = NUM_ZERO;
        } else {
            numberState = NUM_INTEGER;
            mantissa = first - '0';
            if (data.hasArray()) {
                takeDigitRun(data);
                int lengthLimit = limits.maxNumberLength;
                if (lengthLimit > 0 && numberLength > lengthLimit) {
                    throw new JSONException("Maximum number length exceeded: " + lengthLimit);
                }
            }
        }
        // An integer followed by something that cannot continue it is
        // complete: no need to go round the main loop to find that out
        if (data.hasRemaining()) {
            byte next = data.get(data.position());
            if ((next < '0' || next > '9') && next != '.' && next != 'e' && next != 'E') {
                emitNumber();
            }
        }
    }

    /**
     * Continues the number in progress with the bytes of {@code data}.
     *
     * @return true if the number ended (at a byte left unconsumed in
     *         {@code data}), false if {@code data} ran out first
     */
    private boolean continueNumber(ByteBuffer data) throws JSONException {
        int lengthLimit = limits.maxNumberLength;
        while (data.hasRemaining()) {
            if (data.hasArray() && !mantissaFull
                    && (numberState == NUM_INTEGER || numberState == NUM_FRACTION)) {
                takeDigitRun(data);
                if (lengthLimit > 0 && numberLength > lengthLimit) {
                    throw new JSONException("Maximum number length exceeded: " + lengthLimit);
                }
                if (!data.hasRemaining()) {
                    break;
                }
            }
            byte b = data.get();
            int digit = b - '0';
            boolean isDigit = digit >= 0 && digit <= 9;
            switch (numberState) {
                case NUM_AFTER_MINUS:
                    if (b == '0') {
                        numberState = NUM_ZERO;
                    } else if (isDigit) {
                        numberState = NUM_INTEGER;
                        takeDigit(digit, false);
                    } else {
                        throw new JSONException("Invalid number format");
                    }
                    break;
                case NUM_ZERO:
                    if (isDigit) {
                        throw new JSONException("Numbers cannot have leading zeros");
                    } else if (b == '.') {
                        numberIsReal = true;
                        numberState = NUM_FRACTION_DOT;
                    } else if (b == 'e' || b == 'E') {
                        startExponent();
                    } else {
                        return endNumber(data);
                    }
                    break;
                case NUM_INTEGER:
                    if (isDigit) {
                        takeDigit(digit, false);
                    } else if (b == '.') {
                        numberIsReal = true;
                        numberState = NUM_FRACTION_DOT;
                    } else if (b == 'e' || b == 'E') {
                        startExponent();
                    } else {
                        return endNumber(data);
                    }
                    break;
                case NUM_FRACTION_DOT:
                    if (!isDigit) {
                        throw new JSONException("Decimal point must be followed by digit");
                    }
                    numberState = NUM_FRACTION;
                    takeDigit(digit, true);
                    break;
                case NUM_FRACTION:
                    if (isDigit) {
                        takeDigit(digit, true);
                    } else if (b == 'e' || b == 'E') {
                        startExponent();
                    } else {
                        return endNumber(data);
                    }
                    break;
                case NUM_EXPONENT_MARK:
                    if (b == '+' || b == '-') {
                        exponentNegative = (b == '-');
                        numberState = NUM_EXPONENT_SIGN;
                    } else if (isDigit) {
                        numberState = NUM_EXPONENT;
                        takeExponentDigit(digit);
                    } else {
                        throw new JSONException("Exponent must have digit");
                    }
                    break;
                case NUM_EXPONENT_SIGN:
                    if (!isDigit) {
                        throw new JSONException("Exponent must have digit");
                    }
                    numberState = NUM_EXPONENT;
                    takeExponentDigit(digit);
                    break;
                case NUM_EXPONENT:
                    if (isDigit) {
                        takeExponentDigit(digit);
                    } else {
                        return endNumber(data);
                    }
                    break;
                default:
                    throw new IllegalStateException("Not in a number");
            }
            numberLength++;
            if (lengthLimit > 0 && numberLength > lengthLimit) {
                throw new JSONException("Maximum number length exceeded: " + lengthLimit);
            }
        }
        return false;
    }

    private void startExponent() {
        numberIsReal = true;
        numberState = NUM_EXPONENT_MARK;
    }

    // While the mantissa is at most this, any next digit fits a long
    private static final long DIGIT_ALWAYS_FITS_BELOW = 922337203685477579L;

    /**
     * Takes a whole run of integer or fraction digits at once, working on
     * local copies of the accumulators. This is the common case in numeric
     * data, so it avoids the per-byte state dispatch. Stops at the first
     * byte that is not a digit, or when the mantissa nears overflow; the
     * general code then deals with that byte.
     */
    private void takeDigitRun(ByteBuffer data) {
        byte[] arr = data.array();
        int off = data.arrayOffset();
        int start = data.position();
        int end = data.limit();
        int p = start;
        long m = mantissa;
        while (p < end && m <= DIGIT_ALWAYS_FITS_BELOW) {
            int digit = arr[off + p] - '0';
            if (digit < 0 || digit > 9) {
                break;
            }
            m = m * 10 + digit;
            p++;
        }
        int taken = p - start;
        if (taken > 0) {
            mantissa = m;
            if (numberState == NUM_FRACTION) {
                decimalShift -= taken;
            }
            numberLength += taken;
            data.position(p);
        }
    }

    /** The last byte read does not belong to the number: give it back and report the number. */
    private boolean endNumber(ByteBuffer data) throws JSONException {
        data.position(data.position() - 1);
        emitNumber();
        return true;
    }

    /**
     * Adds one digit of the integer part or the fraction. Digits are read
     * into {@link #mantissa} until the next would overflow a long; after
     * that they are dropped (integer digits still scale the value, and
     * still feed {@link #bigInteger} so a plain integer stays exact).
     */
    private void takeDigit(int digit, boolean fraction) {
        if (!mantissaFull) {
            if (mantissa <= (Long.MAX_VALUE - digit) / 10) {
                mantissa = mantissa * 10 + digit;
                if (fraction) {
                    decimalShift--;
                }
                return;
            }
            mantissaFull = true;
            droppedNonZero = false;
            firstDropped = -1;
            if (!fraction) {
                bigInteger = BigInteger.valueOf(mantissa);
                bigChunk = 0;
                bigChunkDigits = 0;
            }
        }
        if (!fraction) {
            decimalShift++;
            bigChunk = bigChunk * 10 + digit;
            if (++bigChunkDigits == 18) {
                bigInteger = bigInteger.multiply(TEN_POW_18).add(BigInteger.valueOf(bigChunk));
                bigChunk = 0;
                bigChunkDigits = 0;
            }
        }
        if (digit != 0) {
            droppedNonZero = true;
        }
        if (firstDropped < 0) {
            firstDropped = digit;
        }
    }

    private void takeExponentDigit(int digit) {
        if (exponent < 100_000) {
            exponent = exponent * 10 + digit;
        }
    }

    /** Reports the finished number to the handler. */
    private void emitNumber() throws JSONException {
        Number number = numberIsReal ? realValue() : integerValue();
        numberState = NUM_NONE;
        state = State.AFTER_VALUE;
        columnNumber += numberLength;
        handler.numberValue(number);
    }

    private Number integerValue() {
        if (bigInteger != null) {
            BigInteger value = bigInteger;
            if (bigChunkDigits > 0) {
                value = value.multiply(BigInteger.TEN.pow(bigChunkDigits)).add(BigInteger.valueOf(bigChunk));
            }
            return numberNegative ? value.negate() : value;
        }
        long value = numberNegative ? -mantissa : mantissa;
        if (value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE) {
            return Integer.valueOf((int) value);
        }
        return Long.valueOf(value);
    }

    /**
     * The double nearest to the number. Exact (as {@link Double#parseDouble}
     * would give) when its significant digits fit {@link #mantissa}. If more
     * digits were dropped the true value lies strictly between mantissa and
     * mantissa + 1 (in units of the shift), so the double is the same for
     * both ends unless a rounding boundary falls between them; in that rare
     * case the nearer end, judged by the first dropped digit, is used and the
     * result may be one ulp out.
     */
    private Number realValue() {
        int e10 = (exponentNegative ? -exponent : exponent) + decimalShift;
        if (!(mantissaFull && droppedNonZero)) {
            return Double.valueOf(DecimalToDouble.convert(numberNegative, mantissa, e10));
        }
        double low = DecimalToDouble.convert(false, mantissa, e10);
        double high = mantissa == Long.MAX_VALUE ? low : DecimalToDouble.convert(false, mantissa + 1, e10);
        double value = (low == high || firstDropped < 5) ? low : high;
        return Double.valueOf(numberNegative ? -value : value);
    }
}
