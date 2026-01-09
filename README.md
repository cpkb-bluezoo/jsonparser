# jsonparser
JSON parser and serializer for Java

This is a tiny and efficient JSON parser and serialization library for
Java. It takes up only 26KB (including the serialisation feature), as
opposed to object-mapping-based JSON parsers such as GSON (290KB) or
Jackson (600KB), and is fast and conformant. The streaming parser operates
in **constant memory** regardless of document size.

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
- **Memory-constrained environments** — operates in constant memory

Traditional object-mapping based JSON parsers such as GSON or Jackson, in
contrast, are **blocking** — your application thread has to block while
it processes the entire message. If the message hasn't been completely
delivered yet, your entire process has to wait. Then when it finally
produces the parse result, that result is an object taking up memory
proportional to the size of the JSON message.

With this event-based parser, memory usage is **constant** — only your
buffer size, regardless of how large the JSON document is. You control
the buffer, there is no internal buffering, and JSON semantic events are
delivered before the parse is complete, even before the message has
finished arriving over the network: near-zero latency, zero-copy design.

#### How It Works

The parser maintains internal state between calls and emits parsing events
via the SAX-like `JSONContentHandler` interface as soon as complete tokens
are recognized. This allows it to operate as a **streaming transformer** in
a data pipeline, converting raw byte chunks into semantic JSON events.

The parser operates in **constant memory** — it does not buffer incomplete
tokens internally. Instead, when a token spans chunk boundaries, the parser
leaves unconsumed bytes in the buffer (underflow). The caller is responsible
for the standard NIO buffer lifecycle: `compact()`, read more data, `flip()`.

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
- Process arbitrarily large documents in fixed memory

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
        <version>1.2</version>
    </dependency>
</dependencies>
```

## License

This project is licensed under the GNU Lesser General Public License v3.0
(LGPLv3). See the [COPYING](COPYING) file for details.

