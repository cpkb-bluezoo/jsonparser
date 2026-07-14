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
 * <h3>Buffer Management Contract</h3>
 * <p>This parser follows the standard non-blocking streaming contract:
 * <ul>
 *   <li>The caller provides a buffer in read mode (ready for get operations)</li>
 *   <li>The parser consumes as many complete tokens as possible</li>
 *   <li>After {@link #receive(ByteBuffer)} returns, the buffer's position
 *       indicates where unconsumed data begins</li>
 *   <li>If there is unconsumed data (partial token), the caller MUST call
 *       {@link ByteBuffer#compact()} before reading more data into the buffer</li>
 *   <li>The next {@code receive()} call will continue from where parsing left off</li>
 * </ul>
 *
 * <h3>Usage (Streaming)</h3>
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
 * <h3>Usage (InputStream)</h3>
 * <pre>{@code
 * JSONParser parser = new JSONParser();
 * parser.setContentHandler(myHandler);
 * parser.parse(inputStream);
 * }</pre>
 *
 * <h3>Character Encoding</h3>
 * The parser assumes UTF-8 encoding by default. BOM detection is performed
 * on the first chunk.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class JSONParser {

    private static final int DEFAULT_BUFFER_SIZE = 8192;

    private JSONContentHandler handler;
    private JSONTokenizer tokenizer;
    private final int bufferSize;

    // Stream state
    private boolean checkedBom;
    private boolean closed;

    // Carries forward whatever bytes were left unconsumed (an incomplete
    // trailing token) at the end of the most recent receive() call. This is
    // needed because receive() may be called with an entirely fresh,
    // non-overlapping ByteBuffer each time (not just the same buffer
    // compacted and refilled) - the parser, not the caller, is responsible
    // for remembering a partial token across calls. It also lets close()
    // (which takes no buffer argument) hand any final leftover bytes to the
    // tokenizer one more time with its closed flag set, to finalize a bare
    // trailing token such as "42" with no following delimiter.
    // In write mode between calls: position is the number of leftover
    // bytes already stored, limit is capacity.
    private ByteBuffer pending;

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
     * @param bufferSize the initial size of the character buffer in bytes
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
     * Sets the maximum number of characters in a single string value.
     * Guards against a huge single-string memory allocation. Default
     * 20,000,000 (matches Jackson). A value {@code <= 0} disables this check.
     *
     * @param maxStringLength the maximum string length, or {@code <= 0} to disable
     */
    public void setMaxStringLength(int maxStringLength) {
        limits.maxStringLength = maxStringLength;
    }

    /**
     * Sets the maximum number of characters in a single object key. Default
     * 50,000 (matches Jackson). A value {@code <= 0} disables this check.
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
            
            // Process any remaining data in the buffer
            buffer.flip();
            if (buffer.hasRemaining()) {
                receive(buffer);
            }
            
            close();
        } catch (IOException e) {
            throw new JSONException("I/O error reading stream", e);
        }
    }

    /**
     * Receive bytes into the parser.
     * <p>
     * Bytes are processed immediately. Parsing events are fired to the
     * content handler as tokens are recognized. Incomplete tokens are
     * left in the buffer - the position is set to the start of the
     * incomplete token.
     * <p>
     * <strong>Buffer Contract:</strong> The byte buffer must be in read mode
     * (after {@code flip()}). After this method returns:
     * <ul>
     *   <li>The buffer's position indicates where unconsumed data begins</li>
     *   <li>If {@code position() < limit()}, there is a partial token that needs
     *       more data to complete</li>
     *   <li>The caller MUST call {@code buffer.compact()} before reading more
     *       data into the buffer</li>
     * </ul>
     *
     * @param data the byte buffer to process (must be in read mode)
     * @throws JSONException if there is a parsing error or stream is closed
     */
    public void receive(ByteBuffer data) throws JSONException {
        if (closed) {
            throw new JSONException("Cannot receive data after close()");
        }

        if (!data.hasRemaining()) {
            return;
        }

        if (limits.maxDocumentLength > 0) {
            documentLength += data.remaining();
            if (documentLength > limits.maxDocumentLength) {
                throw new JSONException("Maximum document length exceeded: " + limits.maxDocumentLength);
            }
        }

        // If a previous call left an incomplete token's worth of bytes
        // uncarried, prepend them so the tokenizer sees the token from its
        // true start. Common case (nothing pending) is zero-copy.
        ByteBuffer toProcess = mergeWithPending(data);

        // Check for BOM on first chunk
        if (!checkedBom) {
            if (!checkBom(toProcess)) {
                // Need more data to determine BOM - carry forward and wait
                savePending(toProcess);
                return;
            }
            checkedBom = true;
        }

        // Create tokenizer if needed
        if (tokenizer == null) {
            tokenizer = new JSONTokenizer(handler, keySymbolTable, rejectDuplicateKeys, limits);
        }

        // Tokenize directly against the raw bytes - no decode step for
        // structural characters, whitespace, numbers, or literals; see
        // JSONTokenizer for the UTF-8 self-synchronization property this
        // relies on.
        tokenizer.receive(toProcess);

        savePending(toProcess);
    }

    /**
     * If {@link #pending} holds carried-over bytes from a previous call,
     * appends {@code data} to it and returns it (in read mode); otherwise
     * returns {@code data} unchanged (the common, zero-copy case).
     */
    private ByteBuffer mergeWithPending(ByteBuffer data) {
        if (pending == null || pending.position() == 0) {
            return data;
        }
        int needed = pending.position() + data.remaining();
        if (pending.capacity() < needed) {
            ByteBuffer bigger = ByteBuffer.allocate(Math.max(needed + 256, pending.capacity() * 2));
            pending.flip();
            bigger.put(pending);
            pending = bigger;
        }
        pending.put(data);
        pending.flip();
        return pending;
    }

    /**
     * Carries forward whatever remains unconsumed in {@code toProcess} (an
     * incomplete trailing token) into {@link #pending}, ready to be
     * prepended by the next call to {@link #mergeWithPending}.
     */
    private void savePending(ByteBuffer toProcess) {
        if (!toProcess.hasRemaining()) {
            if (toProcess == pending) {
                pending.clear();
            }
            return;
        }
        if (toProcess == pending) {
            // Already our own buffer - shift the unread remainder to the
            // front and return to write mode for the next append.
            pending.compact();
        } else {
            int remaining = toProcess.remaining();
            if (pending == null || pending.capacity() < remaining) {
                pending = ByteBuffer.allocate(Math.max(remaining + 256, bufferSize));
            } else {
                pending.clear();
            }
            pending.put(toProcess);
        }
    }

    /**
     * Close the parser, signaling end of input.
     * <p>
     * This validates that the JSON document is complete. The closed state
     * is signaled to the tokenizer, which will treat end-of-buffer as 
     * end-of-token (e.g., for numbers without delimiters like "42").
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
        
        tokenizer.setClosed(true);

        // Re-present any leftover incomplete-token bytes now that the
        // tokenizer knows no more data is coming (e.g. finalizes a bare
        // trailing number like "42" with no following delimiter).
        if (pending != null && pending.position() > 0) {
            pending.flip();
            tokenizer.receive(pending);

            if (pending.hasRemaining()) {
                throw new JSONException("Unclosed string or incomplete token at end of input");
            }
        }

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
        if (pending != null) {
            pending.clear();
        }

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
