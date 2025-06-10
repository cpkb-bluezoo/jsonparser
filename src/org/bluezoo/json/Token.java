package org.bluezoo.json;

/**
 * Base token class.
 *
 * @author Chris Burdess
 */
class Token {
        
    enum Type { START_OBJECT, END_OBJECT, START_ARRAY, END_ARRAY, NUMBER, STRING, BOOLEAN, NULL, COMMA, COLON, WHITESPACE, EOF };

    final Type type;

    Token(Type type) {
        this.type = type;
    }

    public String toString() {
        return type.toString();
    }

}
