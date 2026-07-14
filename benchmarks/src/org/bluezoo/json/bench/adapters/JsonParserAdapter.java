package org.bluezoo.json.bench.adapters;

import org.bluezoo.json.JSONDefaultHandler;
import org.bluezoo.json.JSONParser;

import java.nio.ByteBuffer;

/**
 * Adapter for the library under test: org.bluezoo.json (jsonparser).
 * It is streaming (push model) only - there is no DOM/tree API.
 */
public class JsonParserAdapter implements ParserAdapter {

    @Override
    public String name() {
        return "jsonparser";
    }

    @Override
    public boolean supportsStream() {
        return true;
    }

    @Override
    public boolean supportsDom() {
        return false;
    }

    @Override
    public Object parseStream(byte[] data) throws Exception {
        ChecksumHandler handler = new ChecksumHandler();
        JSONParser parser = new JSONParser();
        parser.disableAllLimits();
        parser.setContentHandler(handler);
        parser.receive(ByteBuffer.wrap(data));
        parser.close();
        return handler.checksum;
    }

    @Override
    public Object parseDom(byte[] data) {
        throw new UnsupportedOperationException("jsonparser has no DOM mode");
    }

    private static final class ChecksumHandler extends JSONDefaultHandler {
        long checksum = 1;

        @Override
        public void startObject() {
            checksum = checksum * 31 + 1;
        }

        @Override
        public void endObject() {
            checksum = checksum * 31 + 2;
        }

        @Override
        public void startArray() {
            checksum = checksum * 31 + 3;
        }

        @Override
        public void endArray() {
            checksum = checksum * 31 + 4;
        }

        @Override
        public void numberValue(Number number) {
            checksum = checksum * 31 + number.hashCode();
        }

        @Override
        public void stringValue(String value) {
            checksum = checksum * 31 + value.hashCode();
        }

        @Override
        public void booleanValue(boolean value) {
            checksum = checksum * 31 + (value ? 5 : 6);
        }

        @Override
        public void nullValue() {
            checksum = checksum * 31 + 7;
        }

        @Override
        public void key(String key) {
            checksum = checksum * 31 + key.hashCode();
        }
    }
}
