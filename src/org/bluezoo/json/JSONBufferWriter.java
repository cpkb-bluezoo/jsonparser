package org.bluezoo.json;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * JSON writer that writes to an internal ByteBuffer.
 * <p>
 * Designed for streaming use cases where the caller periodically drains
 * the buffer and submits chunks. The buffer automatically grows if needed
 * to accommodate a single write operation.
 *
 * <h2>Usage Pattern</h2>
 * <pre>{@code
 * JSONBufferWriter writer = new JSONBufferWriter(4096);
 * 
 * writer.writeStartObject();
 * writer.writeKey("urls");
 * writer.writeStartArray();
 * 
 * for (String url : urls) {
 *     writer.writeString(url);
 *     
 *     // Periodically drain to avoid memory buildup
 *     if (writer.shouldDrain()) {
 *         ByteBuffer chunk = writer.drainBuffer();
 *         publisher.submit(chunk);
 *     }
 * }
 * 
 * writer.writeEndArray();
 * writer.writeEndObject();
 * 
 * // Final drain
 * if (writer.hasContent()) {
 *     publisher.submit(writer.drainBuffer());
 * }
 * }</pre>
 *
 * <h2>Thread Safety</h2>
 * <p>
 * This class is NOT thread-safe. It is intended for use on a single thread.
 */
public class JSONBufferWriter {

    private static final byte[] TRUE_BYTES = "true".getBytes(StandardCharsets.UTF_8);
    private static final byte[] FALSE_BYTES = "false".getBytes(StandardCharsets.UTF_8);
    private static final byte[] NULL_BYTES = "null".getBytes(StandardCharsets.UTF_8);

    private static final int DEFAULT_CAPACITY = 4096;
    private static final float DRAIN_THRESHOLD = 0.75f;

    private ByteBuffer buffer;
    private final int drainThreshold;

    private enum State { INIT, AFTER_KEY, AFTER_VALUE, IN_ARRAY, IN_OBJECT }
    private State state = State.INIT;
    private int depth = 0;

    /**
     * Creates a new JSON buffer writer with default capacity (4KB).
     */
    public JSONBufferWriter() {
        this(DEFAULT_CAPACITY);
    }

    /**
     * Creates a new JSON buffer writer with specified initial capacity.
     *
     * @param initialCapacity initial buffer capacity in bytes
     */
    public JSONBufferWriter(int initialCapacity) {
        this.buffer = ByteBuffer.allocate(initialCapacity);
        this.drainThreshold = (int) (initialCapacity * DRAIN_THRESHOLD);
    }

    /**
     * Writes the start of a JSON object '{'.
     */
    public void writeStartObject() {
        writeValueSeparatorIfNeeded();
        ensureCapacity(1);
        buffer.put((byte) '{');
        state = State.IN_OBJECT;
        depth++;
    }

    /**
     * Writes the end of a JSON object '}'.
     */
    public void writeEndObject() {
        ensureCapacity(1);
        buffer.put((byte) '}');
        depth--;
        state = depth > 0 ? State.AFTER_VALUE : State.INIT;
    }

    /**
     * Writes the start of a JSON array '['.
     */
    public void writeStartArray() {
        writeValueSeparatorIfNeeded();
        ensureCapacity(1);
        buffer.put((byte) '[');
        state = State.IN_ARRAY;
        depth++;
    }

    /**
     * Writes the end of a JSON array ']'.
     */
    public void writeEndArray() {
        ensureCapacity(1);
        buffer.put((byte) ']');
        depth--;
        state = depth > 0 ? State.AFTER_VALUE : State.INIT;
    }

    /**
     * Writes an object key (property name).
     *
     * @param key the key name
     */
    public void writeKey(String key) {
        if (state == State.AFTER_VALUE) {
            ensureCapacity(1);
            buffer.put((byte) ',');
        }
        writeQuotedString(key);
        ensureCapacity(1);
        buffer.put((byte) ':');
        state = State.AFTER_KEY;
    }

    /**
     * Writes a string value.
     *
     * @param value the string value
     */
    public void writeString(String value) {
        writeValueSeparatorIfNeeded();
        writeQuotedString(value);
        state = State.AFTER_VALUE;
    }

    /**
     * Writes a number value.
     *
     * @param value the number value
     */
    public void writeNumber(Number value) {
        writeValueSeparatorIfNeeded();
        byte[] bytes = value.toString().getBytes(StandardCharsets.UTF_8);
        ensureCapacity(bytes.length);
        buffer.put(bytes);
        state = State.AFTER_VALUE;
    }

    /**
     * Writes a boolean value.
     *
     * @param value the boolean value
     */
    public void writeBoolean(boolean value) {
        writeValueSeparatorIfNeeded();
        byte[] bytes = value ? TRUE_BYTES : FALSE_BYTES;
        ensureCapacity(bytes.length);
        buffer.put(bytes);
        state = State.AFTER_VALUE;
    }

