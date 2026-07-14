package org.bluezoo.json.bench.adapters;

/**
 * Common interface implemented by each competing JSON library so the
 * benchmark harness can drive them all identically.
 *
 * <p>Two parsing modes are modelled, matching how these libraries are
 * actually used in practice:
 * <ul>
 *   <li><b>stream</b> - walk every token via a low-level streaming/pull or
 *       push API without materializing a tree (e.g. Jackson's JsonParser,
 *       Gson's JsonReader, this project's JSONContentHandler). Not every
 *       library offers this.</li>
 *   <li><b>dom</b> - parse the whole document into an in-memory tree
 *       (e.g. Jackson's ObjectMapper#readTree, Gson's JsonElement,
 *       org.json's JSONObject/JSONArray). Not every library offers a
 *       streaming alternative, so this is the only mode available for some.</li>
 * </ul>
 *
 * <p>Each parse method returns the constructed object (or an accumulated
 * checksum for streaming mode). The caller is expected to store the result
 * in a volatile sink field to prevent the JIT from eliminating the call as
 * dead code.
 */
public interface ParserAdapter {

    String name();

    boolean supportsStream();

    boolean supportsDom();

    Object parseStream(byte[] data) throws Exception;

    Object parseDom(byte[] data) throws Exception;
}
