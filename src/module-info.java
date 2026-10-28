/**
 * JSON parser and serializer module.
 * 
 * <p>Provides a streaming, event-driven JSON parser and writer whose memory
 * use does not grow with document size (string values and keys are buffered
 * until complete, so it is bounded by the largest single string). The parser
 * uses a push model where bytes are fed via
 * {@link org.bluezoo.json.JSONParser#receive(java.nio.ByteBuffer)} and events
 * are delivered via {@link org.bluezoo.json.JSONContentHandler}.
 * 
 * @see org.bluezoo.json.JSONParser
 * @see org.bluezoo.json.JSONWriter
 */
module org.bluezoo.json {
    exports org.bluezoo.json;
}

