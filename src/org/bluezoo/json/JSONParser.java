package org.bluezoo.json;

import java.io.InputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * A streaming JSON parser using the push (receive) model.
 * <p>
 * Unlike traditional parsers that take an InputStream and block during
 * parsing, this parser uses a non-blocking push model:
 * <ul>
 * <li>{@link #receive(ByteBuffer)} - push bytes as they arrive</li>
 * <li>{@link #close()} - signal end of data</li>
 * </ul>
 * <p>
 * For convenience, a traditional blocking {@link #parse(InputStream)} method
 * is also provided which internally delegates to the streaming API.
 * <p>
 * Parsing events are delivered via {@link JSONContentHandler} as tokens
 * are recognized. The parser handles BOM detection, then delegates directly
 * to {@link JSONTokenizer}, which tokenizes against the raw bytes - UTF-8
 * decoding only ever happens for the actual content of a string value/key,
 * never for structural characters, whitespace, numbers, or literals.
 *
 * <h2>Buffer Management Contract</h2>
 * <p>This parser follows the standard non-blocking streaming contract:
 * <ul>
 *   <li>The caller provides a buffer in read mode (ready for get operations)</li>
 *   <li>The parser consumes as many complete tokens as possible. It holds
 *       none of the document's bytes itself</li>
 *   <li>Anything it cannot use yet (a key or literal that is not finished, the
 *       start of an escape sequence or multi-byte character in a string, a
 *       partial byte order mark) is left unconsumed: after
 *       {@link #receive(ByteBuffer)} returns, the buffer's position is where
 *       those bytes begin</li>
 *   <li>The caller must {@link ByteBuffer#compact() compact()} the buffer (not
 *       {@code clear()} it), read more data in after the unconsumed bytes,
 *       and present them again at the head of the next {@code receive()}.
 *       The parser checks this and throws a {@link JSONException} if the
 *       bytes are missing or changed</li>
 *   <li>The buffer must be larger than the longest key ({@link #setMaxNameLength},
 *       plus its two quotes). If it is full of one unfinished token,
 *       {@code receive()} throws rather than waiting for data that cannot fit</li>
 * </ul>
 * <p><strong>Memory:</strong> memory use does not grow with document size or
 * string length. The parser has two buffers: the one passed to
 * {@code receive}, and a character buffer of fixed size (32768 characters by
 * default, see {@link #setStringChunkSize}) into which string content is
 * decoded and handed to the handler as it arrives
 * ({@link JSONContentHandler#startStringValue()},
 * {@link JSONContentHandler#stringValueContent(CharSequence)},
 * {@link JSONContentHandler#endStringValue()}). Keys are delivered whole, so
 * a key stays in the caller's buffer until complete.
 *
 * <h2>Usage (Streaming)</h2>
 * <pre>{@code
 * JSONParser parser = new JSONParser();
 * parser.setContentHandler(myHandler);
 * 
 * ByteBuffer buffer = ByteBuffer.allocate(8192);
 * while (channel.read(buffer) > 0) {
 *     buffer.flip();
 *     parser.receive(buffer);
 *     buffer.compact();
 * }
 * parser.close();
 * }</pre>
 *
 * <h2>Usage (InputStream)</h2>
 * <pre>{@code
 * JSONParser parser = new JSONParser();
 * parser.setContentHandler(myHandler);
 * parser.parse(inputStream);
 * }</pre>
 *
 * <h2>Character Encoding</h2>
 * The parser assumes UTF-8 encoding by default. BOM detection is performed
 * on the first chunk.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class JSONParser {

    private static final int DEFAULT_BUFFER_SIZE = 8192;
    private static final int DEFAULT_STRING_CHUNK_SIZE = 32768;

    private JSONContentHandler handler;
    private JSONTokenizer tokenizer;
    private final int bufferSize;
    private int stringChunkSize = DEFAULT_STRING_CHUNK_SIZE;

    // Stream state
    private boolean checkedBom;
    private boolean closed;

    // The parser holds none of the document's bytes. What receive() cannot
    // use yet (an incomplete key or literal, the start of an escape sequence
    // or multi-byte character, a partial byte order mark) is left unconsumed
    // in the caller's buffer, which the caller compacts and presents again
    // with more data. These record how many bytes were left, and a hash of
    // them, so a caller that threw them away (clear() instead of compact())
    // is told rather than silently mis-parsed.
    private int unconsumed;
    private long unconsumedHash;

    // Hash-first key interning table (see KeySymbolTable) - created once and
    // reused across every JSONTokenizer this parser creates, so it stays
    // warm across multiple documents parsed via reset().
    private final KeySymbolTable keySymbolTable = new KeySymbolTable();

    // Opt-in strict duplicate-key detection - off by default, zero behavior
    // change unless explicitly enabled. See setRejectDuplicateKeys.
    private boolean rejectDuplicateKeys;

    // Resource-exhaustion guard limits (see ParserLimits) - on by default,
    // with industry-standard values (matching Jackson's StreamReadConstraints).
    private final ParserLimits limits = new ParserLimits();

    // Cumulative bytes received for the current document (reset() clears
    // this), checked against limits.maxDocumentLength.
    private long documentLength;

    /**
     * Creates a new JSON parser with a default buffer size of 8KB.
     */
    public JSONParser() {
        this(DEFAULT_BUFFER_SIZE);
    }

    /**
     * Creates a new JSON parser with the specified buffer size.
     *
     * @param bufferSize the size in bytes of the single buffer that
     *        {@link #parse(InputStream)} reads into; it must be larger than
     *        the longest key (see {@link #setMaxNameLength}) plus its quotes
     */
    public JSONParser(int bufferSize) {
        if (bufferSize <= 0) {
            throw new IllegalArgumentException("Buffer size must be positive");
        }
        this.bufferSize = bufferSize;
    }

    /**
     * Register a content handler to be notified of parsing events.
     *
     * @param handler the content handler
     */
    public void setContentHandler(JSONContentHandler handler) {
        this.handler = handler;
    }

    /**
     * Sets whether a repeated key within the same object should be treated
     * as a parse error. Off by default: RFC 8259 does not mandate unique
     * object keys, and checking for duplicates has a (small) performance
     * cost, so this is opt-in rather than automatic.
     * <p>
     * Worth enabling wherever the parsed data crosses a trust boundary:
     * different systems (or different libraries) can disagree about which
     * of two duplicate keys "wins," which is a known class of vulnerability
     * (JSON key-confusion/smuggling) when the same payload is interpreted
     * differently by two components.
     * <p>
     * Takes effect from the next document onward (i.e. the next {@link #parse}
     * call, or the next {@link #reset()} followed by {@link #receive}) -
     * changing it mid-document has no effect on a tokenizer already created
     * for that document.
     *
     * @param rejectDuplicateKeys true to throw a JSONException on a repeated
     *        key within the same object
     */
    public void setRejectDuplicateKeys(boolean rejectDuplicateKeys) {
        this.rejectDuplicateKeys = rejectDuplicateKeys;
    }

    /**
     * Sets the maximum object/array nesting depth. Guards against stack/
     * memory exhaustion from a maliciously or accidentally deeply nested
     * document. Default 1000 (matches Jackson's {@code StreamReadConstraints}
     * default). A value {@code <= 0} disables this check.
     *
     * @param maxNestingDepth the maximum nesting depth, or {@code <= 0} to disable
     */
    public void setMaxNestingDepth(int maxNestingDepth) {
        limits.maxNestingDepth = maxNestingDepth;
    }

    /**
     * Sets the maximum number of characters in a single number token.
     * Guards against the CPU/memory cost of converting a pathologically
     * long digit sequence to a {@code BigInteger}/{@code double}. Default
     * 1000 (matches Jackson). A value {@code <= 0} disables this check.
     *
     * @param maxNumberLength the maximum number token length, or {@code <= 0} to disable
     */
    public void setMaxNumberLength(int maxNumberLength) {
        limits.maxNumberLength = maxNumberLength;
    }

    /**
     * Sets the maximum size in raw bytes (before unescaping, excluding the
     * quotes) of a single string value. A value {@code <= 0} means no limit,
     * which is the default.
     * <p>
     * The parser does not need this: a string is delivered to the handler a
     * piece at a time through a fixed-size buffer, so a string of any length
     * costs it the same. It is for a handler that accumulates strings, or a
     * service that wants to reject oversize fields early. (Jackson, which
     * returns a whole {@code String}, defaults to 20,000,000.) The limit is
     * checked before each piece is delivered, so an over-long string is
     * rejected without its excess being reported.
     *
     * @param maxStringLength the maximum string length, or {@code <= 0} to disable
     */
    public void setMaxStringLength(int maxStringLength) {
        limits.maxStringLength = maxStringLength;
    }

    /**
     * Sets the maximum size in raw bytes (before unescaping, excluding the
     * quotes) of a single object key. Default 4096 (Jackson uses 50,000). A
     * value {@code <= 0} disables this check.
     *
     * @param maxNameLength the maximum key length, or {@code <= 0} to disable
     */
    public void setMaxNameLength(int maxNameLength) {
        limits.maxNameLength = maxNameLength;
    }

    /**
     * Sets the maximum total number of bytes in one document. Unlimited
     * (0) by default - matches Jackson, which also leaves this off by
     * default since it's very application-specific. A value {@code <= 0}
     * disables this check.
     *
     * @param maxDocumentLength the maximum document size in bytes, or {@code <= 0} to disable
     */
    public void setMaxDocumentLength(long maxDocumentLength) {
        limits.maxDocumentLength = maxDocumentLength;
    }

    /**
     * Sets the maximum total number of tokens in one document. Guards
     * against a huge flat array/object (many small elements) that would
     * otherwise slip past the other limits. Unlimited (0) by default -
     * matches Jackson. A value {@code <= 0} disables this check.
     *
     * @param maxTokenCount the maximum token count, or {@code <= 0} to disable
     */
    public void setMaxTokenCount(long maxTokenCount) {
        limits.maxTokenCount = maxTokenCount;
    }

    /**
     * Disables every configurable limit (nesting depth, number/string/key
     * length, document length, token count) in one call. Intended for
     * trusted input and for internal testing/benchmarking, where the
     * industry-standard defaults would otherwise be unnecessarily
     * restrictive rather than protective.
     */
    public void disableAllLimits() {
        limits.disableAll();
    }

    /**
     * Sets the size of the character buffer that string content is decoded
     * into, which is also the most characters one
     * {@link JSONContentHandler#stringValueContent(CharSequence)} call carries.
     * Default 32768. However large the buffers passed to {@link #receive} are,
     * the bytes of a string are decoded and delivered in pieces of at most this
     * many characters, so the parser's memory for strings is fixed by this
     * setting and not by the size of the input buffer. A larger value means
     * fewer calls to the handler; a smaller one, a smaller buffer.
     * <p>
     * The value must be at least 16, enough for the longest escape sequence or
     * multi-byte character. It takes effect from the next string, so it may be
     * changed at any time.
     *
     * @param stringChunkSize the buffer size in characters
     * @throws IllegalArgumentException if it is less than 16
     */
    public void setStringChunkSize(int stringChunkSize) {
        if (stringChunkSize < JSONTokenizer.MIN_DECODE_ROOM) {
            throw new IllegalArgumentException("String chunk size must be at least "
                    + JSONTokenizer.MIN_DECODE_ROOM);
        }
        this.stringChunkSize = stringChunkSize;
        if (tokenizer != null) {
            tokenizer.setStringChunkSize(stringChunkSize);
        }
    }

    /**
     * Parse a JSON document from an InputStream.
     * <p>
     * This is a convenience method that reads the input stream in chunks
     * and delegates to the streaming {@link #receive(ByteBuffer)} API.
     * The stream is read until EOF, then {@link #close()} is called.
     * <p>
     * The parser is automatically reset before parsing, allowing this
     * method to be called multiple times to parse different documents.
     * <p>
     * Note: This method does not close the InputStream.
     *
     * @param in the input stream to parse
     * @throws JSONException if there is a parsing error
     */
    public void parse(InputStream in) throws JSONException {
        // Reset for new document
        reset();
        
        try {
            ByteBuffer buffer = ByteBuffer.allocate(bufferSize);
            
            // Read into the buffer's backing array
            while (true) {
                // Read into available space (after any unconsumed data)
                int bytesRead = in.read(buffer.array(), buffer.position(), 
                                        buffer.remaining());
                if (bytesRead == -1) {
                    break;
                }
                
                // Advance position to reflect bytes read
                buffer.position(buffer.position() + bytesRead);
                
                // Switch to read mode and parse
                buffer.flip();
                receive(buffer);
                
                // Preserve any unconsumed bytes for next iteration
                buffer.compact();
            }
            
            // Anything still unconsumed is an incomplete token: close() rejects it
            close();
        } catch (IOException e) {
            throw new JSONException("I/O error reading stream", e);
        }
    }

    /**
     * Receive bytes into the parser.
     * <p>
     * Bytes are processed immediately. Parsing events are fired to the
     * content handler as tokens are recognized.
     * <p>
     * <strong>Buffer Contract:</strong> The byte buffer must be in read mode
     * (after {@code flip()}). After this method returns, any bytes the parser
     * could not use yet remain unconsumed, starting at the buffer's position.
     * The caller must {@code compact()} the buffer, read more data in after
     * them, and call this method again with them still at the head of the
     * buffer.
     *
     * @param data the byte buffer to process (must be in read mode)
     * @throws JSONException if there is a parsing error, the stream is
     *         closed, bytes left unconsumed by the previous call are not
     *         presented again, or the buffer is full of a single token that
     *         is not yet complete
     */
    public void receive(ByteBuffer data) throws JSONException {
        if (closed) {
            throw new JSONException("Cannot receive data after close()");
        }

        int available = data.remaining();
        verifyUnconsumedPresentedAgain(data);

        if (available == 0) {
            return;
        }

        if (limits.maxDocumentLength > 0) {
            // The unconsumed bytes presented again were counted when they first arrived
            documentLength += available - unconsumed;
            if (documentLength > limits.maxDocumentLength) {
                throw new JSONException("Maximum document length exceeded: " + limits.maxDocumentLength);
            }
        }

        int startPosition = data.position();

        // Check for BOM on first chunk
        if (!checkedBom) {
            if (!checkBom(data)) {
                // Need more data to determine BOM: leave it all for the caller
                noteUnconsumed(data, startPosition);
                return;
            }
            checkedBom = true;
        }

        // Create tokenizer if needed
        if (tokenizer == null) {
            tokenizer = new JSONTokenizer(handler, keySymbolTable, rejectDuplicateKeys, limits, stringChunkSize);
        }

        // Tokenize directly against the raw bytes - no decode step for
        // structural characters, whitespace, numbers, or literals; see
        // JSONTokenizer for the UTF-8 self-synchronization property this
        // relies on.
        tokenizer.receive(data);

        noteUnconsumed(data, startPosition);
    }

    /**
     * Checks that the bytes the previous call left unconsumed are at the
     * head of {@code data}, as the buffer contract requires.
     */
    private void verifyUnconsumedPresentedAgain(ByteBuffer data) throws JSONException {
        if (unconsumed == 0) {
            return;
        }
        if (data.remaining() < unconsumed || hashOf(data, unconsumed) != unconsumedHash) {
            throw new JSONException("The " + unconsumed + " unconsumed bytes left by the previous receive() "
                    + "were not presented again: compact() the buffer after receive(), do not clear() it");
        }
    }

    /**
     * Records what receive() left unconsumed in the caller's buffer. If it
     * left everything and the buffer has no room for more, the caller can
     * never supply the rest, so say so rather than loop forever.
     */
    private void noteUnconsumed(ByteBuffer data, int startPosition) throws JSONException {
        unconsumed = data.remaining();
        if (unconsumed == 0) {
            unconsumedHash = 0;
            return;
        }
        unconsumedHash = hashOf(data, unconsumed);
        if (data.position() == startPosition && data.limit() == data.capacity()) {
            throw new JSONException("Receive buffer too small: the next token needs more than the "
                    + unconsumed + " bytes it holds. A key must fit the buffer (see setMaxNameLength).");
        }
    }

    /** FNV-1a hash of the first {@code length} bytes from {@code data}'s position. */
    private static long hashOf(ByteBuffer data, int length) {
        long hash = 0xcbf29ce484222325L;
        int p = data.position();
        for (int i = 0; i < length; i++) {
            hash = (hash ^ (data.get(p + i) & 0xFF)) * 0x100000001b3L;
        }
        return hash;
    }

    /**
     * Close the parser, signaling end of input.
     * <p>
     * This validates that the JSON document is complete. A number that ends
     * the document (such as the whole document {@code 42}) has no delimiter
     * to show where it ends, so it is reported by this call. Bytes still
     * left unconsumed by the last {@link #receive} are an incomplete token and
     * are rejected.
     * <p>
     * After closing, further calls to receive() will throw an exception.
     * Use {@link #reset()} to prepare the parser for a new document.
     *
     * @throws JSONException if the document is incomplete or malformed
     */
    public void close() throws JSONException {
        if (closed) {
            return; // Already closed
        }
        
        closed = true;
        
        // Check if we have any data at all
        if (tokenizer == null) {
            throw new JSONException("No data");
        }
        
        // Bytes left unconsumed can only have been the start of a token that
        // now can never be completed
        if (unconsumed > 0) {
            throw new JSONException("Unclosed string or incomplete token at end of input");
        }

        // Completes a number that ends the document and rejects an unfinished
        // string or number
        tokenizer.endOfInput();

        // Check tokenizer state for structural issues
        if (!tokenizer.seenAnyToken) {
            throw new JSONException("No data");
        }
        
        if (!tokenizer.depthStack.isEmpty()) {
            Token unclosed = tokenizer.depthStack.peek();
            if (unclosed == Token.START_OBJECT) {
                throw new JSONException("Unclosed object");
            } else {
                throw new JSONException("Unclosed array");
            }
        }
    }

    /**
     * Reset the parser to parse a new document.
     * <p>
     * Clears all internal state, allowing the parser to be reused.
     * This is called automatically by {@link #parse(InputStream)}.
     */
    public void reset() {
        checkedBom = false;
        closed = false;
        documentLength = 0;
        unconsumed = 0;
        unconsumedHash = 0;

        // Clear tokenizer
        tokenizer = null;
    }

    /**
     * Check for and skip BOM if present.
     * Optimized for UTF-8 (fast path), also detects UTF-16/32 to reject them.
     * Returns false if we need more data to determine if BOM is present.
     * If BOM is found, it is consumed from the buffer.
     */
    private boolean checkBom(ByteBuffer data) throws JSONException {
        int remaining = data.remaining();
        
        if (remaining == 0) {
            return false; // Need at least 1 byte
        }
        
        // Use get(int) to peek without consuming
        int startPos = data.position();
        byte b1 = data.get(startPos);
        
        // Fast path: UTF-8 BOM (EF BB BF) - most common case
        if (b1 == (byte) 0xEF) {
            if (remaining < 3) {
                // Might be UTF-8 BOM, need more data
                if (remaining >= 2 && data.get(startPos + 1) == (byte) 0xBB) {
                    return false; // Partial UTF-8 BOM
                }
                if (remaining == 1) {
                    return false; // Could be UTF-8 BOM
                }
            } else {
                byte b2 = data.get(startPos + 1);
                byte b3 = data.get(startPos + 2);
                
                if (b2 == (byte) 0xBB && b3 == (byte) 0xBF) {
                    // UTF-8 BOM found, skip it
                    data.position(startPos + 3);
                    return true;
                }
            }
            // Not a UTF-8 BOM, proceed with parsing
            return true;
        }
        
        // Check for UTF-16/32 BOMs (rare, error path)
        // UTF-16 BE: FE FF (2 bytes)
        if (b1 == (byte) 0xFE) {
            if (remaining < 2) {
                return false; // Need more data
            }
            if (data.get(startPos + 1) == (byte) 0xFF) {
                throw new JSONException("UTF-16 BE encoding not supported");
            }
            return true; // Not a BOM
        }
        
        // UTF-16 LE or UTF-32 LE: FF FE ... (need 4 bytes to distinguish)
        if (b1 == (byte) 0xFF) {
            if (remaining < 2) {
                return false; // Need more data
            }
            if (data.get(startPos + 1) == (byte) 0xFE) {
                if (remaining < 4) {
                    return false; // Need 4 bytes to distinguish UTF-16 LE from UTF-32 LE
                }
                // Check for UTF-32 LE: FF FE 00 00
                if (data.get(startPos + 2) == (byte) 0x00 && 
                    data.get(startPos + 3) == (byte) 0x00) {
                    throw new JSONException("UTF-32 LE encoding not supported");
                }
                // UTF-16 LE
                throw new JSONException("UTF-16 LE encoding not supported");
            }
            return true; // Not a BOM
        }
        
        // UTF-32 BE: 00 00 FE FF (4 bytes)
        if (b1 == (byte) 0x00) {
            if (remaining < 2) {
                return false; // Need more data
            }
            if (data.get(startPos + 1) == (byte) 0x00) {
                if (remaining < 4) {
                    return false; // Might be UTF-32 BE BOM
                }
                if (data.get(startPos + 2) == (byte) 0xFE && 
                    data.get(startPos + 3) == (byte) 0xFF) {
                    throw new JSONException("UTF-32 BE encoding not supported");
                }
            }
            return true; // Not a BOM
        }
        
        // No BOM detected, proceed with parsing
        return true;
    }

}
