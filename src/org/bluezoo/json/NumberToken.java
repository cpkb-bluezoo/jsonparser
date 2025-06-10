package org.bluezoo.json;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.io.IOException;

/**
 * Token representing a number.
 *
 * @author Chris Burdess
 */
class NumberToken extends Token {

    final Number number;

    NumberToken(Number number) {
        super(Token.Type.NUMBER);
        this.number = number;
    }

    public String toString() {
        return number.toString();
    }

}
