package org.bluezoo.json.bench.adapters;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;

public class JacksonAdapter implements ParserAdapter {

    private static final JsonFactory FACTORY = new JsonFactory();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String name() {
        return "jackson";
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
        try (JsonParser parser = FACTORY.createParser(new ByteArrayInputStream(data))) {
            JsonToken token;
            while ((token = parser.nextToken()) != null) {
                switch (token) {
                    case START_OBJECT:
                        checksum = checksum * 31 + 1;
                        break;
                    case END_OBJECT:
                        checksum = checksum * 31 + 2;
                        break;
                    case START_ARRAY:
                        checksum = checksum * 31 + 3;
                        break;
                    case END_ARRAY:
                        checksum = checksum * 31 + 4;
                        break;
                    case FIELD_NAME:
                        checksum = checksum * 31 + parser.currentName().hashCode();
                        break;
                    case VALUE_STRING:
                        checksum = checksum * 31 + parser.getText().hashCode();
                        break;
                    case VALUE_NUMBER_INT:
                    case VALUE_NUMBER_FLOAT:
                        checksum = checksum * 31 + Double.hashCode(parser.getDoubleValue());
                        break;
                    case VALUE_TRUE:
                        checksum = checksum * 31 + 5;
                        break;
                    case VALUE_FALSE:
                        checksum = checksum * 31 + 6;
                        break;
                    case VALUE_NULL:
                        checksum = checksum * 31 + 7;
                        break;
                    default:
                        break;
                }
            }
        }
        return checksum;
    }

    @Override
    public Object parseDom(byte[] data) throws Exception {
        JsonNode root = MAPPER.readTree(data);
        return root;
    }
}
