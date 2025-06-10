package org.bluezoo.json;

/**
 * An exception thrown to indicate an anomaly during JSON parsing.
 *
 * @author Chris Burdess
 */
public class JSONException extends Exception {

    /**
     * Constructor for a JSON exception with no message.
     */
    public JSONException() {
    }

    /**
     * Constructor for a JSON exception with the specified message.
     * @param message the exception message
     */
    public JSONException(String message) {
        super(message);
    }

    /**
     * Constructor for a JSON exception with the specified message and
     * underlying cause.
     * @param message the exception message
     * @param cause the underlying cause of this message
     */
    public JSONException(String message, Throwable cause) {
        super(message);
        initCause(cause);
    }

}
