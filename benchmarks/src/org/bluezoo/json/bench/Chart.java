package org.bluezoo.json.bench;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Draws {@code benchmark-comparison.svg}, the chart in the main README, from
 * the CSV produced by {@link Bench}.
 * <p>
 * It is a horizontal stacked bar chart with one row per library and mode and
 * one coloured segment per corpus file, so both the total and what each kind
 * of document contributes to it can be read off. A row's length is the sum of
 * that library/mode's median time over every corpus file, and rows run fastest
 * first. The segments run in descending order of how much time each file
 * takes summed over all rows; the files beyond the ten largest contributors
 * are grouped as "other". Colours and the dark theme are set by CSS classes in
 * the file itself.
 * <p>
 * Usage: {@code Chart <results.csv> <output.svg>}. It also prints the
 * whole-corpus totals, relative to jsonparser.
 */
public final class Chart {

    private static final int LEFT = 168;
    private static final int PLOT_WIDTH = 734;
    private static final int TOP = 108;
    private static final int ROW_PITCH = 52;
    private static final int BAR_HEIGHT = 34;
    private static final int MAX_NAMED_FILES = 10;
    private static final int MAX_TICKS = 8;
    private static final double MAX_CORNER_RADIUS = 4;
    private static final int LEGEND_COLUMNS = 3;
    private static final int LEGEND_COLUMN_WIDTH = 268;

    private static final String STYLE = """
<style>
  .surface { fill: #fcfcfb; }
  .ink-primary { fill: #0b0b0b; }
  .ink-secondary { fill: #52514e; }
  .ink-muted { fill: #898781; }
  .grid { stroke: #e1e0d9; stroke-width: 1; }
  .axis { stroke: #c3c2b7; stroke-width: 1; }
  .gap { stroke: #fcfcfb; stroke-width: 2; }
  text { font-family: system-ui, -apple-system, "Segoe UI", sans-serif; }
  .title { font-size: 22px; font-weight: 500; }
  .subtitle { font-size: 14px; }
  .lib-label { font-size: 13px; font-weight: 500; }
  .mode-label { font-size: 12px; }
  .total-label { font-size: 13px; font-weight: 500; }
  .axis-tick { font-size: 11px; }
  .legend-label { font-size: 12px; }
  .cat-0 { fill: #2a78d6; }
  .cat-1 { fill: #1baf7a; }
  .cat-2 { fill: #eda100; }
  .cat-3 { fill: #008300; }
  .cat-4 { fill: #4a3aa7; }
  .cat-5 { fill: #e34948; }
  .cat-6 { fill: #e87ba4; }
  .cat-7 { fill: #eb6834; }
  .cat-8 { fill: #0b6e7a; }
  .cat-9 { fill: #8a5a2b; }
  .cat-10 { fill: #898781; }
  @media (prefers-color-scheme: dark) {
    .surface { fill: #1a1a19; }
    .ink-primary { fill: #ffffff; }
    .ink-secondary { fill: #c3c2b7; }
    .ink-muted { fill: #898781; }
    .grid { stroke: #2c2c2a; }
    .axis { stroke: #383835; }
    .gap { stroke: #1a1a19; }
    .cat-0 { fill: #3987e5; }
    .cat-1 { fill: #199e70; }
    .cat-2 { fill: #c98500; }
    .cat-3 { fill: #008300; }
    .cat-4 { fill: #9085e9; }
    .cat-5 { fill: #e66767; }
    .cat-6 { fill: #d55181; }
    .cat-7 { fill: #d95926; }
    .cat-8 { fill: #1592a0; }
    .cat-9 { fill: #b17a42; }
    .cat-10 { fill: #c3c2b7; }
  }
</style>
""";

    private static final String[] NUMBER_WORDS = {
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve"
    };

    /** One library/mode: its time on each corpus file, and the sum. */
    private static final class Row {
        final String library;
        final String mode;
        final Map<String, Double> msByFile = new HashMap<>();
        double totalMs;

        Row(String library, String mode) {
            this.library = library;
            this.mode = mode;
        }

