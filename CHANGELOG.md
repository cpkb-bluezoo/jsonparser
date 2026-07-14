# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.3] - 2026-07-14

### Added

- `benchmarks/` module comparing jsonparser against Gson, Jackson, org.json,
  and minimal-json across a varied JSON corpus (size, ASCII vs Unicode,
  content shape). See `benchmarks/README.md` for methodology and how to
  reproduce the figures. A performance comparison chart and a jar-size
  comparison table were added to the main README.

### Security

- Configurable resource-exhaustion limits (`ParserLimits`, exposed via new
  `JSONParser` setters `setMaxNestingDepth`, `setMaxNumberLength`,
  `setMaxStringLength`, `setMaxNameLength`, `setMaxDocumentLength`,
  `setMaxTokenCount`), guarding against deeply nested input, oversized
  number/string/key literals, and oversized documents/token counts.
  Defaults match Jackson's `StreamReadConstraints` industry-standard
  values. Any limit `<= 0` disables it; `disableAllLimits()` disables all
  six at once (used by this project's own tests and benchmarks, which
  intentionally parse inputs larger than the defaults allow).
- Opt-in duplicate object-key rejection (`setRejectDuplicateKeys(boolean)`,
  off by default).

### Changed

- Rewrote the tokenizer to operate directly on the input `ByteBuffer`
  instead of decoding to `char` upfront. Structural, whitespace, number,
  and literal (`true`/`false`/`null`) tokens are scanned without UTF-8
  decoding, and numbers are composed directly from digit bytes rather than
  always materializing and parsing a `String`. Combined with a hand-rolled
  UTF-8 decoder (replacing `java.nio.charset.CharsetDecoder`) and
  array-backed fast-path scanning for strings and literals, this
  significantly improves throughput; see the new Performance section in
  the README for figures.
- Object keys are now interned via a hash-first symbol table
  (`KeySymbolTable`), so repeated field names across a document - and
  across `reset()` calls on the same `JSONParser` - reuse the same
  `String` instance rather than being reallocated.
- `pom.xml` reduced to coordinate metadata only (groupId, artifactId,
  version, license). This project is built exclusively with Ant; the POM
  is not used to build or deploy anything.

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

