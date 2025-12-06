package org.bluezoo.json;

import org.bluezoo.util.CompositeByteBuffer;

import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;

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
 * are recognized. The parser maintains internal state between receive()
 * calls using a {@link CompositeByteBuffer} for underflow handling.
 *
 * <h3>Usage (Streaming)</h3>
 * <pre>{@code
 * JSONParser parser = new JSONParser();
 * parser.setContentHandler(myHandler);
 * 
 * // As bytes arrive...
 * parser.receive(chunk1);
 * parser.receive(chunk2);
 * ...
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
public class JSONParser implements JSONLocator {

    // Structural tokens
    private static final byte LEFT_BRACKET = '[';
    private static final byte LEFT_BRACE = '{';
    private static final byte RIGHT_BRACKET = ']';
    private static final byte RIGHT_BRACE = '}';
    private static final byte COLON = ':';
    private static final byte COMMA = ',';
    private static final byte QUOTE = '"';
    private static final byte BACKSLASH = '\\';

    // Whitespace
    private static final byte SPACE = ' ';
    private static final byte TAB = '\t';
    private static final byte LF = '\n';
    private static final byte CR = '\r';

    // Parser states
    enum ExpectState { VALUE, KEY, COMMA_OR_CLOSE, COLON, EOF }
    enum ContextState { OBJECT, ARRAY }
    enum TokenState { 
        NONE,           // Not in a token
        STRING,         // In a string
        STRING_ESCAPE,  // After backslash in string
        STRING_HEX,     // In hex escape sequence
        NUMBER,         // In a number
        LITERAL,        // In true/false/null
        WHITESPACE      // In whitespace
    }

    private JSONContentHandler handler;
    private final CompositeByteBuffer buffer = new CompositeByteBuffer();
    
    // Parser state
    private ExpectState expectState = ExpectState.VALUE;
    private final Deque<ContextState> contextStack = new ArrayDeque<>();
    private ContextState currentContext;
    private TokenState tokenState = TokenState.NONE;
    private boolean seenComma;
    private int tokenCount;
    
    // Token accumulation
    private final StringBuilder tokenBuilder = new StringBuilder();
    private int hexDigitsRemaining;
    private int hexValue;
    
    // Location tracking
    private int lineNumber = 1;
    private int columnNumber = 1;
    
    // BOM detection
    private boolean checkedBom;

    /**
     * Creates a new JSON parser.
     */
    public JSONParser() {
    }

    /**
     * Register a content handler to be notified of parsing events.
     *
     * @param handler the content handler
     */
    public void setContentHandler(JSONContentHandler handler) {
        this.handler = handler;
        if (handler != null) {
            handler.setLocator(this);
        }
    }

    /**
     * Parse a JSON document from an InputStream.
     * <p>
     * This is a convenience method that reads the input stream in chunks
     * and delegates to the streaming {@link #receive(ByteBuffer)} API.
     * The stream is read until EOF, then {@link #close()} is called.
     * <p>
     * Note: This method does not close the InputStream.
     *
     * @param in the input stream to parse
     * @throws JSONException if there is a parsing error
     */
    public void parse(InputStream in) throws JSONException {
        try {
            byte[] chunk = new byte[8192];
            int bytesRead;
            
            while ((bytesRead = in.read(chunk)) != -1) {
                ByteBuffer buffer = ByteBuffer.wrap(chunk, 0, bytesRead);
                receive(buffer);
            }
            
            close();
        } catch (java.io.IOException e) {
            throw new JSONException("I/O error reading stream", e);
        }
    }

    /**
     * Receive bytes into the parser.
     * <p>
     * Bytes are processed immediately. Parsing events are fired to the
     * content handler as tokens are recognized. Incomplete tokens are
     * buffered for the next receive() call.
     *
     * @param data the byte buffer to process
     * @throws JSONException if there is a parsing error
     */
    public void receive(ByteBuffer data) throws JSONException {
        buffer.put(data);
        buffer.flip();
        
        // Check for BOM on first chunk
        if (!checkedBom) {
            checkBom();
            checkedBom = true;
        }
        
        parse();
        
        buffer.compact();
    }

    /**
     * Close the parser, signaling end of input.
     * <p>
     * This validates that the JSON document is complete.
     *
     * @throws JSONException if the document is incomplete
     */
    public void close() throws JSONException {
        // Finish any pending token
        if (tokenState != TokenState.NONE) {
            finishToken();
        }
        
        // Validate complete document
        if (!contextStack.isEmpty()) {
            throw new JSONException("Unclosed object or array");
        }
        if (tokenCount == 0) {
            throw new JSONException("No data");
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
     * Check for UTF-8 BOM and skip it.
     */
    private void checkBom() {
        if (buffer.remaining() >= 3) {
            if (buffer.get(0) == (byte) 0xEF &&
                buffer.get(1) == (byte) 0xBB &&
                buffer.get(2) == (byte) 0xBF) {
                // Skip UTF-8 BOM
                buffer.position(buffer.position() + 3);
            }
        }
    }

    /**
     * Main parse loop.
     */
    private void parse() throws JSONException {
        while (buffer.hasRemaining()) {
            byte b = buffer.get();
            
            // Continue parsing based on current token state
            switch (tokenState) {
                case NONE:
                    parseStart(b);
                    break;
                case STRING:
                    parseString(b);
                    break;
                case STRING_ESCAPE:
                    parseStringEscape(b);
                    break;
                case STRING_HEX:
                    parseStringHex(b);
                    break;
                case NUMBER:
                    if (!parseNumber(b)) {
                        // Character wasn't part of number, reprocess
                        buffer.position(buffer.position() - 1);
                        finishToken();
                    }
                    break;
                case LITERAL:
                    if (!parseLiteral(b)) {
                        // Character wasn't part of literal, reprocess
                        buffer.position(buffer.position() - 1);
                        finishToken();
                    }
                    break;
                case WHITESPACE:
                    if (!parseWhitespace(b)) {
                        // Character wasn't whitespace, reprocess
                        buffer.position(buffer.position() - 1);
                        finishToken();
                    }
                    break;
            }
        }
    }

    /**
     * Parse the start of a token.
     */
    private void parseStart(byte b) throws JSONException {
        // Update location for newlines
        if (b == LF) {
            lineNumber++;
            columnNumber = 1;
        } else if (b == CR) {
            // Will check for LF next
            lineNumber++;
            columnNumber = 1;
        } else {
            columnNumber++;
        }

        // Handle whitespace
        if (isWhitespace(b)) {
            tokenState = TokenState.WHITESPACE;
            tokenBuilder.setLength(0);
            tokenBuilder.append((char) b);
            return;
        }

        // Handle structural tokens
        switch (b) {
            case LEFT_BRACE:
                handleStartObject();
                return;
            case RIGHT_BRACE:
                handleEndObject();
                return;
            case LEFT_BRACKET:
                handleStartArray();
                return;
            case RIGHT_BRACKET:
                handleEndArray();
                return;
            case COLON:
                handleColon();
                return;
            case COMMA:
                handleComma();
                return;
            case QUOTE:
                tokenState = TokenState.STRING;
                tokenBuilder.setLength(0);
                return;
        }

        // Check expected state for values
        if (expectState != ExpectState.VALUE) {
            throw new JSONException("Unexpected character: " + (char) b);
        }

        // Start number or literal
        if (b == '-' || (b >= '0' && b <= '9')) {
            tokenState = TokenState.NUMBER;
            tokenBuilder.setLength(0);
            tokenBuilder.append((char) b);
        } else if (b == 't' || b == 'f' || b == 'n') {
            tokenState = TokenState.LITERAL;
            tokenBuilder.setLength(0);
            tokenBuilder.append((char) b);
        } else {
            throw new JSONException("Unexpected character: " + (char) b);
        }
    }

    /**
     * Parse string content.
     */
    private void parseString(byte b) throws JSONException {
        columnNumber++;
        
        if (b == QUOTE) {
            finishToken();
        } else if (b == BACKSLASH) {
            tokenState = TokenState.STRING_ESCAPE;
        } else if (b < 0x20) {
            throw new JSONException("Unescaped control character in string");
        } else {
            tokenBuilder.append((char) (b & 0xFF));
        }
    }

    /**
     * Parse escape sequence in string.
     */
    private void parseStringEscape(byte b) throws JSONException {
        columnNumber++;
        
        switch (b) {
            case '"':
                tokenBuilder.append('"');
                break;
            case '\\':
                tokenBuilder.append('\\');
                break;
            case '/':
                tokenBuilder.append('/');
                break;
            case 'b':
                tokenBuilder.append('\b');
                break;
            case 'f':
                tokenBuilder.append('\f');
                break;
            case 'n':
                tokenBuilder.append('\n');
                break;
            case 'r':
                tokenBuilder.append('\r');
                break;
            case 't':
                tokenBuilder.append('\t');
                break;
            case 'u':
                tokenState = TokenState.STRING_HEX;
                hexDigitsRemaining = 4;
                hexValue = 0;
                return;
            default:
                throw new JSONException("Invalid escape character: " + (char) b);
        }
        
        tokenState = TokenState.STRING;
    }

    /**
     * Parse hex digits in unicode escape.
     */
    private void parseStringHex(byte b) throws JSONException {
        columnNumber++;
        
        int digit = unhex(b);
        hexValue = (hexValue << 4) | digit;
        hexDigitsRemaining--;
        
        if (hexDigitsRemaining == 0) {
            tokenBuilder.append((char) hexValue);
            tokenState = TokenState.STRING;
        }
    }

    /**
     * Parse number.
     * @return true if byte was part of number
     */
    private boolean parseNumber(byte b) {
        if ((b >= '0' && b <= '9') ||
            b == '.' || b == 'e' || b == 'E' ||
            b == '+' || b == '-') {
            columnNumber++;
            tokenBuilder.append((char) b);
            return true;
        }
        return false;
    }

    /**
     * Parse literal (true/false/null).
     * @return true if byte was part of literal
     */
    private boolean parseLiteral(byte b) {
        if ((b >= 'a' && b <= 'z')) {
            columnNumber++;
            tokenBuilder.append((char) b);
            return true;
        }
        return false;
    }

    /**
     * Parse whitespace.
     * @return true if byte was whitespace
     */
    private boolean parseWhitespace(byte b) {
        if (isWhitespace(b)) {
            if (b == LF) {
                lineNumber++;
                columnNumber = 1;
            } else if (b == CR) {
                lineNumber++;
                columnNumber = 1;
            } else {
                columnNumber++;
            }
            tokenBuilder.append((char) b);
            return true;
        }
        return false;
    }

    /**
     * Finish the current token and fire events.
     */
    private void finishToken() throws JSONException {
        switch (tokenState) {
            case STRING:
                handleString(tokenBuilder.toString());
                break;
            case NUMBER:
                handleNumber(tokenBuilder.toString());
                break;
            case LITERAL:
                handleLiteral(tokenBuilder.toString());
                break;
            case WHITESPACE:
                if (handler != null) {
                    handler.whitespace(tokenBuilder.toString());
                }
                break;
            default:
                break;
        }
        tokenState = TokenState.NONE;
        tokenBuilder.setLength(0);
    }

    private void handleStartObject() throws JSONException {
        if (expectState != ExpectState.VALUE) {
            throw new JSONException("Unexpected '{'");
        }
        
        seenComma = false;
        currentContext = ContextState.OBJECT;
        contextStack.push(currentContext);
        expectState = ExpectState.KEY;
        tokenCount++;
        
        if (handler != null) {
            handler.startObject();
        }
    }

    private void handleEndObject() throws JSONException {
        if (currentContext != ContextState.OBJECT) {
            throw new JSONException("Unexpected '}'");
        }
        if (seenComma) {
            throw new JSONException("Trailing comma in object");
        }
        
        contextStack.pop();
        currentContext = contextStack.isEmpty() ? null : contextStack.peek();
        expectState = (currentContext == null) ? ExpectState.EOF : ExpectState.COMMA_OR_CLOSE;
        
        if (handler != null) {
            handler.endObject();
        }
    }

    private void handleStartArray() throws JSONException {
        if (expectState != ExpectState.VALUE) {
            throw new JSONException("Unexpected '['");
        }
        
        seenComma = false;
        currentContext = ContextState.ARRAY;
        contextStack.push(currentContext);
        expectState = ExpectState.VALUE;
        tokenCount++;
        
        if (handler != null) {
            handler.startArray();
        }
    }

    private void handleEndArray() throws JSONException {
        if (currentContext != ContextState.ARRAY) {
            throw new JSONException("Unexpected ']'");
        }
        if (seenComma) {
            throw new JSONException("Trailing comma in array");
        }
        
        contextStack.pop();
        currentContext = contextStack.isEmpty() ? null : contextStack.peek();
        expectState = (currentContext == null) ? ExpectState.EOF : ExpectState.COMMA_OR_CLOSE;
        
        if (handler != null) {
            handler.endArray();
        }
    }

    private void handleColon() throws JSONException {
        if (expectState != ExpectState.COLON) {
            throw new JSONException("Unexpected ':'");
        }
        expectState = ExpectState.VALUE;
    }

    private void handleComma() throws JSONException {
        if (expectState != ExpectState.COMMA_OR_CLOSE) {
            throw new JSONException("Unexpected ','");
        }
        
        seenComma = true;
        if (currentContext == ContextState.OBJECT) {
            expectState = ExpectState.KEY;
        } else {
            expectState = ExpectState.VALUE;
        }
    }

    private void handleString(String value) throws JSONException {
        tokenCount++;
        seenComma = false;
        
        if (expectState == ExpectState.KEY) {
            if (handler != null) {
                handler.key(value);
            }
            expectState = ExpectState.COLON;
        } else if (expectState == ExpectState.VALUE) {
            if (handler != null) {
                handler.stringValue(value);
            }
            expectState = (currentContext == null) ? ExpectState.EOF : ExpectState.COMMA_OR_CLOSE;
        } else {
            throw new JSONException("Unexpected string");
        }
    }

    private void handleNumber(String value) throws JSONException {
        if (expectState != ExpectState.VALUE) {
            throw new JSONException("Unexpected number");
        }
        
        tokenCount++;
        seenComma = false;
        
        Number number = parseNumber(value);
        if (handler != null) {
            handler.numberValue(number);
        }
        
        expectState = (currentContext == null) ? ExpectState.EOF : ExpectState.COMMA_OR_CLOSE;
    }

    private void handleLiteral(String value) throws JSONException {
        if (expectState != ExpectState.VALUE) {
            throw new JSONException("Unexpected literal: " + value);
        }
        
        tokenCount++;
        seenComma = false;
        
        switch (value) {
            case "true":
                if (handler != null) {
                    handler.booleanValue(true);
                }
                break;
            case "false":
                if (handler != null) {
                    handler.booleanValue(false);
                }
                break;
            case "null":
                if (handler != null) {
                    handler.nullValue();
                }
                break;
            default:
                throw new JSONException("Invalid literal: " + value);
        }
        
        expectState = (currentContext == null) ? ExpectState.EOF : ExpectState.COMMA_OR_CLOSE;
    }

    private Number parseNumber(String s) throws JSONException {
        try {
            if (s.contains(".") || s.contains("e") || s.contains("E")) {
                return Double.valueOf(s);
            } else {
                try {
                    long l = Long.parseLong(s);
                    if (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) {
                        return Integer.valueOf((int) l);
                    }
                    return Long.valueOf(l);
                } catch (NumberFormatException e) {
                    return new BigInteger(s);
                }
            }
        } catch (NumberFormatException e) {
            throw new JSONException("Invalid number: " + s, e);
        }
    }

    private boolean isWhitespace(byte b) {
        return b == SPACE || b == TAB || b == LF || b == CR;
    }

    private int unhex(byte b) throws JSONException {
        if (b >= '0' && b <= '9') {
            return b - '0';
        } else if (b >= 'A' && b <= 'F') {
            return b - 'A' + 10;
        } else if (b >= 'a' && b <= 'f') {
            return b - 'a' + 10;
        }
        throw new JSONException("Invalid hex digit: " + (char) b);
    }

}

