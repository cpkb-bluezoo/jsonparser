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

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.EOFException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * A JSON tokenizer. This follows the rules given in ECMA 404.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class JSONTokenizer implements JSONLocator {

    // The six structural tokens
    private static final int LEFT_BRACKET = 0x5b;
    private static final int LEFT_ACCOLADE = 0x7b;
    private static final int RIGHT_BRACKET = 0x5d;
    private static final int RIGHT_ACCOLADE = 0x7d;
    private static final int COLON = 0x3a;
    private static final int COMMA = 0x2c;

    // Literal name tokens
    private static final String TRUE = "true";
    private static final String FALSE = "false";
    private static final String NULL = "null";

    // Quotes, whitespace etc
    private static final int QUOTE = 0x22;
    private static final int REVERSE_SOLIDUS = 0x5c;
    private static final int SPACE = 0x20;
    private static final int TAB = 0x209;
    private static final int FF = 0x0c;
    private static final int LF = 0x0a;
    private static final int CR = 0x0d;

    private static final Token TOKEN_TRUE = new BooleanToken(true);
    private static final Token TOKEN_FALSE = new BooleanToken(false);
    private static final Token TOKEN_NULL = new Token(Token.Type.NULL);
    private static final Token TOKEN_COMMA = new Token(Token.Type.COMMA);
    private static final Token TOKEN_COLON = new Token(Token.Type.COLON);
    private static final Token TOKEN_EOF = new Token(Token.Type.EOF);
    private static final Token TOKEN_START_OBJECT = new Token(Token.Type.START_OBJECT);
    private static final Token TOKEN_END_OBJECT = new Token(Token.Type.END_OBJECT);
    private static final Token TOKEN_START_ARRAY = new Token(Token.Type.START_ARRAY);
    private static final Token TOKEN_END_ARRAY = new Token(Token.Type.END_ARRAY);

    private final StringBuilder buf;
    private final Reader in;

    private int lineNumber = 1, columnNumber = 1;

    private static final int[] UTF8_BOM = new int[] { 0xEF, 0xBB, 0xBF};
    private static final int[] UTF16_BE_BOM = new int[] { 0xFE, 0xFF};
    private static final int[] UTF16_LE_BOM = new int[] { 0xFF, 0xFE};
    private static final int[] UTF32_BE_BOM = new int[] { 0x00, 0x00, 0xFE, 0xFF};
    private static final int[] UTF32_LE_BOM = new int[] { 0xFF, 0xFE, 0x00, 0x00};

    /**
     * @param in the underlying input stream
     * @param charset the stream's character set, if specified
     */
    JSONTokenizer(InputStream in, String charset) throws IOException {
        if (charset == null) {
            // Determine the Unicode character set to use by looking at the
            // first four bytes of the stream
            if (!in.markSupported()) {
                in = new BufferedInputStream(in);
            }
            in.mark(4);
            int c0 = in.read();
            int c1 = in.read();
            int c2 = in.read();
            int c3 = in.read();
            in.reset();
            // Check for byte order mark first
            if (c0 == 0xef && c1 == 0xbb && c2 == 0xbf) { // UTF-8 BOM
                in.read();
                in.read();
                in.read();
                charset = "UTF-8";
            } else if (c0 == 0 && c1 == 0 && c2 == 0xfe && c3 == 0xff) { // UTF-32BE BOM
                in.read();
                in.read();
                in.read();
                in.read();
                charset = "UTF-32BE";
            } else if (c0 == 0xff && c1 == 0xfe && c2 == 0 && c3 == 0) { // UTF-32LE BOM
                in.read();
                in.read();
                in.read();
                in.read();
                charset = "UTF-32LE";
            } else if (c0 == 0xfe && c1 == 0xff) { // UTF-16BE BOM
                in.read();
                in.read();
                charset = "UTF-16BE";
            } else if (c0 == 0xff && c1 == 0xfe) { // UTF-16LE BOM
                in.read();
                in.read();
                charset = "UTF-16LE";
            }
            if (charset == null) { // autodetect algorithm
                if (c0 == 0 && c1 == 0 && c2 == 0) {
                    charset = "UTF-32BE";
                } else if (c0 == 0 && c2 == 0) {
                    charset = "UTF-16BE";
                } else if (c1 == 0 && c2 == 0 && c3 == 0) {
                    charset = "UTF-32LE";
                } else if (c1 == 0 && c3 == 0) {
                    charset = "UTF-16LE";
                } else {
                    charset = "UTF-8";
                }
            }
        }
        Reader reader = new InputStreamReader(in, charset);
        this.in = reader.markSupported() ? reader : new BufferedReader(reader); // need to mark
        buf = new StringBuilder();
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
     * Read the next token from the stream.
     */
    Token nextToken() throws IOException, JSONException {
        int c = in.read();
        switch (c) {
            case -1:
                return TOKEN_EOF;
            case QUOTE:
                return consumeString();
            case COMMA:
                columnNumber++;
                return TOKEN_COMMA;
            case COLON:
                columnNumber++;
                return TOKEN_COLON;
            case LEFT_ACCOLADE:
                columnNumber++;
                return TOKEN_START_OBJECT;
            case RIGHT_ACCOLADE:
                columnNumber++;
                return TOKEN_END_OBJECT;
            case LEFT_BRACKET:
                columnNumber++;
                return TOKEN_START_ARRAY;
            case RIGHT_BRACKET:
                columnNumber++;
                return TOKEN_END_ARRAY;
            case SPACE:
            case LF:
            case TAB:
            case FF:
            case CR:
                return consumeWhitespace(c);
            case 0x74: // t
                int t2 = in.read(), t3 = in.read(), t4 = in.read();
                if (t2 == 0x72 && t3 == 0x75 && t4 == 0x65) { // true
                    columnNumber += 4;
                    return TOKEN_TRUE;
                } else {
                    throw new JSONException("Unexpected characters");
                }
            case 0x66: // f
                int f2 = in.read(), f3 = in.read(), f4 = in.read(), f5 = in.read();
                if (f2 == 0x61 && f3 == 0x6c && f4 == 0x73 && f5 == 0x65) { // false
                    columnNumber += 5;
                    return TOKEN_FALSE;
                } else {
                    throw new JSONException("Unexpected characters");
                }
            case 0x6e: // n
                int n2 = in.read(), n3 = in.read(), n4 = in.read();
                if (n2 == 0x75 && n3 == 0x6c && n4 == 0x6c) { // null
                    columnNumber += 4;
                    return TOKEN_NULL;
                } else {
                    throw new JSONException("Unexpected characters");
                }
            default:
                // number
                return consumeNumber(c);
        }
    }

    StringToken consumeString() throws IOException, JSONException {
        columnNumber++; // "
        buf.setLength(0); // reset buf
        int c = in.read();
        while (c != QUOTE) {
            switch (c) {
                case -1:
                    throw new EOFException("Unexpected EOF in string value");
                case REVERSE_SOLIDUS:
                    buf.append(consumeEscapeSequence());
                    break;
                default:
                    if (c <= 0x1f) {
                        throw new EOFException("Unescaped control character in string value");
                    }
                    columnNumber++;
                    buf.append((char) c);
            }
            c = in.read();
        }
        columnNumber++; // "
        return new StringToken(buf.toString());
    }

    char consumeEscapeSequence() throws IOException, JSONException {
        columnNumber++; // \
        int c = in.read();
        switch (c) {
            case -1:
                throw new EOFException("Unexpected EOF in escape sequence");
            case QUOTE:
                columnNumber++;
                return '"';
            case REVERSE_SOLIDUS:
                columnNumber++;
                return '\\';
            case 0x2f: // SOLIDUS
                columnNumber++;
                return '/';
            case 0x62: // b
                columnNumber++;
                return '\b';
            case 0x66: // f
                columnNumber++;
                return '\f';
            case 0x6e: // n
                columnNumber++;
                return '\n';
            case 0x72: // r
                columnNumber++;
                return '\r';
            case 0x74: // t
                columnNumber++;
                return '\t';
            case 0x75: // u
                columnNumber++;
                return consumeHexadecimalEscapeSequence();
            default:
                throw new JSONException("Unexpected character 0x"+Integer.toHexString(c)+" in escape sequence");
        }
    }

    char consumeHexadecimalEscapeSequence() throws IOException, JSONException {
        // four hexadecimal digits
        int d1 = unhex(in.read());
        columnNumber++;
        int d2 = unhex(in.read());
        columnNumber++;
        int d3 = unhex(in.read());
        columnNumber++;
        int d4 = unhex(in.read());
        columnNumber++;
        int ret = d4 | (d3 << 4) | (d2 << 8) | (d1 << 12);
        return (char) ret;
    }

    int unhex(int c) throws JSONException {
        if (c >= 0x30 && c <= 0x39) { // 0-9
            return c - 0x30;
        } else if (c >= 0x41 && c <= 0x46) { // A-F
            return c - 0x37;
        } else if (c >= 0x61 && c <= 0x66) { // a-f
            return c - 0x57;
        }
        throw new JSONException("Unexpected character in hexadecimal digit");
    }

    WhitespaceToken consumeWhitespace(int c) throws IOException, JSONException {
        buf.setLength(0);
        int last = -1;
        while (true) {
            if (c == LF) {
                if (last != CR) { // Collapse CRLF into one line ending
                    lineNumber++;
                    columnNumber = 1;
                }
                buf.append((char) c);
                last = c;
            } else if (c == CR) {
                lineNumber++;
                columnNumber = 1;
                buf.append((char) c);
                last = c;
            } else if (c == SPACE || c == TAB) {
                columnNumber++;
                buf.append((char) c);
                last = c;
            } else {
                in.reset();
                columnNumber--;
                return new WhitespaceToken(buf.toString());
            }
            in.mark(1);
            c = in.read();
        }
    }

    NumberToken consumeNumber(int c) throws IOException, JSONException {
        buf.setLength(0); // reset
        boolean negativeNumber = (c == 0x2d); // -
        long number = 0L;
        if (negativeNumber) {
            columnNumber++;
            buf.append((char) c);
            c = in.read();
        }
        if (c == 0x30) { // 0
            buf.append((char) c);
            columnNumber++;
            in.mark(1);
            c = in.read();
        } else if (c >= 0x31 && c <= 0x39) { // 1-9
            do {
                columnNumber++;
                buf.append((char) c);
                in.mark(1);
                c = in.read();
            } while (c >= 0x30 && c <= 0x39); // 0-9
        } else {
            throw new JSONException("Invalid character in number value: 0x"+Integer.toHexString(c));
        }
        // number part complete
        // Expecting: .|e|E|EOF
        if (c == -1 || (c != 0x2e && c != 0x65 && c != 0x45)) { // integer
            in.reset();
            try {
                long l = Long.parseLong(buf.toString());
                if (l >= (long) Integer.MIN_VALUE && l <= (long) Integer.MAX_VALUE) {
                    // Store it as an integer to reduce memory cost
                    return new NumberToken(Integer.valueOf((int) l));
                } else {
                    return new NumberToken(Long.valueOf(l));
                }
            } catch (NumberFormatException e) {
                // Could be too large. Try BigInteger
                return new NumberToken(new BigInteger(buf.toString()));
            }
        }
        boolean hasFractionalPart = (c == 0x2e); // .
        long fractionalPart = 0L;
        if (hasFractionalPart) {
            columnNumber++;
            buf.append((char) c);
            c = in.read();
            if (c >= 0x30 && c <= 0x39) { // 0-9
                do {
                    columnNumber++;
                    buf.append((char) c);
                    in.mark(1);
                    c = in.read();
                } while (c >= 0x30 && c <= 0x39); // 0-9
            } else {
                throw new JSONException("Invalid character in number value: 0x"+Integer.toHexString(c));
            }
            // fractional part complete
        }
        // Expecting: e|E
        boolean hasExponent = (c == 0x65 || c == 0x45);
        long exponent = 0L;
        if (hasExponent) {
            columnNumber++;
            buf.append((char) c); // E|e
            c = in.read();
            boolean negativeExponent = (c == 0x2d); // -
            if (negativeExponent || c == 0x2b) { // +
                columnNumber++;
                buf.append((char) c);
                c = in.read();
            }
            if (c >= 0x30 && c <= 0x39) { // 0-9
                do {
                    columnNumber++;
                    buf.append((char) c);
                    in.mark(1);
                    c = in.read();
                } while (c >= 0x30 && c <= 0x39); // 0-9
            } else {
                throw new JSONException("Invalid character in number value: 0x"+Integer.toHexString(c));
            }
            // exponent part complete
        }
        in.reset();
        try {
            return new NumberToken(Double.valueOf(buf.toString()));
        } catch (NumberFormatException e) {
            return new NumberToken(new BigDecimal(buf.toString()));
        }
    }

}
