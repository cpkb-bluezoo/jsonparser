package org.bluezoo.json.bench.adapters;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class GsonAdapter implements ParserAdapter {

    @Override
    public String name() {
        return "gson";
    }

    @Override
    public boolean supportsStream() {
        return true;
    }

    @Override
    public boolean supportsDom() {
        return true;
    }

    @Override
    public Object parseStream(byte[] data) throws Exception {
        long checksum = 1;
        try (JsonReader reader = new JsonReader(
                new InputStreamReader(new ByteArrayInputStream(data), StandardCharsets.UTF_8))) {
            while (true) {
                JsonToken token = reader.peek();
                switch (token) {
                    case BEGIN_OBJECT:
                        reader.beginObject();
                        checksum = checksum * 31 + 1;
                        break;
                    case END_OBJECT:
                        reader.endObject();
                        checksum = checksum * 31 + 2;
                        break;
                    case BEGIN_ARRAY:
                        reader.beginArray();
                        checksum = checksum * 31 + 3;
                        break;
                    case END_ARRAY:
                        reader.endArray();
                        checksum = checksum * 31 + 4;
                        break;
                    case NAME:
                        checksum = checksum * 31 + reader.nextName().hashCode();
                        break;
                    case STRING:
                        checksum = checksum * 31 + reader.nextString().hashCode();
                        break;
                    case NUMBER:
                        // nextDouble() forces numeric parsing of the lexical form,
                        // matching the level of work jsonparser does for numberValue().
                        checksum = checksum * 31 + Double.hashCode(reader.nextDouble());
                        break;
                    case BOOLEAN:
                        checksum = checksum * 31 + (reader.nextBoolean() ? 5 : 6);
                        break;
                    case NULL:
                        reader.nextNull();
                        checksum = checksum * 31 + 7;
                        break;
                    case END_DOCUMENT:
                        return checksum;
                    default:
                        throw new IllegalStateException("Unexpected token: " + token);
                }
            }
        }
    }

    @Override
    public Object parseDom(byte[] data) throws Exception {
        try (InputStreamReader reader = new InputStreamReader(new ByteArrayInputStream(data), StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(reader);
            return root;
        }
    }
}
