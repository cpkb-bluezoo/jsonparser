package org.bluezoo.json;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Class to serialize JSON.
 * This class is based on the design of the XMLStreamWriter interface in the
 * javax.xml.stream package.
 * This class does not perform well-formedness checking on its input: the
 * user of the class is required to supply events in the correct order and
 * close objects and arrays they open. However, it will escape characters
 * supplied in string data.
 * Additionally, the user may request that the serializer beautify the
 * output by indenting it to make it easier for humans to read it. If this
 * option is not specifed (the default), no unnecessary whitespace will be
 * emitted and the resulting representation will be optimized for size.
 *
 * @author Chris Burdess
 */
public class JSONStreamWriter {

    enum State { INIT, SEEN_KEY, SEEN_VALUE };

    private final OutputStream out;
    private final boolean indent;
    private int depth;
    private State state = State.INIT;

    /**
     * Constructor for a JSON serializer.
     * @param out the underlying output stream to write to
     */
    public JSONStreamWriter(OutputStream out) {
        this(out, false);
    }

    /**
     * Constructor for a JSON serializer with indenting if necessary.
     * @param out the underlying output stream to write to
     * @param indent if true, add whitespace to indent the output
     */
    public JSONStreamWriter(OutputStream out, boolean indent) {
        this.out = out;
        this.indent = indent;
    }

    private void startValue() throws IOException {
        switch (state) {
            case SEEN_KEY:
                out.write(' ');
                break;
            case SEEN_VALUE:
                out.write(',');
                // NB fall through
            case INIT:
                out.write('\n');
                for (int i = 0; i < depth; i++) {
                    out.write('\t');
                }
        }
    }

    /**
     * Write the start of a JSON object. This must be matched by a
     * corresponding end object later in the sequence of events.
     * @throws IOException if there was an error writing to the underlying
     * stream
     */
    public void writeStartObject() throws IOException {
        if (indent) {
            startValue();
        }
        out.write('{');
        depth++;
        state = State.INIT;
    }

    /**
     * Write the end of a JSON object.
     * @throws IOException if there was an error writing to the underlying
     * stream
     */
    public void writeEndObject() throws IOException {
        depth--;
        if (indent) {
            out.write('\n');
            for (int i = 0; i < depth; i++) {
                out.write('\t');
            }
        }
        out.write('}');
        state = State.SEEN_VALUE;
    }

    /**
     * Write the start of a JSON array. This must be matched by a
     * corresponding end array later in the sequence of events.
     * @throws IOException if there was an error writing to the underlying
     * stream
     */
    public void writeStartArray() throws IOException {
        if (indent) {
            startValue();
        }
        out.write('[');
        depth++;
        state = State.INIT;
    }

    /**
     * Write the end of a JSON array.
     * @throws IOException if there was an error writing to the underlying
     * stream
     */
    public void writeEndArray() throws IOException {
        depth--;
        if (indent) {
            out.write('\n');
            for (int i = 0; i < depth; i++) {
                out.write('\t');
            }
        }
        out.write(']');
        state = State.SEEN_VALUE;
    }

    /**
     * Write a number value.
     * @param value the number value
     * @throws IOException if there was an error writing to the underlying
     * stream
     */
    public void writeNumber(Number value) throws IOException {
        if (indent) {
            startValue();
        }
        writeLiteral(value.toString());
        state = State.SEEN_VALUE;
    }

    /**
     * Write a string value.
     * @param value the string value
     * @throws IOException if there was an error writing to the underlying
     * stream
     */
    public void writeString(String value) throws IOException {
        if (indent) {
            startValue();
        }
        out.write('"');
        writeLiteral(escape(value));
        out.write('"');
        state = State.SEEN_VALUE;
    }

    /**
     * Write a boolean value.
     * @param value the boolean value
     * @throws IOException if there was an error writing to the underlying
     * stream
     */
    public void writeBoolean(boolean value) throws IOException {
        if (indent) {
            startValue();
        }
        writeLiteral(value ? "true" : "false");
        state = State.SEEN_VALUE;
    }

    /**
     * Write a null value.
     * @throws IOException if there was an error writing to the underlying
     * stream
     */
    public void writeNull() throws IOException {
        if (indent) {
            startValue();
        }
        writeLiteral("null");
        state = State.SEEN_VALUE;
    }

    /**
     * Write a JSON object field key. This will output the key name escaped
     * as a string plus the following colon. The value of the field must
     * then be added using other methods.
     * @param key the key name
     * @throws IOException if there was an error writing to the underlying
     * stream
     */
    public void writeKey(String key) throws IOException {
        if (indent) {
            startValue();
        }
        out.write('"');
        writeLiteral(escape(key));
        out.write('"');
        out.write(':');
        state = State.SEEN_KEY;
    }

    private void writeLiteral(String value) throws IOException {
        // All strings have been escaped so this is safe
        out.write(value.getBytes());
    }

    private String escape(String s) {
        // We will only create a buffer to store the escaped version
        // if there are any characters that need escaping
        StringBuilder buf = null;
        int len = s.length();
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\' || c < 0x20 || c >= 0x7f) {
                if (buf == null) {
                    buf = new StringBuilder(s.substring(0, i));
                }
                if (c == '"') {
                    buf.append("\"");
                } else if (c == '\\') {
                    buf.append("\\\\");
                } else if (c == '\n') {
                    buf.append("\\n");
                } else if (c == '\r') {
                    buf.append("\\r");
                } else if (c == '\t') {
                    buf.append("\\t");
                } else if (c == '\f') {
                    buf.append("\\f");
                } else if (c == '\b') {
                    buf.append("\\b");
                } else {
                    buf.append(String.format("\\u%04x", Integer.valueOf((int) c)));
                }
            } else if (buf != null) {
                buf.append(c);
            }
        }
        return (buf == null) ? s : buf.toString();
    }

}
