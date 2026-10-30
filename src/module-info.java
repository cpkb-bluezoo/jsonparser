/**
 * JSON parser and serializer module.
 * 
 * <p>Provides a streaming, event-driven JSON parser and writer whose memory
 * use does not grow with the size of the document or of any string in it: the
 * parser holds none of the document's bytes, and string values are streamed
 * to the handler through a fixed-size buffer. The parser uses a push model
 * where bytes are fed via
 * {@link org.bluezoo.json.JSONParser#receive(java.nio.ByteBuffer)} and events
 * are delivered via {@link org.bluezoo.json.JSONContentHandler}.
 * 
 * @see org.bluezoo.json.JSONParser
 * @see org.bluezoo.json.JSONWriter
 */
module org.bluezoo.json {
    exports org.bluezoo.json;
}

