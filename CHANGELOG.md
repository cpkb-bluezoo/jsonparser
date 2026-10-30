# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [2.0.0] - 2026-10-30

A major release: the content handler interface and the `receive()` buffer
contract change (see "Changed"), which is what finally makes the parser's
memory independent of the size of the document and of any string in it.

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
  - Since 1.3, `JSONParser` copied any incomplete trailing token into an
    internal `pending` buffer, so `receive()` fully consumed the buffer it
    was given, contradicting the README and `receive()` Javadoc, which
    described the older caller-owned-buffer contract. That buffer is removed
    again; see "Changed".
  - A string spanning many chunks was rescanned from its opening quote on
    every chunk, so very large strings delivered in small chunks cost
    superlinear time.
- String values are now streamed, which resolves the above for strings; see
  "Changed".
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
  that are covered under "Changed".
- `JSONLocator` positions depend on how the input is chunked no longer: the
  first space or tab of every whitespace run was not counted in the column,
  so the reported column varied with the `receive()` chunk boundaries (and
  was short even for a single chunk). CRLF was also counted as two line
  breaks; CR, LF and CRLF are now each one, including when a chunk boundary
  falls between the CR and the LF.
- `JSONException(String, Throwable)` passes the cause to the superclass
  constructor instead of calling the overridable `initCause` from a
  constructor, and `JSONException` declares a `serialVersionUID`.
- The build has no compiler warnings, and `ant javadoc` no Javadoc errors or
  warnings (headings in `JSONParser`'s class documentation, the undocumented
  `JSONDefaultHandler` constructor). `build.xml` turns off only the
  `-Xlint:options` category, which newer JDKs raise to say that targeting
  Java 8, the supported baseline, is obsolete.
- `JSONWriter` no longer relies on a switch fall-through when indenting.
- `JSONWriter` wrote a Java string's unpaired surrogate (half of an emoji
  that lost its other half, for example) as the three bytes `ED A0 BD`,
  which are not valid UTF-8, so this library's own parser rejected what its
  own writer produced. It is now written as a \uXXXX escape, which is valid JSON
  and reads back as the same character. This applies to values, keys, and
  streamed content, including when a pair is split across calls; a proper
  pair is still written as one UTF-8 character. (RFC 8259 notes that other
  software may handle a string like this unpredictably, so it is best
  avoided at the source.)

### Added

- `JSONWriter.writeStartString()`, `writeStringContent(CharSequence)` and
  `writeEndString()`: the counterpart of the parser's streamed strings, so a
  parser can feed a writer directly with neither holding the whole string.
  The writer encodes and sends content as it is written instead of growing
  its buffer, and handles a surrogate pair split across two calls.
  `writeString(String)` remains for callers that already have the whole
  string.
- `JSONParser.setStringChunkSize(int)`: the size in characters of the buffer
  that string content is decoded into, which is also the most characters one
  `stringValueContent` call carries. The default is 32768, and the minimum
  16. The bytes of a string are decoded and delivered in pieces of at most
  this size however large the buffer passed to `receive()` is, so the
  parser's memory for strings is fixed by this setting and not by the size
  of the input buffer. It takes effect from the next string.

### Changed

- **Breaking:** `JSONContentHandler.stringValue(String)` is replaced by
  `startStringValue()`, `stringValueContent(CharSequence)` and
  `endStringValue()`. A string value is delivered as it is read, so the
  parser no longer holds it and memory does not grow with string length,
  which is what the README's constant-memory claim should have meant all
  along. The content is only valid during each call (the parser reuses its
  character buffer), is never empty, never splits a surrogate pair, and is
  provisional until `endStringValue()` because a malformed string is only
  detected when it is reached. `key(String)` and `whitespace(String)` are
  unchanged. To migrate a handler that needs the whole string, append the
  content to a `StringBuilder` and use it in `endStringValue()`.
- Numbers are read in a single forward pass, one byte at a time, keeping
  only a few counters (the digits so far, the power of ten to scale them by,
  and the exponent) instead of looking ahead for the end of the number and
  re-reading it. A number can therefore span any number of `receive()` calls
  without being held, and `close()` completes a number that ends the
  document from that state. A number is still reported when the next byte
  shows it has ended. Integers beyond a `long` stay exact as a `BigInteger`.
  A real number with more than 19 significant digits is now converted using
  19 of them (previously the whole text went to `Double.parseDouble`): the
  result is the same double unless a rounding boundary falls inside the
  dropped digits, which is roughly 1 in a few hundred to a few thousand such
  numbers, and then it can be one ulp out. Real numbers with 19 or fewer
  significant digits are exactly rounded, as before.
- **Breaking:** `JSONParser.receive()` no longer holds any of the document's
  bytes. Version 1.3 copied an incomplete trailing token into an internal
  `pending` buffer and consumed the whole buffer it was given; that is
  removed, and the buffer contract documented in 1.2 is restored. What
  `receive()` cannot use yet (an incomplete key or literal, the start of an
  escape sequence or multi-byte character, a partial byte order mark) is
  left unconsumed in the caller's buffer, with `position()` stopped at it.
  The caller `compact()`s the buffer, reads more in after those bytes, and
  presents them again. The parser has two buffers: the caller's, and a
  fixed-size `CharBuffer` for string content (see `setStringChunkSize`).
  - A caller that passes a fresh buffer holding only new bytes (which 1.3
    tolerated) or that `clear()`s instead of `compact()`ing now gets a
    `JSONException`: the parser keeps a count and a hash of the unconsumed
    bytes and checks they are presented again, rather than mis-parse
    silently.
  - The buffer must be larger than the longest key
    (`setMaxNameLength`, default 4096, plus two quotes). A buffer that is
    full of one unfinished token makes `receive()` throw a "receive buffer
    too small" exception instead of waiting for data that cannot fit.
  - `close()` rejects bytes still left unconsumed as an incomplete token.
  - `parse(InputStream)` reads into a single fixed buffer of `bufferSize`
    bytes (default 8KB) and never grows it; a key longer than the buffer is
    reported as a buffer-too-small error.
  - `setMaxDocumentLength` counts each byte once, however many times it is
    presented.
- Only keys of up to 64 bytes are interned in the key table, which lives as
  long as the parser, so it holds about a megabyte at most (10,000 keys)
  however long keys are allowed to be.
- **Breaking:** `JSONLocator` positions now follow the rule for SAX's
  `org.xml.sax.Locator`, as its Javadoc already promised. Line and column
  numbers start at 1 (columns started at 0), and the position is where an
  event ends, the first character after the text associated with it (it was
  the start of the token). A column counts UTF-16 units since the last line
  end. So in `[1]` the start of the array is at column 2, the number at
  column 3 and the end of the array at column 4. An object key is reported
  after its closing quote; a string value as its opening quote, each piece
  of its content and its closing quote; a number after its last digit.
- **Breaking:** the default `setMaxNameLength` is now 4096 (was 50,000), and
  the limit is documented as raw bytes rather than characters (the code has
  always compared raw byte offsets). Documents with object keys longer than
  4096 bytes that previously parsed are now rejected unless the limit is
  raised. This deliberately departs from Jackson's 50,000 default: a key
  must fit in the caller's receive buffer, and 4096 is safe for any buffer
  of 8KB or more.

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

