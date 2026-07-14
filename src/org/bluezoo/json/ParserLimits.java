/*
 * ParserLimits.java
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
 * Configurable limits guarding against resource-exhaustion vectors in
 * untrusted JSON input (deeply nested structures, gigantic strings/numbers/
 * keys, huge documents, or huge flat collections). Defaults match Jackson's
 * {@code StreamReadConstraints} - the de facto industry reference for this
 * exact problem, added there specifically in response to real-world CVEs
 * about this class of issue.
 * <p>
 * A limit value {@code <= 0} disables that check entirely.
 * <p>
 * Owned by {@link JSONParser} and shared (by reference) with every
 * {@link JSONTokenizer} it creates, so a limit changed via one of
 * {@code JSONParser}'s setters takes effect immediately.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class ParserLimits {

    /** Maximum object/array nesting depth. Jackson default: 1000. */
    int maxNestingDepth = 1000;

    /** Maximum number of characters in a single number token. Jackson default: 1000. */
    int maxNumberLength = 1000;

    /** Maximum number of characters in a single string value. Jackson default: 20,000,000. */
    int maxStringLength = 20_000_000;

    /** Maximum number of characters in a single object key. Jackson default: 50,000. */
    int maxNameLength = 50_000;

    /** Maximum total bytes in one document. Unlimited (0) by default - matches Jackson. */
    long maxDocumentLength = 0;

    /** Maximum total number of tokens in one document. Unlimited (0) by default - matches Jackson. */
    long maxTokenCount = 0;

    /** Disables every limit (sets all six to 0) - for trusted input / internal testing and benchmarking. */
    void disableAll() {
        maxNestingDepth = 0;
        maxNumberLength = 0;
        maxStringLength = 0;
        maxNameLength = 0;
        maxDocumentLength = 0;
        maxTokenCount = 0;
    }
}
