# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.2] - 2026-01-10

### Added

- JPMS (Java Platform Module System) support via `module-info.java`. The module
  name is `org.bluezoo.json`. Projects using Java 9+ can now use this library
  as a proper module.

### Changed

- **Breaking:** Removed `CompositeByteBuffer` utility class. The `receive()`
  caller must now perform proper buffer management. `JSONParser` no longer
  buffers partial tokens internally.

- Callers of `receive(ByteBuffer)` are now responsible for performing correct
  `compact()` / `flip()` lifecycle events on the buffer provided. This change
  gives callers full control over memory allocation and buffer reuse,
  improving efficiency in high-performance scenarios.

- Parser now operates in **constant memory** regardless of document size.

### Migration Guide

If you were previously relying on the parser to handle partial token buffering:

1. Allocate a `ByteBuffer` of appropriate size for your use case
2. After each `receive()` call, if more data is expected:
   - Call `compact()` on the buffer to preserve any unconsumed bytes
   - Read new data into the buffer
   - Call `flip()` before the next `receive()` call
3. The parser will consume complete tokens from the buffer and leave any
   partial token data at the buffer's current position

Example:

```java
ByteBuffer buffer = ByteBuffer.allocate(8192);
while (channel.read(buffer) != -1) {
    buffer.flip();
    parser.receive(buffer);
    buffer.compact();
}
buffer.flip();
if (buffer.hasRemaining()) {
    parser.receive(buffer);
}
parser.close();
```

## [1.1.0] - 2025-12-14

### Added

- Fully converted to NIO
- Streaming `JSONWriter` for JSON serialization instead of `JSONStreamWriter`
- Full conformance with JSONTestSuite
- Support for UTF-8 BOM detection
- Configurable indentation for pretty-printed output

