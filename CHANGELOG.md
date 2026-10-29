# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Fixed

- **Documentation correction:** earlier releases (including the 1.2 changelog
  entry below) and the README claimed the parser "operates in constant
  memory" and does "no internal buffering". That was not accurate.
  - Memory use does not grow with document size, but it is not constant:
    `JSONContentHandler.stringValue(String)` and `key(String)` deliver a
    complete value, so the parser must hold every byte of a string or key
    until its closing quote arrives. Peak memory is proportional to the
    largest single string or key. This is bounded by default by
    `setMaxStringLength` (20,000,000) and `setMaxNameLength` (4096), and
    unbounded if those limits are disabled.
  - Since 1.3, `JSONParser` copies any incomplete trailing token into an
    internal `pending` buffer, so `receive()` fully consumes the buffer it
    is given. The README and `receive()` Javadoc previously described the
    older caller-owned-buffer contract.
  - A string spanning many chunks is rescanned from its opening quote on
    every chunk, so very large strings delivered in small chunks cost
    superlinear time.
- README, package Javadoc and `JSONParser` Javadoc now state the real
  behaviour (see "Memory behaviour" in the README).
- Real numbers are now correctly rounded, matching `Double.parseDouble`
  exactly. They were composed as `(int + frac / 10^k) * Math.pow(10, exp)`,
  which rounds several times and was off by one ulp for a large share of
  inputs (about 36% of random 17-digit doubles, about 20% of short decimals
  with an exponent). It also parsed `1.7976931348623157e308`
  (`Double.MAX_VALUE`) as `Infinity`. Numbers whose significant digits fit
  in a `long` are converted without allocating, using Clinger's exact fast
  path where one IEEE operation suffices and the Eisel-Lemire algorithm
  otherwise (new internal class `DecimalToDouble`; its table of powers of
  five is generated on first use). Numbers with more significant digits than
  that are converted from the number text by the JDK.
- `JSONLocator` positions depend on how the input is chunked no longer: the
  first space or tab of every whitespace run was not counted in the column,
  so the reported column varied with the `receive()` chunk boundaries (and
  was short even for a single chunk). CRLF was also counted as two line
  breaks; CR, LF and CRLF are now each one, including when a chunk boundary
  falls between the CR and the LF.

### Changed

- **Breaking:** the default `setMaxNameLength` is now 4096 (was 50,000), and
  the limit is documented as raw bytes rather than characters (the code has
  always compared raw byte offsets). Documents with object keys longer than
  4096 bytes that previously parsed are now rejected unless the limit is
  raised. This deliberately departs from Jackson's 50,000 default: a key
  must fit in the caller's receive buffer, and 4096 is safe for any buffer
  of 8KB or more.

### Planned

- A future major release will change `JSONContentHandler` to deliver string
  values incrementally (a start event, zero or more content events, an end
  event) so strings can be flushed without buffering. This is a breaking
  API change and is what is required to make large-string handling truly
  constant-memory.

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

