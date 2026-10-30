/*
 * JSONLocator.java
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
 * Locator information for JSON parser.
 * This can be used to retrieve the location in the JSON stream at which
 * parsing events were reported. This information is transient and only valid
 * at the time the event was fired.
 * <p>
 * This follows the rule for {@code org.xml.sax.Locator}. The location is
 * where the event <em>ends</em>: the line and column of the first character
 * after the text associated with the event. Line and column numbers both start
 * at 1, a column being the number of Java {@code char} values (UTF-16 units)
 * since the last line end, plus one. A line end is CR, LF or CRLF.
 * <p>
 * For example, in the document {@code [1]} the start of the array is reported
 * at line 1, column 2 (just after the {@code [}), the number at column 3 and
 * the end of the array at column 4. An object key is reported just after its
 * closing quote, a string value as its opening quote, each piece of its
 * content and its closing quote, and a number just after its last digit
 * (although it is not reported until the next byte, or the end of the
 * document, shows that it has ended).
 * <p>
 * As in SAX, the value is only an approximation intended for diagnostics, for
 * example when characters outside the Basic Multilingual Plane, wide
 * characters or bidirectional text make a column differ from a text editor's
 * display.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface JSONLocator {

    /**
     * Returns the line number where the current event ends.
     * The first line is line 1.
     * @return the current line number
     */
    public int getLineNumber();

    /**
     * Returns the column number where the current event ends.
     * This is a one-based number of Java {@code char} values since the last
     * line end: the first column in each line is column 1.
     * @return the current column number
     */
    public int getColumnNumber();

}
