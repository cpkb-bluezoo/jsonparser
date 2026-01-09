/**
 * JSON parser and serializer module.
 * 
 * <p>Provides a streaming, event-driven JSON parser and writer that operates
 * in constant memory. The parser uses a push model where bytes are fed via
 * {@link org.bluezoo.json.JSONParser#receive(java.nio.ByteBuffer)} and events
 * are delivered via {@link org.bluezoo.json.JSONContentHandler}.
 * 
 * @see org.bluezoo.json.JSONParser
 * @see org.bluezoo.json.JSONWriter
 */
module org.bluezoo.json {
    exports org.bluezoo.json;
}

