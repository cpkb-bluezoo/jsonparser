package org.bluezoo.json;

/**
 * A whitespace token.
 *
 * @author Chris Burdess
 */
class WhitespaceToken extends Token {

    final String whitespace;

    WhitespaceToken(String whitespace) {
        super(Token.Type.WHITESPACE);
        this.whitespace = whitespace;
    }

}
