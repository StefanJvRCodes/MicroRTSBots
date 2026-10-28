package ai.evolution.gp;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

/** Combines the summary.properties of every run in a batch folder: java ai.evolution.gp.GPStats runs/<batch> */
public class GPStats {

    private static final String[] COLUMNS = {
            "winRate", "worstCase", "matchupWinRateStd", "populationWinRateMean", "populationWinRateStd",
            "generations", "trainingSeconds",
    };

    public static void main(String[] args) throws IOException {
        Path batch = Paths.get(args.length > 0 ? args[0] : "runs");
        List<Path> summaries;
        try (Stream<Path> files = Files.walk(batch, 2)) {
            summaries = files.filter(f -> f.getFileName().toString().equals("summary.properties")).sorted().toList();
        }
        if (summaries.isEmpty()) {
            System.out.println("No summary.properties found under " + batch);
            return;
        }

        double[][] values = new double[COLUMNS.length][summaries.size()];
        StringBuilder csv = new StringBuilder("run," + String.join(",", COLUMNS) + "\n");
        for (int r = 0; r < summaries.size(); r++) {
            Properties p = new Properties();
            try (Reader reader = Files.newBufferedReader(summaries.get(r), StandardCharsets.UTF_8)) {
                p.load(reader);
            }
            csv.append(summaries.get(r).getParent().getFileName());
            for (int c = 0; c < COLUMNS.length; c++) {
                values[c][r] = Double.parseDouble(p.getProperty(COLUMNS[c], "NaN"));
                csv.append(',').append(values[c][r]);
            }
            csv.append('\n');
        }
        Files.writeString(batch.resolve("runs.csv"), csv, StandardCharsets.UTF_8);

        System.out.printf("%d runs in %s (also saved to runs.csv)%n%n", summaries.size(), batch);
        System.out.printf("%-10s %8s %9s %12s %10s %9s %5s %10s%n",
                "run", "winRate", "worstCase", "matchupStd", "popMean", "popStd", "gens", "seconds");
        for (int r = 0; r < summaries.size(); r++) {
            System.out.printf("%-10s %8.4f %9.4f %12.4f %10.4f %9.4f %5.0f %10.1f%n",
                    summaries.get(r).getParent().getFileName(), values[0][r], values[1][r], values[2][r],
                    values[3][r], values[4][r], values[5][r], values[6][r]);
        }

        System.out.printf("%nAcross runs%n%-24s %10s %10s %10s %10s%n", "", "mean", "std", "min", "max");
        List<String> lines = new ArrayList<>();
        for (int c = 0; c < COLUMNS.length; c++) {
            double[] v = values[c];
            lines.add(String.format("%-24s %10.4f %10.4f %10.4f %10.4f", COLUMNS[c], mean(v), std(v), min(v), max(v)));
        }
        lines.forEach(System.out::println);
        double totalSeconds = 0;
        for (double s : values[indexOf("trainingSeconds")]) totalSeconds += s;
        System.out.printf("%ntotal training time %.1f h%n", totalSeconds / 3600);
    }

    public static double mean(double[] v) {
        double sum = 0;
        for (double x : v) sum += x;
        return v.length == 0 ? 0 : sum / v.length;
    }

    /** Sample standard deviation (n - 1); 0 for fewer than two values. */
    public static double std(double[] v) {
        if (v.length < 2) return 0;
        double m = mean(v), sq = 0;
        for (double x : v) sq += (x - m) * (x - m);
        return Math.sqrt(sq / (v.length - 1));
    }

    private static double min(double[] v) {
        double m = Double.POSITIVE_INFINITY;
        for (double x : v) m = Math.min(m, x);
        return m;
    }

    private static double max(double[] v) {
        double m = Double.NEGATIVE_INFINITY;
        for (double x : v) m = Math.max(m, x);
        return m;
    }

    private static int indexOf(String column) {
        for (int c = 0; c < COLUMNS.length; c++) if (COLUMNS[c].equals(column)) return c;
        throw new IllegalArgumentException(column);
    }
}
