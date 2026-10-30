# jsonparser
A fast, tiny, streaming JSON parser and serializer for Java.

![Benchmark comparison: total parse time across a varied JSON corpus, by library and mode - smaller is better](benchmarks/benchmark-comparison.svg)

Total time to parse a 13-file corpus, by library and mode (smaller is
better). Over three runs jsonparser took 83-88 ms, against 118-124 ms for
Jackson and Gson streaming. See the [benchmarks](benchmarks/README.md) for
the methodology and caveats.

## Why jsonparser

- **Fast.** Fastest overall in the benchmark above, ahead of Jackson and
  Gson even when they stream.
- **Tiny.** One 38 KB jar with no dependencies (Gson is 306 KB, Jackson's
  streaming core 580 KB).
- **Streaming and non-blocking.** You push bytes in as they arrive, from an
  NIO channel, a socket or a file, and events fire before the document has
  finished arriving.
- **Bounded memory.** The parser holds none of your bytes, and string values
  stream through a fixed-size buffer, so memory does not grow with the size
  of the document or of any string in it.
- **Conformant.** Tested against
  [JSONTestSuite](https://github.com/nst/JSONTestSuite).
- **A matching writer.** An NIO-first `JSONWriter` with optional
  indentation, which can stream strings too.

## Get it

jsonparser is on Maven Central and GitHub Packages, with the same
coordinates:

```xml
<dependency>
    <groupId>org.bluezoo</groupId>
    <artifactId>jsonparser</artifactId>
    <version>2.0.0</version>
</dependency>
```

Gradle: `implementation 'org.bluezoo:jsonparser:2.0.0'`. It runs on Java 8 or
later. To build from source you need JDK 9 or later: run `ant release` and
the jar is written to `dist/`.

[API documentation](https://cpkb-bluezoo.github.io/jsonparser/doc/)

## Parsing

You receive events through a `JSONContentHandler`, as in SAX. Extend
`JSONDefaultHandler` and override the ones you need:

- `startObject()` / `endObject()`, `startArray()` / `endArray()`
- `key(String)`: an object key, delivered whole
- `startStringValue()`, `stringValueContent(CharSequence)`,
  `endStringValue()`: a string value, delivered as it arrives
- `numberValue(Number)`: an `Integer`, `Long`, `Double` or `BigInteger`
- `booleanValue(boolean)`, `nullValue()`
- `whitespace(String)`: only if `needsWhitespace()` returns true

### From a stream

If blocking is fine, `parse(InputStream)` is all you need:

```java
import java.io.*;
import org.bluezoo.json.*;

public class ListFieldNames extends JSONDefaultHandler {

    @Override
    public void key(String key) {
        System.out.println(key);
    }

    public static void main(String[] args) throws Exception {
        JSONParser parser = new JSONParser();
        parser.setContentHandler(new ListFieldNames());
        try (InputStream in = new FileInputStream(args[0])) {
            parser.parse(in);   // blocks until the whole stream is parsed
        }
    }
}
```

### As the bytes arrive

The primary API is non-blocking: you push each chunk into `receive()`, and
the handler is called as soon as tokens are complete.

![Event pipeline: bytes arrive in chunks, the parser emits events immediately](event-pipeline.svg)

```java
import java.nio.*;
import java.nio.channels.*;
import org.bluezoo.json.*;

public class StreamExample extends JSONDefaultHandler {

    @Override
    public void key(String key) {
        System.out.println("key: " + key);
    }

    @Override
    public void stringValueContent(CharSequence content) {
        // Only valid during this call: use it now, or copy it with toString()
        System.out.print(content);
    }

    @Override
    public void endStringValue() {
        System.out.println();
    }

    public static void parse(ReadableByteChannel channel) throws Exception {
        JSONParser parser = new JSONParser();
        parser.setContentHandler(new StreamExample());

        ByteBuffer buffer = ByteBuffer.allocate(16384);
        while (channel.read(buffer) != -1) {
            buffer.flip();            // switch to read mode
            parser.receive(buffer);   // events fire here
            buffer.compact();         // keep what the parser could not use yet
        }
        parser.close();               // checks the document was complete
    }
}
```

Three things to know:

- **Compact, never clear.** What the parser cannot use yet (an unfinished key
  or literal, the start of an escape sequence or a multi-byte character) stays
  in your buffer, and you present it again with more data. The parser checks
  that it is still there and throws if it is not.
- **The buffer must be larger than your longest key.** Keys are limited to
  4096 bytes by default (`setMaxNameLength`).
- **A string arrives in pieces.** Each piece is only valid during the call,
  and is provisional until `endStringValue()`, because a malformed string is
  only detected when it is reached. If you need the whole string, append the
  pieces to a `StringBuilder`.

### Memory and limits

- The parser holds none of your bytes. It has your buffer, plus one
  fixed-size character buffer for string content (32768 characters, see
  `setStringChunkSize`), so a 1 GB string costs the same as a 10 byte one.
- String length is not limited by default, because it costs the parser
  nothing. Use `setMaxStringLength` if your handler accumulates strings.
- The other limits guard untrusted input: nesting depth (1000), number length
  (1000) and key length (4096 bytes). Each has a setter, and
  `disableAllLimits()` turns them all off for trusted input.
- Integers are exact. A real number with up to 19 significant digits is
  exactly the nearest double; see `JSONContentHandler.numberValue` for the
  detail beyond that.

## Writing

`JSONWriter` wraps an `OutputStream` or a `WritableByteChannel` and sends data
as its buffer fills. You call the methods in order and it escapes strings for
you. Pass an `IndentConfig` for readable output, or none for compact output.

```java
import org.bluezoo.json.*;

public class MakeJohnSmith {

    public static void main(String[] args) throws Exception {
        JSONWriter writer = new JSONWriter(System.out, IndentConfig.tabs());
        writer.writeStartObject();
        writer.writeKey("first_name");
        writer.writeString("John");
        writer.writeKey("is_alive");
        writer.writeBoolean(true);
        writer.writeKey("age");
        writer.writeNumber(27);
        writer.writeKey("address");
        writer.writeStartObject();
        writer.writeKey("city");
        writer.writeString("New York");
        writer.writeEndObject();
        writer.writeKey("phone_numbers");
        writer.writeStartArray();
        writer.writeString("212 555-1234");
        writer.writeString("646 555-4567");
        writer.writeEndArray();
        writer.writeKey("spouse");
        writer.writeNull();
        writer.writeEndObject();
        writer.close();
    }
}
```

To write a long string without holding it, use `writeStartString()`, any
number of `writeStringContent(CharSequence)` calls, then `writeEndString()`.
They mirror the parser's string events, so a handler can pipe the parser
straight into a writer. `writeNumber` rejects NaN and the infinities, which
JSON cannot represent.

## More

[API documentation](https://cpkb-bluezoo.github.io/jsonparser/doc/) |
[Benchmarks](benchmarks/README.md) | [Changelog](CHANGELOG.md) |
[Security policy](SECURITY.md)

## License

This project is licensed under the GNU Lesser General Public License v3.0
(LGPLv3). See the [COPYING](COPYING) file for details.

## Acknowledgements

Converting real numbers to doubles uses the Eisel-Lemire algorithm, following
Daniel Lemire's [fast_float](https://github.com/fastfloat/fast_float)
library (Apache-2.0, MIT or BSL-1.0).
