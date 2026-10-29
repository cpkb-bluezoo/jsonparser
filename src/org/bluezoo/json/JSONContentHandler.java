/*
 * JSONContentHandler.java
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

/**
 * This is the main interface to be implemented by an application wanting to
 * receive JSON parsing events. An application can implement this interface
 * and register the implementation with the JSONParser using the
 * setContentHandler method.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface JSONContentHandler {

    /**
     * Indicates the start of a JSON object.
     * @throws JSONException the client may throw an exception during
     * processing
     */
    public void startObject() throws JSONException;

    /**
     * Indicates the end of a JSON object.
     * @throws JSONException the client may throw an exception during
     * processing
     */
    public void endObject() throws JSONException;

    /**
     * Indicates the start of a JSON array.
     * @throws JSONException the client may throw an exception during
     * processing
     */
    public void startArray() throws JSONException;

    /**
     * Indicates the end of a JSON array.
     * @throws JSONException the client may throw an exception during
     * processing
     */
    public void endArray() throws JSONException;

    /**
     * Notifies of a JSON number.
     * @param number the number parsed: an Integer, Long or Double, or a
     * BigInteger for an integer too large for a Long
     * @throws JSONException the client may throw an exception during
     * processing
     */
    public void numberValue(Number number) throws JSONException;

    /**
     * Indicates the start of a JSON string value, at its opening quote. The
     * characters of the string follow in zero or more
     * {@link #stringValueContent(CharSequence)} calls, and the string ends
     * with {@link #endStringValue()}.
     * <p>
     * Object keys are not reported this way: a key is a single token
     * delivered whole to {@link #key(String)}.
     * @throws JSONException the client may throw an exception during
     * processing
     */
    public void startStringValue() throws JSONException;

    /**
     * Notifies of the next characters of the current string value,
     * unescaped and unquoted. The value of the string is the concatenation
     * of the content of every call between {@link #startStringValue()} and
     * {@link #endStringValue()}. How it is divided into calls is arbitrary
     * and depends on how the input arrived and on the parser's buffer size,
     * so a handler must not attach any meaning to the boundaries.
     * <p>
     * The parser never passes empty content, and never splits a surrogate
     * pair across two calls.
     * <p>
     * <strong>The content is only valid until this method returns.</strong>
     * The parser reuses the underlying buffer, so a handler that needs the
     * characters afterwards must copy them (for example with
     * {@code toString()}).
     * <p>
     * Content is delivered as it is read, before the rest of the string has
     * been seen. If the string turns out to be malformed (an invalid
     * escape sequence, invalid UTF-8, an unescaped control character, or a
     * length limit exceeded), the parser throws a {@link JSONException},
     * possibly after some of the string's content has already been
     * delivered, so a handler should treat the content as provisional until
     * {@link #endStringValue()}.
     * @param content the next characters of the string
     * @throws JSONException the client may throw an exception during
     * processing
     */
    public void stringValueContent(CharSequence content) throws JSONException;

    /**
     * Indicates the end of the current JSON string value, at its closing
     * quote.
     * @throws JSONException the client may throw an exception during
     * processing
     */
    public void endStringValue() throws JSONException;

    /**
     * Notifies of a JSON boolean value.
     * @param value the boolean value
     * @throws JSONException the client may throw an exception during
     * processing
     */
    public void booleanValue(boolean value) throws JSONException;

    /**
     * Notifies of a JSON null value.
     * @throws JSONException the client may throw an exception during
     * processing
     */
    public void nullValue() throws JSONException;

    /**
     * Notifies of whitespace in the underlying stream. Note that whitespace
     * between key strings and their associated colon, if present,
     * will not be reported by this method.
     * @param whitespace the whitespace contents as a string
     * @throws JSONException the client may throw an exception during
     * processing
     */
    public void whitespace(String whitespace) throws JSONException;

    /**
     * Notifies of a key in a JSON object. This is a string followed by a
     * colon inside an object representation in the JSON source. It will
     * always be associated with the most recent previous startObject event.
     * @param key the key name
     * @throws JSONException the client may throw an exception during
     * processing
     */
    public void key(String key) throws JSONException;

    /**
     * Receive an object that can be used to determine the current location
     * during parsing. The locator allows the application to determine the
     * end position of any parsing-related event, even if the parser is not
     * reporting an error. Typically, the application will use this
     * information for reporting its own errors (such as character content
     * that does not match an application's business rules).
     * @param locator the locator object
     */
    public void setLocator(JSONLocator locator);

    /**
     * Indicates whether this handler needs to receive whitespace events.
     * By default, handlers do not need whitespace, which allows the parser
     * to skip expensive string extraction for whitespace sequences.
     * Implementations that need whitespace (e.g., for pretty-printing or
     * preserving exact formatting) should override this to return true.
     * 
     * @return true if this handler wants {@link #whitespace(String)} to be
     *         called, false otherwise (default)
     */
    default boolean needsWhitespace() {
        return false;
    }

}
