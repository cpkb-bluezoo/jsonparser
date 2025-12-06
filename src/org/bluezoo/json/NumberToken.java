/*
 * NumberToken.java
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

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.io.IOException;

/**
 * Token representing a number.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class NumberToken extends Token {

    final Number number;

    NumberToken(Number number) {
        super(Token.Type.NUMBER);
        this.number = number;
    }

    public String toString() {
        return number.toString();
    }

}
