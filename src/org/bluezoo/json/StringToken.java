package org.bluezoo.json;

/**
 * A string token.
 *
 * @author Chris Burdess
 */
class StringToken extends Token {

    final String string;

    StringToken(String string) {
        super(Token.Type.STRING);
        this.string = string;
    }

    public String toString() {
        return "\"" + string + "\"";
    }

}
