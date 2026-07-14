package org.bluezoo.json.bench;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads the CSV produced by {@link Bench} and prints a human-readable
 * comparison table: per corpus file, every library/mode ranked fastest
 * first, with throughput and a multiplier relative to jsonparser. Finishes
 * with a geometric-mean summary across the whole corpus, since ratios
 * should be averaged geometrically, not arithmetically.
 */
public final class Report {

    private static final class Row {
        String library;
        String mode;
        String file;
        long bytes;
        long minNs;
        long medianNs;
        long maxNs;
        double meanMs;
    }

    public static void main(String[] args) throws IOException {
        List<String> lines = Files.readAllLines(Paths.get(args[0]));
        List<Row> rows = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.trim().isEmpty()) {
                continue;
            }
            String[] parts = line.split(",");
            Row r = new Row();
            r.library = parts[0];
            r.mode = parts[1];
            r.file = parts[2];
            r.bytes = Long.parseLong(parts[3]);
            r.minNs = Long.parseLong(parts[4]);
            r.medianNs = Long.parseLong(parts[5]);
            r.maxNs = Long.parseLong(parts[6]);
            r.meanMs = Double.parseDouble(parts[7]);
            rows.add(r);
        }

        Map<String, List<Row>> byFile = new LinkedHashMap<>();
        for (Row r : rows) {
            byFile.computeIfAbsent(r.file, k -> new ArrayList<>()).add(r);
        }

        Map<String, List<Double>> ratiosByKey = new LinkedHashMap<>();

        for (Map.Entry<String, List<Row>> entry : byFile.entrySet()) {
            String file = entry.getKey();
            List<Row> group = new ArrayList<>(entry.getValue());
            long bytes = group.get(0).bytes;

            Double baseline = null;
            for (Row r : group) {
                if (r.library.equals("jsonparser")) {
                    baseline = (double) r.medianNs;
                }
            }

            group.sort((a, b) -> Long.compare(a.medianNs, b.medianNs));

            System.out.printf(Locale.ROOT, "%n=== %s (%,d bytes) ===%n", shortName(file), bytes);
            System.out.printf(Locale.ROOT, "%-14s %-7s %12s %10s %14s%n",
                    "library", "mode", "median", "MB/s", "vs jsonparser");
            for (Row r : group) {
                double mbPerSec = (r.bytes / (1024.0 * 1024.0)) / (r.medianNs / 1_000_000_000.0);
                String rel = baseline == null ? "n/a" : String.format(Locale.ROOT, "%.2fx", r.medianNs / baseline);
                System.out.printf(Locale.ROOT, "%-14s %-7s %12s %10.1f %14s%n",
                        r.library, r.mode, formatNs(r.medianNs), mbPerSec, rel);

                if (baseline != null && !r.library.equals("jsonparser")) {
                    String key = r.library + "/" + r.mode;
                    ratiosByKey.computeIfAbsent(key, k -> new ArrayList<>()).add(r.medianNs / baseline);
                }
            }
        }

        System.out.printf(Locale.ROOT,
                "%n=== Summary: geometric-mean time relative to jsonparser (stream), across all corpus files ===%n");
        for (Map.Entry<String, List<Double>> entry : ratiosByKey.entrySet()) {
            double logSum = 0;
            for (double d : entry.getValue()) {
                logSum += Math.log(d);
            }
            double geoMean = Math.exp(logSum / entry.getValue().size());
            System.out.printf(Locale.ROOT, "%-20s %.2fx%n", entry.getKey(), geoMean);
        }
    }

    private static String shortName(String path) {
        int idx = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return idx >= 0 ? path.substring(idx + 1) : path;
    }

    private static String formatNs(long ns) {
        if (ns < 1_000_000) {
            return String.format(Locale.ROOT, "%.2fµs", ns / 1_000.0);
        }
        return String.format(Locale.ROOT, "%.2fms", ns / 1_000_000.0);
    }
}
