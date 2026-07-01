package Common;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * 为已有 Tanaka 派生并行机算例生成不同 setup severity 的实验变体。
 *
 * 2026-07-01: ETConverter 中的原始 eta 会被 Floyd 三角闭包显著压低，因此这里用
 * “闭包后的真实 job-to-job 平均 setup / 平均 processing”作为目标比例，并通过二分
 * 反推生成前 eta。默认不覆盖原数据，只写到指定输出目录。
 */
public final class SetupRatioVariantGenerator {

    private static final long SETUP_BASE_SEED = 20260417L;
    private static final int BINARY_SEARCH_ITERS = 36;

    private SetupRatioVariantGenerator() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 3) {
            System.out.println("Usage: Common.SetupRatioVariantGenerator <inputDat> <outputDir> <ratiosComma>");
            System.out.println("Example: Common.SetupRatioVariantGenerator data/40-2/wet040_001_2m.dat data/setup-variants/40-2 0.25,0.50,0.75");
            return;
        }
        Path input = Paths.get(args[0]);
        Path outputDir = Paths.get(args[1]);
        double[] targets = parseTargets(args[2]);
        Instance instance = readInstance(input);
        Files.createDirectories(outputDir);
        for (double target : targets) {
            GeneratedSetup generated = generateForTargetRatio(instance, target);
            Path output = outputDir.resolve(outputName(input, target));
            writeInstance(output, instance, generated.setup);
            System.out.printf(Locale.US,
                    "Generated %s target=%.4f preEta=%.6f postRatio=%.6f avgP=%.6f avgSetup=%.6f%n",
                    output, target, generated.preClosureEta, generated.postClosureRatio, generated.avgProcessing,
                    generated.avgSetup);
        }
    }

    private static double[] parseTargets(String text) {
        String[] parts = text.split(",");
        double[] values = new double[parts.length];
        for (int i = 0; i < parts.length; i++) {
            values[i] = Double.parseDouble(parts[i].trim());
            if (values[i] <= 0.0) {
                throw new IllegalArgumentException("target ratio must be positive: " + values[i]);
            }
        }
        return values;
    }

    private static String outputName(Path input, double target) {
        String base = input.getFileName().toString();
        int dot = base.lastIndexOf('.');
        String stem = dot >= 0 ? base.substring(0, dot) : base;
        String suffix = String.format(Locale.US, "_setupR%02d.dat", (int) Math.rint(target * 100.0));
        return stem + suffix;
    }

    private static Instance readInstance(Path input) throws IOException {
        List<String> lines = Files.readAllLines(input);
        if (lines.isEmpty()) {
            throw new IOException("empty instance: " + input);
        }
        String[] first = split(lines.get(0));
        if (first.length < 2) {
            throw new IOException("first line must contain n and m: " + input);
        }
        int n = Integer.parseInt(first[0]);
        int m = Integer.parseInt(first[1]);
        if (lines.size() < n + 1) {
            throw new IOException("not enough job rows: " + input);
        }
        int[] p = new int[n];
        int[] d = new int[n];
        int[] wE = new int[n];
        int[] wT = new int[n];
        List<String> jobRows = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            String row = lines.get(i + 1).trim();
            String[] tokens = split(row);
            if (tokens.length < 4) {
                throw new IOException("job row must contain p d wE wT at line " + (i + 2));
            }
            p[i] = Integer.parseInt(tokens[0]);
            d[i] = Integer.parseInt(tokens[1]);
            wE[i] = Integer.parseInt(tokens[2]);
            wT[i] = Integer.parseInt(tokens[3]);
            jobRows.add(row);
        }
        return new Instance(n, m, p, d, wE, wT, jobRows);
    }

    private static String[] split(String line) {
        return line.trim().split("\\s+");
    }

    private static void writeInstance(Path output, Instance instance, int[][] setup) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(output)) {
            writer.write(instance.n + " " + instance.m);
            writer.newLine();
            for (String row : instance.jobRows) {
                writer.write(row);
                writer.newLine();
            }
            writer.write("SETUP");
            writer.newLine();
            for (int i = 0; i <= instance.n; i++) {
                for (int j = 0; j <= instance.n; j++) {
                    if (j > 0) {
                        writer.write(' ');
                    }
                    writer.write(Integer.toString(setup[i][j]));
                }
                writer.newLine();
            }
        }
    }

    private static GeneratedSetup generateForTargetRatio(Instance instance, double targetRatio) {
        double avgProcessing = averageProcessing(instance.p);
        double low = 0.0;
        double high = Math.max(0.1, targetRatio * 4.0);
        GeneratedSetup highSetup = buildSetup(instance, high, avgProcessing);
        while (highSetup.postClosureRatio < targetRatio) {
            low = high;
            high *= 2.0;
            if (high > 100.0) {
                throw new IllegalStateException("cannot reach setup target ratio " + targetRatio);
            }
            highSetup = buildSetup(instance, high, avgProcessing);
        }
        GeneratedSetup best = highSetup;
        for (int iter = 0; iter < BINARY_SEARCH_ITERS; iter++) {
            double mid = (low + high) / 2.0;
            GeneratedSetup current = buildSetup(instance, mid, avgProcessing);
            best = current;
            if (current.postClosureRatio < targetRatio) {
                low = mid;
            } else {
                high = mid;
            }
        }
        return best;
    }

    private static GeneratedSetup buildSetup(Instance instance, double eta, double avgProcessing) {
        int[][] setup = new int[instance.n + 1][instance.n + 1];
        double avgSetup = eta * avgProcessing;
        double stdDev = avgSetup / 2.0;
        double upperBound = 2.0 * avgSetup;
        Random random = new Random(buildSetupSeed(instance));
        for (int j = 1; j <= instance.n; j++) {
            setup[0][j] = sampleRoundedTruncatedNormal(random, avgSetup, stdDev, 0.0, upperBound);
        }
        for (int i = 1; i <= instance.n; i++) {
            for (int j = 1; j <= instance.n; j++) {
                if (i != j) {
                    setup[i][j] = sampleRoundedTruncatedNormal(random, avgSetup, stdDev, 0.0, upperBound);
                }
            }
        }
        enforceDirectedTriangleInequality(setup);
        double avgClosedSetup = averageJobToJobSetup(setup, instance.n);
        return new GeneratedSetup(setup, eta, avgProcessing, avgClosedSetup, avgClosedSetup / avgProcessing);
    }

    private static double averageProcessing(int[] p) {
        double total = 0.0;
        for (int value : p) {
            total += value;
        }
        return total / p.length;
    }

    private static double averageJobToJobSetup(int[][] setup, int n) {
        double total = 0.0;
        int count = 0;
        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= n; j++) {
                if (i != j) {
                    total += setup[i][j];
                    count++;
                }
            }
        }
        return total / count;
    }

    private static long buildSetupSeed(Instance instance) {
        long seed = SETUP_BASE_SEED;
        seed = 31L * seed + instance.n;
        seed = 31L * seed + instance.m;
        for (int i = 0; i < instance.n; i++) {
            seed = 31L * seed + instance.p[i];
            seed = 31L * seed + instance.d[i];
            seed = 31L * seed + instance.wE[i];
            seed = 31L * seed + instance.wT[i];
        }
        return seed;
    }

    private static int sampleRoundedTruncatedNormal(Random random, double mean, double stdDev, double lowerBound,
            double upperBound) {
        if (Utility.compareLe(stdDev, 0.0)) {
            return (int) Math.rint(mean);
        }
        double value;
        do {
            value = mean + random.nextGaussian() * stdDev;
        } while (Utility.compareLt(value, lowerBound) || Utility.compareGt(value, upperBound));
        return (int) Math.rint(value);
    }

    private static void enforceDirectedTriangleInequality(int[][] setup) {
        int size = setup.length;
        for (int k = 0; k < size; k++) {
            for (int i = 0; i < size; i++) {
                if (i == k) {
                    continue;
                }
                for (int j = 0; j < size; j++) {
                    if (i == j || j == k) {
                        continue;
                    }
                    int via = setup[i][k] + setup[k][j];
                    if (via < setup[i][j]) {
                        setup[i][j] = via;
                    }
                }
            }
        }
    }

    private static final class Instance {
        final int n;
        final int m;
        final int[] p;
        final int[] d;
        final int[] wE;
        final int[] wT;
        final List<String> jobRows;

        Instance(int n, int m, int[] p, int[] d, int[] wE, int[] wT, List<String> jobRows) {
            this.n = n;
            this.m = m;
            this.p = p;
            this.d = d;
            this.wE = wE;
            this.wT = wT;
            this.jobRows = jobRows;
        }
    }

    private static final class GeneratedSetup {
        final int[][] setup;
        final double preClosureEta;
        final double avgProcessing;
        final double avgSetup;
        final double postClosureRatio;

        GeneratedSetup(int[][] setup, double preClosureEta, double avgProcessing, double avgSetup,
                double postClosureRatio) {
            this.setup = setup;
            this.preClosureEta = preClosureEta;
            this.avgProcessing = avgProcessing;
            this.avgSetup = avgSetup;
            this.postClosureRatio = postClosureRatio;
        }
    }
}

