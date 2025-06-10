package org.bluezoo.json;

import java.io.InputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * A JSON parser.
 * To use, implement JSONContentHandler and register an instance of it as a
 * handler on the parser. Then call parse.
 *
 * @author Chris Burdess
 */
public class JSONParser {

    enum ExpectState { VALUE, KEY, COMMA_OR_CLOSE, EOF };
    enum ContextState { OBJECT, ARRAY };

    private JSONContentHandler handler;

    /**
     * Register a content handler to be notified of parsing events.
     * @param handler the content handler
     */
    public void setContentHandler(JSONContentHandler handler) {
        this.handler = handler;
    }

    /**
     * Parse an input stream containing JSON data, and report parsing events
     * in it to the registered handler.
     * @param in the input stream
     * @throws IOException if there was an I/O error reading the stream
     * @throws JSONException if there was a problem processing the JSON stream
     */
    public void parse(InputStream in) throws IOException, JSONException {
        JSONTokenizer tokenizer = new JSONTokenizer(in);
        if (handler != null) {
            handler.setLocator(tokenizer);
        }
        ExpectState expectState = ExpectState.VALUE;
        Deque<ContextState> stack = new ArrayDeque<>();
        ContextState current = null;
        Token token = tokenizer.nextToken();
        while (token.type != Token.Type.EOF) {
            if (token.type == Token.Type.WHITESPACE) {
                WhitespaceToken w = (WhitespaceToken) token;
                if (handler != null) {
                    handler.whitespace(w.whitespace);
                }
            } else {
                switch (expectState) {
                    case VALUE:
                        switch (token.type) {
                            case START_OBJECT:
                                current = ContextState.OBJECT;
                                stack.addLast(current);
                                expectState = ExpectState.KEY;
                                if (handler != null) {
                                    handler.startObject();
                                }
                                break;
                            case START_ARRAY:
                                current = ContextState.ARRAY;
                                stack.addLast(current);
                                expectState = ExpectState.VALUE;
                                if (handler != null) {
                                    handler.startArray();
                                }
                                break;
                            case NUMBER:
                                NumberToken n = (NumberToken) token;
                                if (handler != null) {
                                    handler.numberValue(n.number);
                                }
                                expectState = (current == null) ? ExpectState.EOF :  ExpectState.COMMA_OR_CLOSE;
                                break;
                            case STRING:
                                StringToken s = (StringToken) token;
                                if (handler != null) {
                                    handler.stringValue(s.string);
                                }
                                expectState = (current == null) ? ExpectState.EOF :  ExpectState.COMMA_OR_CLOSE;
                                break;
                            case BOOLEAN:
                                BooleanToken b = (BooleanToken) token;
                                if (handler != null) {
                                    handler.booleanValue(b.value);
                                }
                                expectState = (current == null) ? ExpectState.EOF :  ExpectState.COMMA_OR_CLOSE;
                                break;
                            case NULL:
                                if (handler != null) {
                                    handler.nullValue();
                                }
                                expectState = (current == null) ? ExpectState.EOF :  ExpectState.COMMA_OR_CLOSE;
                                break;
                            default:
                                throw new JSONException("Unexpected token: "+token);
                        }
                        break;
                    case KEY:
                        if (token.type == Token.Type.STRING) {
                            StringToken s = (StringToken) token;
                            if (handler != null) {
                                handler.key(s.string);
                            }
                            // Must be followed by colon
                            token = tokenizer.nextToken();
                            if (token.type == Token.Type.WHITESPACE) {
                                token = tokenizer.nextToken();
                            }
                            if (token.type != Token.Type.COLON) {
                                throw new JSONException("Key not followed by colon: "+token);
                            }
                            expectState = ExpectState.VALUE;
                        } else {
                            throw new JSONException("Unexpected token: "+token);
                        }
                        break;
                    case COMMA_OR_CLOSE:
                        switch (token.type) {
                            case COMMA:
                                if (current == ContextState.OBJECT) {
                                    expectState = ExpectState.KEY;
                                } else if (current == ContextState.ARRAY) {
                                    expectState = ExpectState.VALUE;
                                } else {
                                    throw new JSONException("Comma outside of object or array: "+token);
                                }
                                break;
                            case END_OBJECT:
                                if (current == ContextState.OBJECT) {
                                    if (handler != null) {
                                        handler.endObject();
                                    }
                                    stack.removeLast();
                                    current = stack.isEmpty() ? null : stack.peekLast();
                                    expectState = (current == null) ? ExpectState.EOF :  ExpectState.COMMA_OR_CLOSE;
                                } else {
                                    throw new JSONException("Encountered end of object outside object: "+token);
                                }
                                break;
                            case END_ARRAY:
                                if (current == ContextState.ARRAY) {
                                    if (handler != null) {
                                        handler.endObject();
                                    }
                                    stack.removeLast();
                                    current = stack.isEmpty() ? null : stack.peekLast();
                                    expectState = (current == null) ? ExpectState.EOF :  ExpectState.COMMA_OR_CLOSE;
                                } else {
                                    throw new JSONException("Encountered end of array outside array: "+token);
                                }
                                break;
                            default:
                                throw new JSONException("Expected comma or end of structure: "+token);
                        }
                        break;
                    case EOF:
                        throw new JSONException("Expected EOF: "+token);
                }
            }
            token = tokenizer.nextToken();
        }
    }

}
