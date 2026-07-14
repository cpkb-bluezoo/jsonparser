package org.bluezoo.json.bench.adapters;

import org.json.JSONTokener;

import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Adapter for org.json (the ubiquitous "reference" JSON library). It has no
 * streaming token API, so only DOM mode is exercised.
 */
public class OrgJsonAdapter implements ParserAdapter {

    @Override
    public String name() {
        return "org.json";
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
        throw new UnsupportedOperationException("org.json has no streaming token API");
    }

    @Override
    public Object parseDom(byte[] data) throws Exception {
        try (InputStreamReader reader = new InputStreamReader(new ByteArrayInputStream(data), StandardCharsets.UTF_8)) {
            JSONTokener tokener = new JSONTokener(reader);
            return tokener.nextValue();
        }
    }
}
