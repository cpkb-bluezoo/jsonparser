package org.bluezoo.json.bench;

import java.io.BufferedOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Random;
import java.util.function.Function;

/**
 * Generates a synthetic corpus of JSON documents covering a spread of sizes
 * and content characteristics, so the benchmark isn't just measuring one
 * shape of document:
 * <ul>
 *   <li>size: tiny / small / medium / large</li>
 *   <li>character set: pure ASCII vs full Unicode (CJK, emoji, accents)</li>
 *   <li>number density: number-heavy arrays vs string/object-heavy documents</li>
 *   <li>shape: flat wide objects vs deeply nested structures</li>
 * </ul>
 *
 * <p>Generation is deterministic (fixed random seed) so the corpus is
 * reproducible across runs and machines.
 */
public final class CorpusGenerator {

    private static final long SEED = 42L;

    public static void main(String[] args) throws IOException {
        Path outDir = Paths.get(args[0]);
        Files.createDirectories(outDir);

        generateTiny(outDir.resolve("tiny.json"));
        generateMixed(outDir.resolve("small_mixed.json"), 20_000, new Random(SEED));
        generateMixed(outDir.resolve("medium_mixed.json"), 2_000_000, new Random(SEED));
        generateMixed(outDir.resolve("large_mixed.json"), 20_000_000, new Random(SEED));
        generateNumbersHeavy(outDir.resolve("numbers_heavy.json"), 3_000_000, new Random(SEED));
        generateStringArray(outDir.resolve("strings_ascii.json"), 3_000_000, new Random(SEED),
                CorpusGenerator::randomAsciiSentence);
        generateStringArray(outDir.resolve("strings_unicode.json"), 3_000_000, new Random(SEED),
                CorpusGenerator::randomUnicodeSentence);
        generateBooleansAndNulls(outDir.resolve("booleans_nulls.json"), 1_000_000, new Random(SEED));
        generateWideObject(outDir.resolve("wide_object.json"), 100_000);
        // Kept below Gson's hard-coded default JsonReader nesting limit (255 levels),
        // otherwise Gson refuses to parse it at all - still deep enough to be an
        // unusual, stress-worthy shape relative to typical JSON documents.
        generateDeeplyNested(outDir.resolve("deeply_nested.json"), 200);

        System.err.println("Generated corpus files in " + outDir + ":");
        try (var stream = Files.list(outDir)) {
            stream.sorted().forEach(p -> {
                try {
                    System.err.printf("  %-24s %,12d bytes%n", p.getFileName(), Files.size(p));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    // ------------------------------------------------------------------
    // Individual generators
    // ------------------------------------------------------------------

    private static void generateTiny(Path file) throws IOException {
        writeString(file, "{\"id\":1,\"name\":\"widget\",\"active\":true,\"tags\":[\"a\",\"b\"],\"price\":9.99}");
    }

    /** A JSON array of typical "API response" style records: mixed types, one level of nesting. */
    private static void generateMixed(Path file, long targetBytes, Random rnd) throws IOException {
        try (CountingOutputStream cos = openCounting(file); Writer w = writer(cos)) {
            w.write("[\n");
            boolean first = true;
            long id = 0;
            while (true) {
                if (!first) {
                    w.write(",\n");
                }
                first = false;
                w.write("{\"id\":");
                w.write(Long.toString(id++));
                w.write(",\"name\":\"");
                w.write(jsonEscape(randomAsciiSentence(rnd)));
                w.write("\",\"active\":");
                w.write(rnd.nextBoolean() ? "true" : "false");
                w.write(",\"score\":");
                w.write(Double.toString(rnd.nextDouble() * 100));
                w.write(",\"tags\":[");
                int tagCount = 1 + rnd.nextInt(4);
                for (int t = 0; t < tagCount; t++) {
                    if (t > 0) {
                        w.write(",");
                    }
                    w.write("\"");
                    w.write(jsonEscape(randomWord(rnd)));
                    w.write("\"");
                }
                w.write("],\"meta\":{\"created\":");
                w.write(Long.toString(1_700_000_000L + rnd.nextInt(50_000_000)));
                w.write(",\"note\":");
                if (rnd.nextInt(5) == 0) {
                    w.write("null");
                } else {
                    w.write("\"");
                    w.write(jsonEscape(randomWord(rnd)));
                    w.write("\"");
                }
                w.write("}}");

                if (id % 200 == 0) {
                    w.flush();
                    if (cos.count >= targetBytes) {
                        break;
                    }
                }
            }
            w.write("\n]\n");
        }
    }

    /** Number-dense array: ints, longs, negatives, decimals, and scientific notation. */
    private static void generateNumbersHeavy(Path file, long targetBytes, Random rnd) throws IOException {
        try (CountingOutputStream cos = openCounting(file); Writer w = writer(cos)) {
            w.write("[\n");
            boolean first = true;
            int count = 0;
            while (true) {
                if (!first) {
                    w.write(",");
                }
                first = false;
                w.write(randomNumber(rnd));
                count++;
                if (count % 32 == 0) {
                    w.write("\n");
                }
                if (count % 1000 == 0) {
                    w.flush();
                    if (cos.count >= targetBytes) {
                        break;
                    }
                }
            }
            w.write("\n]\n");
        }
    }

    /** Flat array of strings, using the given per-element string generator. */
    private static void generateStringArray(Path file, long targetBytes, Random rnd,
                                             Function<Random, String> stringGenerator) throws IOException {
        try (CountingOutputStream cos = openCounting(file); Writer w = writer(cos)) {
            w.write("[\n");
            boolean first = true;
            int count = 0;
            while (true) {
                if (!first) {
                    w.write(",\n");
                }
                first = false;
                w.write("\"");
                w.write(jsonEscape(stringGenerator.apply(rnd)));
                w.write("\"");
                count++;
                if (count % 200 == 0) {
                    w.flush();
                    if (cos.count >= targetBytes) {
                        break;
                    }
                }
            }
            w.write("\n]\n");
        }
    }

    private static void generateBooleansAndNulls(Path file, long targetBytes, Random rnd) throws IOException {
        try (CountingOutputStream cos = openCounting(file); Writer w = writer(cos)) {
            w.write("[");
            boolean first = true;
            int count = 0;
            while (true) {
                if (!first) {
                    w.write(",");
                }
                first = false;
                int r = rnd.nextInt(3);
                w.write(r == 0 ? "true" : r == 1 ? "false" : "null");
                count++;
                if (count % 40 == 0) {
                    w.write("\n");
                }
                if (count % 2000 == 0) {
                    w.flush();
                    if (cos.count >= targetBytes) {
                        break;
                    }
                }
            }
            w.write("\n]\n");
        }
    }

    /** A single flat object with a large number of keys - stresses key/map handling rather than nesting. */
    private static void generateWideObject(Path file, int keyCount) throws IOException {
        Random rnd = new Random(SEED);
        try (CountingOutputStream cos = openCounting(file); Writer w = writer(cos)) {
            w.write("{\n");
            for (int i = 0; i < keyCount; i++) {
                if (i > 0) {
                    w.write(",\n");
                }
                w.write("\"key_");
                w.write(Integer.toString(i));
                w.write("\":");
                if (i % 7 == 0) {
                    w.write("\"");
                    w.write(jsonEscape(randomWord(rnd)));
                    w.write("\"");
                } else {
                    w.write(Integer.toString(rnd.nextInt(1_000_000)));
                }
            }
            w.write("\n}\n");
        }
    }

    /** A single deeply nested array, e.g. [[[[...1...]]]], to exercise recursion/depth handling. */
    private static void generateDeeplyNested(Path file, int depth) throws IOException {
        try (CountingOutputStream cos = openCounting(file); Writer w = writer(cos)) {
            for (int i = 0; i < depth; i++) {
                w.write("[");
            }
            w.write("1");
            for (int i = 0; i < depth; i++) {
                w.write("]");
            }
            w.write("\n");
        }
    }

    // ------------------------------------------------------------------
    // Random content helpers
    // ------------------------------------------------------------------

    private static final String[] WORDS = {
        "alpha", "bravo", "charlie", "delta", "echo", "foxtrot", "golf", "hotel",
        "india", "juliet", "kilo", "lima", "mike", "november", "oscar", "papa",
        "quebec", "romeo", "sierra", "tango", "uniform", "victor", "whiskey", "xray",
        "yankee", "zulu", "widget", "gadget", "sensor", "queue", "buffer", "packet",
        "router", "server", "client", "session", "token", "cache", "index", "record"
    };

    private static String randomWord(Random rnd) {
        return WORDS[rnd.nextInt(WORDS.length)];
    }

    private static String randomAsciiSentence(Random rnd) {
        int words = 4 + rnd.nextInt(12);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < words; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(randomWord(rnd));
        }
        // Occasionally include characters that require JSON escaping, to exercise
        // that code path without making every string expensive to escape.
        if (rnd.nextInt(20) == 0) {
            sb.append(" \"quoted\" and a\\backslash and a\ttab");
        }
        return sb.toString();
    }

    // Representative multi-byte ranges: CJK ideographs, emoji (supplementary plane,
    // surrogate pairs), and accented Latin - a realistic mix for internationalized data.
    private static String randomUnicodeSentence(Random rnd) {
        StringBuilder sb = new StringBuilder();
        int segments = 3 + rnd.nextInt(6);
        for (int s = 0; s < segments; s++) {
            if (s > 0) {
                sb.append(' ');
            }
            int kind = rnd.nextInt(4);
            switch (kind) {
                case 0: // ASCII word for realism (mixed-language text)
                    sb.append(randomWord(rnd));
                    break;
                case 1: // CJK ideographs
                    int cjkLen = 2 + rnd.nextInt(6);
                    for (int i = 0; i < cjkLen; i++) {
                        sb.appendCodePoint(0x4E00 + rnd.nextInt(0x9FFF - 0x4E00));
                    }
                    break;
                case 2: // Emoji (astral plane, encoded as surrogate pairs)
                    int emojiLen = 1 + rnd.nextInt(3);
                    for (int i = 0; i < emojiLen; i++) {
                        sb.appendCodePoint(0x1F600 + rnd.nextInt(0x1F64F - 0x1F600));
                    }
                    break;
                case 3: // Accented Latin
                    int latinLen = 3 + rnd.nextInt(6);
                    for (int i = 0; i < latinLen; i++) {
                        sb.appendCodePoint(0x00C0 + rnd.nextInt(0x017F - 0x00C0));
                    }
                    break;
                default:
                    break;
            }
        }
        return sb.toString();
    }

    private static String randomNumber(Random rnd) {
        int kind = rnd.nextInt(5);
        switch (kind) {
            case 0:
                return Integer.toString(rnd.nextInt(1_000_000) - 500_000);
            case 1:
                return Long.toString(rnd.nextLong() % 1_000_000_000_000L);
            case 2:
                return Double.toString(rnd.nextDouble() * 1000 - 500);
            case 3:
                return String.format("%.6e", rnd.nextDouble() * Math.pow(10, rnd.nextInt(20) - 10));
            case 4:
            default:
                return Integer.toString(rnd.nextInt(10));
        }
    }

    // ------------------------------------------------------------------
    // JSON string escaping (only the generators need this - keep it minimal)
    // ------------------------------------------------------------------

    private static String jsonEscape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // I/O helpers
    // ------------------------------------------------------------------

    private static void writeString(Path file, String content) throws IOException {
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
    }

    private static CountingOutputStream openCounting(Path file) throws IOException {
        return new CountingOutputStream(new BufferedOutputStream(Files.newOutputStream(file)));
    }

    private static Writer writer(OutputStream out) {
        return new OutputStreamWriter(out, StandardCharsets.UTF_8);
    }

    /** Tracks bytes written so generators can stop once they reach a target size. */
    private static final class CountingOutputStream extends FilterOutputStream {
        long count;

        CountingOutputStream(OutputStream out) {
            super(out);
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            count += len;
        }
    }
}