        String name() {
            return library + "/" + mode;
        }
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("usage: Chart <results.csv> <output.svg>");
            System.exit(2);
        }
        List<Row> rows = readRows(args[0]);
        if (rows.isEmpty()) {
            throw new IOException("no results in " + args[0]);
        }
        rows.sort((a, b) -> Double.compare(a.totalMs, b.totalMs));

        Files.write(Paths.get(args[1]), draw(rows).getBytes(StandardCharsets.UTF_8));
        printTotals(rows);
    }

    private static List<Row> readRows(String csv) throws IOException {
        Map<String, Row> byName = new HashMap<>();
        List<Row> rows = new ArrayList<>();
        List<String> lines = Files.readAllLines(Paths.get(csv));
        for (int i = 1; i < lines.size(); i++) { // the first line is the header
            String line = lines.get(i);
            if (line.trim().isEmpty()) {
                continue;
            }
            String[] parts = line.split(",");
            String file = Paths.get(parts[2]).getFileName().toString();
            double ms = Long.parseLong(parts[5]) / 1_000_000.0; // the median, in ms
            Row row = byName.get(parts[0] + "/" + parts[1]);
            if (row == null) {
                row = new Row(parts[0], parts[1]);
                byName.put(row.name(), row);
                rows.add(row);
            }
            row.msByFile.merge(file, ms, Double::sum);
            row.totalMs += ms;
        }
        return rows;
    }

    private static String draw(List<Row> rows) {
        // The files that take the most time overall come first, so the largest segments sit leftmost
        Map<String, Double> fileTotals = new HashMap<>();
        for (Row r : rows) {
            r.msByFile.forEach((f, ms) -> fileTotals.merge(f, ms, Double::sum));
        }
        List<String> files = new ArrayList<>(fileTotals.keySet());
        files.sort((a, b) -> {
            int c = Double.compare(fileTotals.get(b), fileTotals.get(a));
            return c != 0 ? c : a.compareTo(b);
        });
        List<String> named = new ArrayList<>(files.subList(0, Math.min(MAX_NAMED_FILES, files.size())));
        List<String> rest = new ArrayList<>(files.subList(named.size(), files.size()));
        List<String> categories = new ArrayList<>(named);
        if (!rest.isEmpty()) {
            categories.add("other (" + rest.size() + " smaller files)");
        }

        double maxTotal = rows.get(rows.size() - 1).totalMs;
        double step = tickStep(maxTotal);
        int ticks = (int) Math.ceil(maxTotal / step);
        double axisMax = ticks * step;
        double scale = PLOT_WIDTH / axisMax;
        int bottom = TOP + (rows.size() - 1) * ROW_PITCH + BAR_HEIGHT;
        int gridTop = TOP - 6;

        StringBuilder svg = new StringBuilder();
        svg.append("<svg viewBox=\"0 0 980 760\" xmlns=\"http://www.w3.org/2000/svg\" role=\"img\" aria-label=\"")
                .append("Horizontal stacked bar chart comparing total JSON parse time across ")
                .append(words(rows.size())).append(" Java JSON library configurations, broken down by corpus file. ")
                .append(rows.get(0).name()).append(" is fastest at ").append(f2(rows.get(0).totalMs))
                .append(" milliseconds; ").append(rows.get(rows.size() - 1).name()).append(" is slowest at ")
                .append(f2(maxTotal)).append(" milliseconds.\">\n");
        svg.append(STYLE);
        svg.append("<rect class=\"surface\" x=\"0\" y=\"0\" width=\"980\" height=\"760\" rx=\"12\"/>\n");
        svg.append("<text x=\"168\" y=\"40\" class=\"title ink-primary\">JSON parser benchmark comparison</text>\n");
        svg.append("<text x=\"168\" y=\"64\" class=\"subtitle ink-secondary\">Total time to parse the full ")
                .append(files.size()).append("-file benchmark corpus, in milliseconds, by library and mode. ")
                .append("Smaller is better.</text>\n");

        for (int t = 0; t <= ticks; t++) {
            double value = t * step;
            String x = f1(LEFT + value * scale);
            svg.append("<line class=\"grid\" x1=\"").append(x).append("\" y1=\"").append(gridTop)
                    .append("\" x2=\"").append(x).append("\" y2=\"").append(bottom).append("\"/>\n");
            svg.append("<text x=\"").append(x).append("\" y=\"").append(bottom + 22)
                    .append("\" text-anchor=\"middle\" class=\"axis-tick ink-muted\">")
                    .append(tickLabel(value)).append("</text>\n");
        }
        svg.append("<text x=\"").append(f1(LEFT + PLOT_WIDTH / 2.0)).append("\" y=\"").append(bottom + 40)
                .append("\" text-anchor=\"middle\" class=\"axis-tick ink-muted\">milliseconds</text>\n");
        svg.append("<line class=\"axis\" x1=\"168\" y1=\"").append(gridTop).append("\" x2=\"168\" y2=\"")
                .append(bottom).append("\"/>\n");

        for (int i = 0; i < rows.size(); i++) {
            drawRow(svg, rows.get(i), TOP + i * ROW_PITCH, named, rest, categories, scale);
        }

        int legendTop = bottom + 52;
        svg.append("<text x=\"168\" y=\"").append(legendTop).append("\" class=\"axis-tick ink-muted\">corpus file</text>\n");
        for (int c = 0; c < categories.size(); c++) {
            int x = LEFT + (c % LEGEND_COLUMNS) * LEGEND_COLUMN_WIDTH;
            int y = legendTop + 4 + (c / LEGEND_COLUMNS) * 24;
            svg.append("<rect x=\"").append(x).append("\" y=\"").append(y)
                    .append("\" width=\"12\" height=\"12\" rx=\"3\" class=\"cat-").append(c).append("\"/>\n");
            svg.append("<text x=\"").append(x + 18).append("\" y=\"").append(y + 10)
                    .append("\" class=\"legend-label ink-secondary\">").append(categories.get(c)).append("</text>\n");
        }
        svg.append("</svg>");
        return svg.toString();
    }

    private static void drawRow(StringBuilder svg, Row row, int y, List<String> named, List<String> rest,
                                List<String> categories, double scale) {
        // The segments of this row, in legend order, with their times
        List<Integer> index = new ArrayList<>();
        List<Double> ms = new ArrayList<>();
        for (int c = 0; c < categories.size(); c++) {
            double v;
            if (c < named.size()) {
                v = row.msByFile.getOrDefault(named.get(c), 0.0);
            } else {
                v = 0;
                for (String f : rest) {
                    v += row.msByFile.getOrDefault(f, 0.0);
                }
            }
            if (v > 0) {
                index.add(c);
                ms.add(v);
            }
        }

        double cumulative = 0;
        for (int k = 0; k < index.size(); k++) {
            double x0 = LEFT + cumulative * scale;
            cumulative += ms.get(k);
            double x1 = LEFT + cumulative * scale;
            double radius = Math.min(MAX_CORNER_RADIUS, (x1 - x0) / 2);
            String cls = "cat-" + index.get(k);
            String title = categories.get(index.get(k)) + ": " + f2(ms.get(k)) + " ms";
            if (k > 0) {
                svg.append("<line class=\"gap\" x1=\"").append(f2(x0)).append("\" y1=\"").append(y)
                        .append("\" x2=\"").append(f2(x0)).append("\" y2=\"").append(y + BAR_HEIGHT).append("\"/>\n");
            }
            if (k == 0) {
                // the left end of the bar is rounded
                svg.append("<path d=\"M ").append(f2(x0 + radius)).append(' ').append(num(y))
                        .append(" L ").append(f2(x1)).append(' ').append(num(y))
                        .append(" L ").append(f2(x1)).append(' ').append(num(y + BAR_HEIGHT))
                        .append(" L ").append(f2(x0 + radius)).append(' ').append(num(y + BAR_HEIGHT))
                        .append(" Q ").append(f2(x0)).append(' ').append(num(y + BAR_HEIGHT))
                        .append(' ').append(f2(x0)).append(' ').append(num(y + BAR_HEIGHT - radius))
                        .append(" L ").append(f2(x0)).append(' ').append(num(y + radius))
                        .append(" Q ").append(f2(x0)).append(' ').append(num(y))
                        .append(' ').append(f2(x0 + radius)).append(' ').append(num(y))
                        .append(" Z\" class=\"").append(cls).append("\">\n");
                svg.append("<title>").append(title).append("</title>\n</path>\n");
            } else if (k == index.size() - 1) {
                // the right end is rounded
                svg.append("<path d=\"M ").append(f2(x0)).append(' ').append(num(y))
                        .append(" L ").append(f2(x1 - radius)).append(' ').append(num(y))
                        .append(" Q ").append(f2(x1)).append(' ').append(num(y))
                        .append(' ').append(f2(x1)).append(' ').append(num(y + radius))
                        .append(" L ").append(f2(x1)).append(' ').append(num(y + BAR_HEIGHT - radius))
                        .append(" Q ").append(f2(x1)).append(' ').append(num(y + BAR_HEIGHT))
                        .append(' ').append(f2(x1 - radius)).append(' ').append(num(y + BAR_HEIGHT))
                        .append(" L ").append(f2(x0)).append(' ').append(num(y + BAR_HEIGHT))
                        .append(" Z\" class=\"").append(cls).append("\">\n");
                svg.append("<title>").append(title).append("</title>\n</path>\n");
            } else {
                svg.append("<rect x=\"").append(f2(x0)).append("\" y=\"").append(y).append("\" width=\"")
                        .append(f2(x1 - x0)).append("\" height=\"").append(BAR_HEIGHT).append("\" class=\"")
                        .append(cls).append("\">\n");
                svg.append("<title>").append(title).append("</title>\n</rect>\n");
            }
        }

        double barEnd = LEFT + row.totalMs * scale;
        svg.append("<text x=\"156\" y=\"").append(f1(y + 14)).append("\" text-anchor=\"end\" class=\"lib-label ink-primary\">")
                .append(row.library).append("</text>\n");
        svg.append("<text x=\"156\" y=\"").append(f1(y + 29)).append("\" text-anchor=\"end\" class=\"mode-label ink-secondary\">")
                .append(row.mode).append("</text>\n");
        svg.append("<text x=\"").append(f1(barEnd + 10)).append("\" y=\"").append(f1(y + 21.5))
                .append("\" class=\"total-label ink-primary\">").append(f2(row.totalMs)).append(" ms</text>\n");
    }

    /** The smallest 1, 2 or 5 times a power of ten that needs at most {@link #MAX_TICKS} gridlines to cover the largest total. */
    private static double tickStep(double max) {
        double magnitude = 0.01;
        while (true) {
            for (double m : new double[] {1, 2, 5}) {
                double step = m * magnitude;
                if (Math.ceil(max / step) <= MAX_TICKS) {
                    return step;
                }
            }
            magnitude *= 10;
        }
    }

    private static String tickLabel(double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : f1(value);
    }

    private static void printTotals(List<Row> rows) {
        double baseline = 0;
        for (Row r : rows) {
            if (r.name().equals("jsonparser/stream")) {
                baseline = r.totalMs;
            }
        }
        System.out.printf(Locale.ROOT, "%-18s %10s %14s%n", "library/mode", "total ms", "vs jsonparser");
        for (Row r : rows) {
            String rel = baseline == 0 ? "n/a" : String.format(Locale.ROOT, "%.2fx", r.totalMs / baseline);
            System.out.printf(Locale.ROOT, "%-18s %10.2f %14s%n", r.name(), r.totalMs, rel);
        }
    }

    private static String words(int n) {
        return n < NUMBER_WORDS.length ? NUMBER_WORDS[n] : Integer.toString(n);
    }

    private static String f1(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    private static String f2(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    /** A coordinate as the shortest text: whole numbers without a decimal point. */
    private static String num(double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : Double.toString(v);
    }
}
