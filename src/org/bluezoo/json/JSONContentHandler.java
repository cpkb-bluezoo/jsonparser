package org.bluezoo.json;

/**
 * This is the main interface to be implemented by an application wanting to
 * receive JSON parsing events. An application can implement this interface
 * and register the implementation with the JSONParser using the
 * setContentHandler method.
 *
 * @author Chris Burdess
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
     * @param number the number parsed: may be an Integer, Long, or Double
     * @throws JSONException the client may throw an exception during
     * processing
     */
    public void numberValue(Number number) throws JSONException;

    /**
     * Notifies of a JSON string.
     * @param value the string value, unescaped and unquoted
     * @throws JSONException the client may throw an exception during
     * processing
     */
    public void stringValue(String value) throws JSONException;

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

}
