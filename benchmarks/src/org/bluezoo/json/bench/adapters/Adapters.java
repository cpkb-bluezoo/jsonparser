package org.bluezoo.json.bench.adapters;

import java.util.LinkedHashMap;
import java.util.Map;

/** Registry of all libraries under comparison. */
public final class Adapters {

    private Adapters() {
    }

    public static Map<String, ParserAdapter> all() {
        Map<String, ParserAdapter> map = new LinkedHashMap<>();
        map.put("jsonparser", new JsonParserAdapter());
        map.put("gson", new GsonAdapter());
        map.put("jackson", new JacksonAdapter());
        map.put("org.json", new OrgJsonAdapter());
        map.put("minimal-json", new MinimalJsonAdapter());
        return map;
    }
}
