# jsonparser benchmarks

Performance comparison of this project's `org.bluezoo.json` parser against
other Java JSON libraries, across a corpus of documents that varies in size,
character set, and content shape. Its purpose is to find out *where*
jsonparser has a performance deficit relative to its competitors, so
optimization effort can be targeted.

This module is entirely self-contained under `benchmarks/`. It does not
touch the main project's build, tests, or distribution artifact - it only
consumes the jar the main Ant build already produces
(`../dist/jsonparser-1.2.jar`). Nothing here is part of the release.

## Quick start

```sh
# from the project root, build the library jar under test:
ant release

cd benchmarks
ant bench     # downloads deps + corpus (first run only), then runs the full matrix
ant report    # prints a formatted comparison table from the last run
```

`ant bench` fetches ~6 competitor jars from Maven Central and three
real-world corpus files from GitHub on first run (cached under `lib/` and
`corpus/real/` afterwards), generates the synthetic corpus into
`corpus/generated/`, then runs every (library, mode, file) combination in
its own child JVM and writes `results/results-<sha>.csv`. `ant report` just
re-reads that CSV, so you can re-run it without re-benchmarking.

### Comparing results across commits

The results filename is tagged with the git commit the jsonparser library
(`src/`) was built from - e.g. `results/results-05dfe9d.csv` - with a
`-dirty` suffix appended if `src/` has uncommitted changes (since then the
jar doesn't correspond to any committed state). Both `bench` and `report`
compute this automatically from the current checkout, so the normal
workflow (`ant bench` then `ant report`) always operates on the current
commit's file without you needing to name it.

To compare against an older run, checkout the commit you want, rebuild the
jar, and re-run:

```sh
git checkout <some-earlier-commit>
ant release              # from the project root
cd benchmarks && ant bench
```

This produces another `results-<sha>.csv` alongside the others (nothing is
overwritten - each commit gets its own file). To view a specific one later,
regardless of what commit you currently have checked out:

```sh
ant report -Dbench.csv=results/results-<sha>.csv
```

Iteration counts are configurable:

```sh
ant -Dbench.warmup=15 -Dbench.iterations=30 bench   # defaults
```

## Libraries compared

| Library | Version | Modes tested | Why it's here |
|---|---|---|---|
| `org.bluezoo.json` (jsonparser) | this project | stream | the library under test |
| Gson | 2.14.0 | stream, dom | primary competitor named by the project |
| Jackson | 2.22.1 (core+databind) | stream, dom | primary competitor named by the project; known for byte-level UTF-8 and hand-written number parsing |
| org.json | 20260522 | dom | the ubiquitous "reference" JSON library most Java developers reach for first |
| minimal-json | 0.9.5 | dom | closest published competitor in philosophy - small, dependency-free, DOM-only |

Only jsonparser has just a push/SAX streaming API. Gson and Jackson offer
both a low-level token stream and a DOM tree builder, matching the
project's framing that Jackson's `readTree` and streaming paths are
different code paths worth comparing separately. org.json and minimal-json
have no streaming token API, so only DOM mode applies to them.

To add another competitor: implement
[`ParserAdapter`](src/org/bluezoo/json/bench/adapters/ParserAdapter.java) and
register it in
[`Adapters`](src/org/bluezoo/json/bench/adapters/Adapters.java). Ant will
pick up any new jar you add to `fetch-deps` in `build.xml`.

## Corpus

`corpus/real/` (downloaded from the
[nativejson-benchmark](https://github.com/miloyip/nativejson-benchmark)
project, MIT-licensed data, widely used for exactly this kind of comparison):

| File | Size | Character |
|---|---|---|
| `canada.json` | ~2.2 MB | GeoJSON polygon data - almost entirely floating-point number arrays |
| `citm_catalog.json` | ~1.7 MB | Deeply keyed object/array structure, many repeated string keys |
| `twitter.json` | ~630 KB | Real tweet data - heavy non-ASCII (Japanese, emoji), mixed types |

`corpus/generated/` (synthetic, produced by
[`CorpusGenerator`](src/org/bluezoo/json/bench/CorpusGenerator.java) with a
fixed random seed so the corpus is reproducible):

| File | Approx size | Purpose |
|---|---|---|
| `tiny.json` | ~70 B | fixed overhead / per-call cost |
| `small_mixed.json` | ~20 KB | small typical document |
| `medium_mixed.json` | ~2 MB | typical "API response" shape at moderate scale |
| `large_mixed.json` | ~20 MB | same shape at large scale - throughput and GC pressure |
| `numbers_heavy.json` | ~3 MB | dense array of ints, longs, decimals, and scientific notation |
| `strings_ascii.json` | ~3 MB | dense array of plain ASCII strings |
| `strings_unicode.json` | ~3 MB | dense array of strings mixing CJK ideographs, emoji (surrogate pairs), and accented Latin |
| `booleans_nulls.json` | ~1 MB | dense array of `true`/`false`/`null` - minimal payload per token |
| `wide_object.json` | ~2 MB | one flat object with 100,000 distinct keys - stresses key/map handling rather than nesting |
| `deeply_nested.json` | ~400 B | array nested 200 levels deep - stress an unusual shape, not size |

`deeply_nested.json` is capped at 200 levels because Gson's `JsonReader`
hard-codes a 255-level nesting limit and throws `MalformedJsonException`
beyond it - itself a small data point about Gson's assumptions.

## Methodology

There is no JMH dependency here (deliberately - this project has no
Maven/Gradle build and JMH's annotation-processing setup didn't seem worth
the extra machinery). Instead:

- Each `(library, mode, file)` combination runs in its **own child JVM**
  (see [`Bench`](src/org/bluezoo/json/bench/Bench.java) /
  [`Worker`](src/org/bluezoo/json/bench/Worker.java)), so one library's
  class loading, JIT profile, or GC behavior can't leak into another's
  numbers.
- The input file is read into a `byte[]` once, outside the timed region.
- 15 warmup iterations run un-timed to let the JIT compile hot paths,
  followed by 30 timed iterations (both configurable).
- Every iteration's result is written to a `static volatile` sink field.
  A volatile write can never be optimized away by the JIT (another thread
  could observe it), which is what prevents the parse call from being
  eliminated as dead code - the same problem JMH's `Blackhole` solves, done
  by hand.
- Reported figures are the **median** of the timed iterations (min/max are
  also recorded in the CSV for reference). Median was chosen over mean to
  reduce sensitivity to the odd GC pause during measurement.

`Report` prints a per-file table (fastest first) plus a geometric-mean
summary across the whole corpus (ratios should be averaged geometrically,
not arithmetically).

## Caveats

- Numbers are wall-clock, single-machine, single-JVM-per-test - useful for
  relative comparison and spotting deficits, not a formal peer-reviewed
  benchmark.
- The "stream" mode checksum work differs slightly per library by
  necessity (e.g. Gson's `nextDouble()` vs Jackson's `getDoubleValue()`),
  but each does comparable value-extraction work per token, matching how
  each library is actually used.
- DOM-mode results are the cost of building the tree only; nothing further
  is done with it (the tree reference itself is what escapes to the
  volatile sink).