    /**
     * Writes a null value.
     */
    public void writeNull() {
        writeValueSeparatorIfNeeded();
        ensureCapacity(NULL_BYTES.length);
        buffer.put(NULL_BYTES);
        state = State.AFTER_VALUE;
    }

    /**
     * Returns whether the buffer should be drained.
     * <p>
     * Returns true when buffer is more than 75% full.
     *
     * @return true if buffer should be drained
     */
    public boolean shouldDrain() {
        return buffer.position() >= drainThreshold;
    }

    /**
     * Returns whether the buffer has any content.
     *
     * @return true if buffer has content
     */
    public boolean hasContent() {
        return buffer.position() > 0;
    }

    /**
     * Returns the number of bytes written to the buffer.
     *
     * @return bytes written
     */
    public int size() {
        return buffer.position();
    }

    /**
     * Drains the buffer content and returns it as a new ByteBuffer.
     * <p>
     * The internal buffer is cleared after this call.
     *
     * @return a new ByteBuffer containing the written content, ready for reading
     */
    public ByteBuffer drainBuffer() {
        buffer.flip();
        ByteBuffer result = ByteBuffer.allocate(buffer.remaining());
        result.put(buffer);
        result.flip();
        buffer.clear();
        return result;
    }

    /**
     * Returns the buffer content without clearing.
     * <p>
     * The returned buffer is flipped and ready for reading.
     * Call {@link #clear()} after consuming.
     *
     * @return the internal buffer, flipped for reading
     */
    public ByteBuffer getBuffer() {
        buffer.flip();
        return buffer;
    }

    /**
     * Clears the buffer for reuse.
     */
    public void clear() {
        buffer.clear();
    }

    private void writeValueSeparatorIfNeeded() {
        if (state == State.AFTER_VALUE) {
            ensureCapacity(1);
            buffer.put((byte) ',');
        }
    }

    private void writeQuotedString(String s) {
        // Estimate size: quotes + string + some escapes
        int estimatedSize = 2 + s.length() + (s.length() / 10);
        ensureCapacity(estimatedSize);

        buffer.put((byte) '"');

        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);

            // Check if we need more space (worst case: 6 bytes for unicode escape)
            if (buffer.remaining() < 6) {
                growBuffer(buffer.capacity() * 2);
            }

            switch (c) {
                case '"':
                    buffer.put((byte) '\\');
                    buffer.put((byte) '"');
                    break;
                case '\\':
                    buffer.put((byte) '\\');
                    buffer.put((byte) '\\');
                    break;
                case '\b':
                    buffer.put((byte) '\\');
                    buffer.put((byte) 'b');
                    break;
                case '\f':
                    buffer.put((byte) '\\');
                    buffer.put((byte) 'f');
                    break;
                case '\n':
                    buffer.put((byte) '\\');
                    buffer.put((byte) 'n');
                    break;
                case '\r':
                    buffer.put((byte) '\\');
                    buffer.put((byte) 'r');
                    break;
                case '\t':
                    buffer.put((byte) '\\');
                    buffer.put((byte) 't');
                    break;
                default:
                    if (c < 0x20) {
                        // Control character - escape as unicode
                        buffer.put((byte) '\\');
                        buffer.put((byte) 'u');
                        buffer.put((byte) '0');
                        buffer.put((byte) '0');
                        buffer.put((byte) hexChar((c >> 4) & 0xF));
                        buffer.put((byte) hexChar(c & 0xF));
                    } else if (c < 0x80) {
                        // ASCII
                        buffer.put((byte) c);
                    } else {
                        // Non-ASCII - encode as UTF-8
                        writeUtf8Char(c);
                    }
            }
        }

        buffer.put((byte) '"');
    }

    private void writeUtf8Char(char c) {
        if (c < 0x800) {
            // 2-byte UTF-8
            buffer.put((byte) (0xC0 | (c >> 6)));
            buffer.put((byte) (0x80 | (c & 0x3F)));
        } else {
            // 3-byte UTF-8
            buffer.put((byte) (0xE0 | (c >> 12)));
            buffer.put((byte) (0x80 | ((c >> 6) & 0x3F)));
            buffer.put((byte) (0x80 | (c & 0x3F)));
        }
    }

    private char hexChar(int n) {
        return (char) (n < 10 ? '0' + n : 'a' + (n - 10));
    }

    private void ensureCapacity(int needed) {
        if (buffer.remaining() < needed) {
            growBuffer(Math.max(buffer.capacity() * 2, buffer.position() + needed));
        }
    }

    private void growBuffer(int newCapacity) {
        ByteBuffer newBuffer = ByteBuffer.allocate(newCapacity);
        buffer.flip();
        newBuffer.put(buffer);
        buffer = newBuffer;
    }

}

