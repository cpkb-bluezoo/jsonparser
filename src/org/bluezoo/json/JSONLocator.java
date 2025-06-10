package org.bluezoo.json;

/**
 * Locator information for JSON parser.
 * This can be used to retrieve the location in the JSON stream at which
 * parsing events were reported, including errors. This information is
 * transient and only valid at the time the event was fired or the
 * exception was thrown.
 * Note that JSON is a standalone file format.
 * Both line and column numbers start at 1.
 *
 * @author Chris Burdess
 */
public interface JSONLocator {

    /**
     * Returns the line number currently being processed.
     * The first line is line 1.
     * @return the current line number
     */
    public int getLineNumber();

    /**
     * Returns the column number currently being processed.
     * The first column in each line is column 1.
     * @return the current column number
     */
    public int getColumnNumber();

}
