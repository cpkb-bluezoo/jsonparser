package org.bluezoo.json.bench;

import org.bluezoo.json.bench.adapters.Adapters;
import org.bluezoo.json.bench.adapters.ParserAdapter;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Map;

/**
 * Runs in its own child JVM (spawned by {@link Bench}) so that each
 * (library, mode, file) measurement gets a clean JIT/GC state, with no
 * chance of one library's classes or allocation patterns influencing
 * another's numbers.
 *
 * <p>Args: library mode filePath fileLabel warmupIterations measuredIterations
 * <p>{@code filePath} is the absolute path used to actually read the file;
 * {@code fileLabel} (a path relative to the benchmarks directory, e.g.
 * {@code corpus/real/canada.json}) is what gets written to the CSV output
 * instead, so results never embed local filesystem/username information.
 * <p>Prints a single CSV result line to stdout; everything else goes to stderr.
 */
public final class Worker {

    /**
     * Every iteration writes here. A write to a volatile field is a
     * publication the JVM must honor, so the JIT cannot prove the parse
     * result is unused and eliminate the call as dead code - this is the
     * same trick JMH's Blackhole relies on, without needing JMH.
     */
    static volatile Object sink;

    public static void main(String[] args) throws Exception {
        String libName = args[0];
        String mode = args[1];
        String filePath = args[2];
        String fileLabel = args[3];
        int warmup = Integer.parseInt(args[4]);
        int iterations = Integer.parseInt(args[5]);

        byte[] data = Files.readAllBytes(Paths.get(filePath));

        Map<String, ParserAdapter> adapters = Adapters.all();
        ParserAdapter adapter = adapters.get(libName);
        if (adapter == null) {
            throw new IllegalArgumentException("Unknown library: " + libName);
        }

        for (int i = 0; i < warmup; i++) {
            sink = run(adapter, mode, data);
        }

        long[] nanos = new long[iterations];
        for (int i = 0; i < iterations; i++) {
            long t0 = System.nanoTime();
            sink = run(adapter, mode, data);
            long t1 = System.nanoTime();
            nanos[i] = t1 - t0;
        }

        Arrays.sort(nanos);
        long min = nanos[0];
        long max = nanos[nanos.length - 1];
        long median = nanos[nanos.length / 2];
        double sum = 0;
        for (long n : nanos) {
            sum += n;
        }
        double meanMs = (sum / nanos.length) / 1_000_000.0;

        System.out.println(String.join(",",
                libName, mode, fileLabel, String.valueOf(data.length),
                String.valueOf(min), String.valueOf(median), String.valueOf(max),
                String.valueOf(meanMs)));
    }

    private static Object run(ParserAdapter adapter, String mode, byte[] data) throws Exception {
        return "stream".equals(mode) ? adapter.parseStream(data) : adapter.parseDom(data);
    }
}
