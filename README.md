# jsonparser
JSON parser and serializer for Java

This is a tiny and efficient JSON parser and serialization library for
Java. It takes up only 31KB (including the serialisation feature), as
opposed to object-mapping-based JSON parsers such as GSON (306KB) or
Jackson (580KB for streaming alone, 2.3MB with object-mapping/databind
support), and is fast and conformant. The streaming parser's memory use
does not grow with document size, but it does grow with the size of the
largest single string value or key, which is buffered until complete (see
[Memory behaviour](#memory-behaviour)).

Full JavaDoc documentation is included in the package, see the doc
subdirectory.

**[View API documentation online](https://cpkb-bluezoo.github.io/jsonparser/doc/)

## Parser

### Asynchronous, Non-Blocking API (Primary)

The `JSONParser` is designed as an **asynchronous-first, non-blocking,
data-driven parser** that integrates seamlessly into event-driven
architectures. Instead of blocking on I/O operations, it uses a **push
model** where you feed bytes to the parser as they arrive from any
source — network sockets, async file I/O, message queues, or any
streaming data pipeline.

![Event Pipeline Architecture](event-pipeline.svg)

This design makes the parser ideal for:
- **Non-blocking I/O** systems (NIO, async frameworks)
- **Event-driven architectures** (reactive streams, actors)
- **High-concurrency servers** where blocking is prohibitive
- **Data pipeline architectures** where JSON transformation is one stage
- **Memory-constrained environments** — memory use independent of document
  size (bounded by the largest string, see [Memory behaviour](#memory-behaviour))

Traditional object-mapping based JSON parsers such as GSON or Jackson, in
contrast, are **blocking** — your application thread has to block while
it processes the entire message. If the message hasn't been completely
delivered yet, your entire process has to wait. Then when it finally
produces the parse result, that result is an object taking up memory
proportional to the size of the JSON message.

With this event-based parser, memory usage does not depend on how large
the JSON document is: only your buffer, plus the parser's carry-over of
the one token currently being assembled (see
[Memory behaviour](#memory-behaviour) for what that means for large
strings). JSON semantic events are delivered before the parse is complete,
even before the message has finished arriving over the network: low
latency, and zero-copy for any token that arrives within a single chunk.

#### How It Works

The parser maintains internal state between calls and emits parsing events
via the SAX-like `JSONContentHandler` interface as soon as complete tokens
are recognized. This allows it to operate as a **streaming transformer** in
a data pipeline, converting raw byte chunks into semantic JSON events.

When a token spans chunk boundaries, the parser copies the incomplete
token's bytes into an internal `pending` buffer and prepends them to the
next chunk. `receive()` therefore always consumes the whole buffer you pass
it; calling `compact()` afterwards is harmless but not required. Tokens
that arrive within a single chunk are tokenized in place with no copy.

**Core Methods:**
- `receive(ByteBuffer data)` - Push a chunk of bytes into the parser
- `close()` - Signal end of input and validate document completeness

**Buffer Contract:**
- Provide the buffer in read mode (after `flip()`)
- After `receive()` returns, `buffer.position()` indicates unconsumed data
- If `position() < limit()`, call `compact()` before reading more data
- This zero-copy design minimizes allocations and GC pressure

#### Async Streaming Example (NIO Channel)

```java
import java.nio.*;
import java.nio.channels.*;
import org.bluezoo.json.*;

public class AsyncJSONProcessor extends JSONDefaultHandler {
    
    @Override
    public void key(String key) throws JSONException {
        System.out.println("Key: " + key);
    }
    
    @Override
    public void stringValue(String value) throws JSONException {
        System.out.println("String: " + value);
    }
    
    public static void processChannel(ReadableByteChannel channel) 
            throws Exception {
        JSONParser parser = new JSONParser();
        parser.setContentHandler(new AsyncJSONProcessor());
        
        ByteBuffer buffer = ByteBuffer.allocate(8192);
        
        while (channel.read(buffer) != -1) {
            buffer.flip();              // Switch to read mode
            parser.receive(buffer);     // Parse available data
            buffer.compact();           // Preserve unconsumed bytes
        }
        
        // Process any remaining data
        buffer.flip();
        if (buffer.hasRemaining()) {
            parser.receive(buffer);
        }
        
        parser.close();  // Validate document completeness
    }
}
```

The `compact()` / `flip()` cycle ensures that partial tokens spanning chunk
boundaries are preserved and completed when more data arrives. This pattern
is standard for NIO and works naturally with non-blocking channels,
selectors, and async I/O frameworks.

This non-blocking approach means your application can:
- Process JSON data as it arrives without waiting for complete documents
- Integrate with async I/O frameworks (Netty, Vert.x, etc.)
- Build reactive data pipelines where JSON parsing is a transformation stage
- Handle multiple concurrent JSON streams without thread-per-connection
- Process arbitrarily large documents with memory independent of document
  size (subject to the string-length caveat in
  [Memory behaviour](#memory-behaviour))

### Memory behaviour

Memory use is independent of document size, but it is **not constant**:

- Structural tokens, numbers, literals and whitespace are small and bounded
  (numbers by `setMaxNumberLength`, default 1000).
- **Strings and keys are buffered until their closing quote arrives.**
  `JSONContentHandler.stringValue(String)` and `key(String)` take a complete
  value, so a string that spans several `receive()` chunks is held in the
  internal `pending` buffer (growing by doubling) until it is complete, and
  then materialized as a `String`. Peak memory is therefore proportional to
  the largest single string or key, roughly 2-3 times its size in bytes in
  the worst case.
- Because the partial token is rescanned from its opening quote on each
  chunk, a very large string delivered in small chunks also costs
  time that grows faster than linearly with the string's length.
- By default this is capped: `setMaxStringLength` (20,000,000) and
  `setMaxNameLength` (50,000). `disableAllLimits()`, or setting those limits
  to 0, removes the cap, and a single huge string can then exhaust memory.

Truly constant-memory handling of large strings requires the handler to
receive string content incrementally (start / content chunks / end events)
instead of as one `String`. That is an incompatible change to
`JSONContentHandler` and is planned for a future major release.

### Traditional Blocking API (Convenience)

For simpler use cases where blocking I/O is acceptable, a traditional
`parse(InputStream)` method is provided as a convenience wrapper. It
internally delegates to the streaming API by reading the stream in
chunks.

#### Blocking Example

```java
import java.io.*;
import org.bluezoo.json.*;

public class ListFieldNames extends JSONDefaultHandler {
    
    public void key(String key) throws JSONException {
        System.out.println(key);
    }
    
    public static void main(String[] args) throws Exception {
        InputStream in = null;
        try {
            in = new FileInputStream(args[0]);
            JSONParser parser = new JSONParser();
            parser.setContentHandler(new ListFieldNames());
            parser.parse(in);  // Convenience method - blocks until complete
        } finally {
            in.close();
        }
    }
}
```

### Event-Driven Design

The parser follows the same event-driven pattern as the SAX API for
parsing XML. You create an implementation of the `JSONContentHandler`
interface to receive parsing events. There is a handy
`JSONDefaultHandler` class that you can subclass if you only want to
implement a subset of the methods.

Your handler will be notified of events in the JSON stream as they are
recognized:
- `startObject()` / `endObject()` - Object boundaries
- `startArray()` / `endArray()` - Array boundaries  
- `key(String)` - Object field names
- `stringValue(String)` - String values
- `numberValue(Number)` - Numeric values
- `booleanValue(boolean)` - Boolean values
- `nullValue()` - Null values
- `whitespace(String)` - Whitespace (if needed for pretty-printing)

This event-driven model allows you to build custom JSON processors
without materializing the entire document in memory—perfect for
processing large JSON streams or building transformation pipelines.

## Serializer

The serializer follows the same pattern as the javax.xml.stream API for
writing XML. You create an instance of `JSONWriter` that wraps a
`WritableByteChannel` or `OutputStream`. You then call the JSON value
construction methods, in order, to construct the JSON output
representation.

The `JSONWriter` uses an **NIO-first design** with automatic buffering
and chunking. Data is automatically sent to the channel when the buffer
reaches a threshold, making it ideal for streaming output scenarios.

You can configure indentation using `IndentConfig` to beautify the output
and make it more easily human-readable. If no indent configuration is
provided, the output is compact (no unnecessary whitespace).

### Example

Here is an example to create a simple JSON file:

```java
import java.io.*;
import org.bluezoo.json.*;

public class MakeJohnSmith {

    public static void main(String[] args) throws Exception {
        JSONWriter writer = new JSONWriter(System.out, 
                                           IndentConfig.tabs());
        writer.writeStartObject();
        writer.writeKey("first_name");
        writer.writeString("John");
        writer.writeKey("last_name");
        writer.writeString("Smith");
        writer.writeKey("is_alive");
        writer.writeBoolean(true);
        writer.writeKey("age");
        writer.writeNumber(27);
        writer.writeKey("height");
        writer.writeNumber(6.01);
        writer.writeKey("address");
        writer.writeStartObject();
        writer.writeKey("street_address");
        writer.writeString("21 2nd Street");
        writer.writeKey("city");
        writer.writeString("New York");
        writer.writeEndObject(); // address
        writer.writeKey("phone_numbers");
        writer.writeStartArray();
        writer.writeString("212 555-1234");
        writer.writeString("646 555-4567");
        writer.writeEndArray();
        writer.writeKey("spouse");
        writer.writeNull();
        writer.writeEndObject(); // top level object
        writer.close();
    }

}
```

## Installation

An Apache Ant build file is included.

```bash
git clone https://github.com/cpkb-bluezoo/jsonparser.git
cd jsonparser
ant dist
```

This will create a jar file in the dist subdirectory that you can add to
your classpath.

## Conformance

The parser has been tested with
[JSONTestSuite](https://github.com/nst/JSONTestSuite) and is fully
conformant with that test suite.

## Performance

jsonparser is benchmarked against Gson, Jackson, org.json, and minimal-json
across a corpus of JSON documents varying in size, character set (ASCII vs
full Unicode), and content shape (number-heavy, string-heavy, deeply
nested, wide flat objects, and real-world files). It comes out fastest
overall:

![Benchmark comparison: total parse time across a varied JSON corpus, by library and mode - smaller is better](benchmarks/benchmark-comparison.svg)

See [benchmarks/](benchmarks/README.md) for the full methodology and how
to reproduce these figures yourself.

Size matters too - a smaller jar means less to download, less to load,
and less attack surface. Here's how the benchmarked libraries compare:

| Library | Jar(s) | Size |
|---|---|---|
| jsonparser 1.3 | jsonparser | 31 KB |
| minimal-json 0.9.5 | minimal-json | 33 KB |
| org.json 20260522 | json | 87 KB |
| Gson 2.14.0 | gson | 306 KB |
| Jackson 2.22 (streaming only) | jackson-core | 580 KB |
| Jackson 2.22 (with databind) | jackson-core + jackson-databind + jackson-annotations | 2.3 MB |

## Maven integration

You can incorporate this project directly into your Maven project. To do
so, add the following elements to your project's pom.xml:

```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>

<dependencies>
    <dependency>
        <groupId>com.github.cpkb-bluezoo</groupId>
        <artifactId>jsonparser</artifactId>
        <version>1.3</version>
    </dependency>
</dependencies>
```

## License

This project is licensed under the GNU Lesser General Public License v3.0
(LGPLv3). See the [COPYING](COPYING) file for details.

