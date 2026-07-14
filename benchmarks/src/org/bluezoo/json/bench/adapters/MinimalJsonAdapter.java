package org.bluezoo.json.bench.adapters;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonValue;

import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Adapter for minimal-json - a small footprint DOM-only library, philosophically
 * the closest published competitor to this project's size goals.
 */
public class MinimalJsonAdapter implements ParserAdapter {

    @Override
    public String name() {
        return "minimal-json";
    }

    @Override
    public boolean supportsStream() {
        return false;
    }

    @Override
    public boolean supportsDom() {
        return true;
    }

    @Override
    public Object parseStream(byte[] data) {
        throw new UnsupportedOperationException("minimal-json has no streaming token API");
    }

    @Override
    public Object parseDom(byte[] data) throws Exception {
        try (InputStreamReader reader = new InputStreamReader(new ByteArrayInputStream(data), StandardCharsets.UTF_8)) {
            JsonValue root = Json.parse(reader);
            return root;
        }
    }
}
