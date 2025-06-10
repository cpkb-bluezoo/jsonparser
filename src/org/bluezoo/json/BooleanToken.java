package org.bluezoo.json;

/**
 * A boolean token.
 *
 * @author Chris Burdess
 */
class BooleanToken extends Token {

    final boolean value;

    BooleanToken(boolean value) {
        super(Token.Type.BOOLEAN);
        this.value = value;
    }

    public String toString() {
        return value ? "true" : "false";
    }

}
