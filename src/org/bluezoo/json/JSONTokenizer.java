package org.bluezoo.json;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.InputStream;
import java.io.IOException;

/**
 * A JSON tokenizer. This follows the rules given in ECMA 404.
 * TODO add Locator information
 *
 * @author Chris Burdess
 */
class JSONTokenizer {

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

    private StringBuilder buf;
    private InputStream in;

    JSONTokenizer(InputStream in) {
        this.in = in.markSupported() ? in : new BufferedInputStream(in);
        buf = new StringBuilder();
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
                return TOKEN_COMMA;
            case COLON:
                return TOKEN_COLON;
            case LEFT_ACCOLADE:
                return TOKEN_START_OBJECT;
            case RIGHT_ACCOLADE:
                return TOKEN_END_OBJECT;
            case LEFT_BRACKET:
                return TOKEN_START_ARRAY;
            case RIGHT_BRACKET:
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
                    return TOKEN_TRUE;
                } else {
                    throw new JSONException("Unexpected characters");
                }
            case 0x66: // f
                int f2 = in.read(), f3 = in.read(), f4 = in.read(), f5 = in.read();
                if (f2 == 0x61 && f3 == 0x6c && f4 == 0x73 && f5 == 0x65) { // false
                    return TOKEN_FALSE;
                } else {
                    throw new JSONException("Unexpected characters");
                }
            case 0x6e: // n
                int n2 = in.read(), n3 = in.read(), n4 = in.read();
                if (n2 == 0x75 && n3 == 0x6c && n4 == 0x6c) { // null
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
                    buf.append((char) c);
            }
            c = in.read();
        }
        return new StringToken(buf.toString());
    }

    char consumeEscapeSequence() throws IOException, JSONException {
        int c = in.read();
        switch (c) {
            case -1:
                throw new EOFException("Unexpected EOF in escape sequence");
            case QUOTE:
                return '"';
            case REVERSE_SOLIDUS:
                return '\\';
            case 0x2f: // SOLIDUS
                return '/';
            case 0x62: // b
                return '\b';
            case 0x66: // f
                return '\f';
            case 0x6e: // n
                return '\n';
            case 0x72: // r
                return '\r';
            case 0x74: // t
                return '\t';
            case 0x75: // u
                return consumeHexadecimalEscapeSequence();
            default:
                throw new JSONException("Unexpected character 0x"+Integer.toHexString(c)+" in escape sequence");
        }
    }

    char consumeHexadecimalEscapeSequence() throws IOException, JSONException {
        // four hexadecimal digits
        int d1 = unhex(in.read()), d2 = unhex(in.read()), d3 = unhex(in.read()), d4 = unhex(in.read());
        int ret = d4 | (d3 << 0x10) | (d2 << 0x100) | (d1 << 0x1000);
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
        while (true) {
            if (c == SPACE || c == LF || c == CR || c == TAB || c == FF) {
                buf.append((char) c);
            } else {
                in.reset();
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
            buf.append((char) c);
            c = in.read();
        }
        if (c == 0x30) { // 0
            buf.append((char) c);
        } else if (c >= 0x31 && c <= 0x39) { // 1-9
            do {
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
            long l = Long.parseLong(buf.toString());
            if (l >= (long) Integer.MIN_VALUE && l <= (long) Integer.MAX_VALUE) {
                // Store it as an integer to reduce memory cost
                return new NumberToken(Integer.valueOf((int) l));
            } else {
                return new NumberToken(Long.valueOf(l));
            }
        }
        boolean hasFractionalPart = (c == 0x2e); // .
        long fractionalPart = 0L;
        if (hasFractionalPart) {
            buf.append((char) c);
            c = in.read();
            if (c >= 0x30 && c <= 0x39) { // 0-9
                do {
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
            c = in.read();
            boolean negativeExponent = (c == 0x2d); // -
            if (negativeExponent || c == 0x2b) { // +
                buf.append((char) c);
                c = in.read();
            }
            if (c >= 0x30 && c <= 0x39) { // 0-9
                do {
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
        return new NumberToken(Double.valueOf(buf.toString()));
    }

}
