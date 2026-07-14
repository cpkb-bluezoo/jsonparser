package org.bluezoo.json.bench;

import org.bluezoo.json.bench.adapters.Adapters;
import org.bluezoo.json.bench.adapters.ParserAdapter;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Orchestrates the whole benchmark matrix: for every (library, mode, corpus
 * file) combination it spawns an isolated child JVM ({@link Worker}) and
 * collects its one-line CSV result.
 *
 * <p>Args: corpusRealDir corpusGeneratedDir outputCsv warmupIterations measuredIterations
 */
public final class Bench {

    public static void main(String[] args) throws Exception {
        Path corpusReal = Paths.get(args[0]);
        Path corpusGenerated = Paths.get(args[1]);
        Path outputCsv = Paths.get(args[2]);
        int warmup = Integer.parseInt(args[3]);
        int iterations = Integer.parseInt(args[4]);

        String libDir = require("bench.lib.dir");
        String jsonparserJar = require("bench.jsonparser.jar");
        String buildDir = require("bench.build.dir");
        String javaHome = System.getProperty("bench.java.home", System.getProperty("java.home"));

        String classpath = buildClasspath(buildDir, libDir, jsonparserJar);
        String javaBin = javaHome + File.separator + "bin" + File.separator + "java";

        List<CorpusFile> files = new ArrayList<>();
        collectJsonFiles(corpusReal, "corpus/real", files);
        collectJsonFiles(corpusGenerated, "corpus/generated", files);
        files.sort(Comparator.comparing(f -> f.label));

        if (files.isEmpty()) {
            throw new IllegalStateException(
                    "No corpus files found under " + corpusReal + " or " + corpusGenerated);
        }

        Map<String, ParserAdapter> adapters = Adapters.all();
        String[] modes = {"stream", "dom"};

        int total = 0;
        for (ParserAdapter adapter : adapters.values()) {
            for (String mode : modes) {
                if (supports(adapter, mode)) {
                    total += files.size();
                }
            }
        }

        List<String> results = new ArrayList<>();
        results.add("library,mode,file,bytes,minNs,medianNs,maxNs,meanMs");

        int done = 0;
        for (Map.Entry<String, ParserAdapter> entry : adapters.entrySet()) {
            String libName = entry.getKey();
            ParserAdapter adapter = entry.getValue();
            for (String mode : modes) {
                if (!supports(adapter, mode)) {
                    continue;
                }
                for (CorpusFile file : files) {
                    done++;
                    System.err.printf("[%d/%d] %-14s %-6s %-30s ", done, total, libName, mode,
                            file.path.getFileName());
                    System.err.flush();
                    long t0 = System.currentTimeMillis();
                    String line = runWorker(javaBin, classpath, libName, mode, file, warmup, iterations);
                    long t1 = System.currentTimeMillis();
                    results.add(line);
                    System.err.printf("(%dms)%n", t1 - t0);
                }
            }
        }

        Files.write(outputCsv, results, StandardCharsets.UTF_8);
        System.err.println();
        System.err.println("Wrote " + (results.size() - 1) + " result rows to " + outputCsv);
    }

    private static boolean supports(ParserAdapter adapter, String mode) {
        return "stream".equals(mode) ? adapter.supportsStream() : adapter.supportsDom();
    }

    private static String require(String prop) {
        String value = System.getProperty(prop);
        if (value == null) {
            throw new IllegalStateException("Missing required system property: " + prop);
        }
        return value;
    }

    private static String runWorker(String javaBin, String classpath, String libName, String mode,
                                     CorpusFile file, int warmup, int iterations) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
                javaBin, "-cp", classpath, "-Xms256m", "-Xmx1536m",
                "org.bluezoo.json.bench.Worker",
                libName, mode, file.path.toString(), file.label,
                String.valueOf(warmup), String.valueOf(iterations));
        Process process = pb.start();

        String output;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            String last = null;
            while ((line = reader.readLine()) != null) {
                last = line;
            }
            output = last;
        }
        String errOutput;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            errOutput = sb.toString();
        }
        int exit = process.waitFor();
        if (exit != 0 || output == null || output.isEmpty()) {
            throw new IllegalStateException("Worker failed (exit=" + exit + ") for " + libName + "/" + mode
                    + " on " + file.label + ":\n" + errOutput);
        }
        return output;
    }

    private static void collectJsonFiles(Path dir, String labelPrefix, List<CorpusFile> out) throws Exception {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(p -> p.toString().endsWith(".json"))
                    .forEach(p -> out.add(new CorpusFile(p, labelPrefix + "/" + p.getFileName())));
        }
    }

    /** A corpus file paired with the path-independent label written to the result CSV. */
    private static final class CorpusFile {
        final Path path;
        final String label;

        CorpusFile(Path path, String label) {
            this.path = path;
            this.label = label;
        }
    }

    private static String buildClasspath(String buildDir, String libDir, String jsonparserJar) {
        StringBuilder sb = new StringBuilder();
        sb.append(buildDir);
        sb.append(File.pathSeparator).append(jsonparserJar);
        File lib = new File(libDir);
        File[] jars = lib.listFiles((d, name) -> name.endsWith(".jar"));
        if (jars != null) {
            for (File jar : jars) {
                sb.append(File.pathSeparator).append(jar.getAbsolutePath());
            }
        }
        return sb.toString();
    }
}
