/*
 * JSONPrettyPrinter.java
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

import java.io.FileInputStream;
import java.io.InputStream;
import java.io.IOException;

/**
 * A command-line utility that demonstrates how to use the JSON parser
 * to pretty-print JSON files. This example shows the SAX-like parsing
 * pattern where events are captured and replayed through the stream writer.
 *
 * Usage: java org.bluezoo.json.JSONPrettyPrinter <input-file>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class JSONPrettyPrinter extends JSONDefaultHandler {

    private JSONStreamWriter writer;

    /**
     * Constructor that initializes the pretty-printing stream writer.
     */
    public JSONPrettyPrinter() {
        // Create a writer that outputs to stdout with indentation enabled
        this.writer = new JSONStreamWriter(System.out, true);
    }

    @Override
    public void startObject() throws JSONException {
        try {
            writer.writeStartObject();
        } catch (IOException e) {
            throw new JSONException("Error writing start object: " + e.getMessage());
        }
    }

    @Override
    public void endObject() throws JSONException {
        try {
            writer.writeEndObject();
        } catch (IOException e) {
            throw new JSONException("Error writing end object: " + e.getMessage());
        }
    }

    @Override
    public void startArray() throws JSONException {
        try {
            writer.writeStartArray();
        } catch (IOException e) {
            throw new JSONException("Error writing start array: " + e.getMessage());
        }
    }

    @Override
    public void endArray() throws JSONException {
        try {
            writer.writeEndArray();
        } catch (IOException e) {
            throw new JSONException("Error writing end array: " + e.getMessage());
        }
    }

    @Override
    public void key(String key) throws JSONException {
        try {
            writer.writeKey(key);
        } catch (IOException e) {
            throw new JSONException("Error writing key: " + e.getMessage());
        }
    }

    @Override
    public void numberValue(Number number) throws JSONException {
        try {
            writer.writeNumber(number);
        } catch (IOException e) {
            throw new JSONException("Error writing number: " + e.getMessage());
        }
    }

    @Override
    public void stringValue(String value) throws JSONException {
        try {
            writer.writeString(value);
        } catch (IOException e) {
            throw new JSONException("Error writing string: " + e.getMessage());
        }
    }

    @Override
    public void booleanValue(boolean value) throws JSONException {
        try {
            writer.writeBoolean(value);
        } catch (IOException e) {
            throw new JSONException("Error writing boolean: " + e.getMessage());
        }
    }

    @Override
    public void nullValue() throws JSONException {
        try {
            writer.writeNull();
        } catch (IOException e) {
            throw new JSONException("Error writing null: " + e.getMessage());
        }
    }

    // Note: We don't override whitespace() - we want to discard the original
    // whitespace and let the writer generate its own pretty-printed formatting

    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("Usage: java org.bluezoo.json.JSONPrettyPrinter <input-file>");
            System.exit(1);
        }

        InputStream in = null;
        try {
            // Open the input file
            in = new FileInputStream(args[0]);

            // Create a parser and register our handler
            JSONParser parser = new JSONParser();
            parser.setContentHandler(new JSONPrettyPrinter());

            // Parse the input - the handler will write pretty-printed output to stdout
            parser.parse(in);

            // Add a final newline for clean terminal output
            System.out.println();
            System.out.flush();

        } catch (IOException e) {
            System.err.println("I/O error: " + e.getMessage());
            System.exit(2);
        } catch (JSONException e) {
            System.err.println("JSON parsing error: " + e.getMessage());
            System.exit(3);
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException e) {
                    // Ignore close errors
                }
            }
        }
    }

}



