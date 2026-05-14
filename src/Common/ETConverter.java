package Common;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Random;
import java.util.Scanner;

/**
 * 将 Tanaka 单机 TWET 数据转成同质并行机数据。
 * 当前转换时会直接附带生成一份任务级、与机器无关的 sequence-dependent setup 矩阵。
 */
public final class ETConverter {

    private static final double SETUP_ETA = 0.5;
    private static final long SETUP_BASE_SEED = 20260417L;

    private ETConverter() { }

    public static void convertFile(String path, int[] machineNums) throws IOException {
        Path in = Paths.get(path);
        try (Scanner sc = new Scanner(in)) {
            if (!sc.hasNextInt()) {
                throw new IllegalArgumentException("First token must be n");
            }
            int n = sc.nextInt();
            int[] p = new int[n];
            int[] d = new int[n];
            int[] wE = new int[n];
            int[] wT = new int[n];
            for (int i = 0; i < n; i++) {
                if (!sc.hasNextInt()) {
                    throw new IllegalArgumentException("Line " + (i + 2) + " missing p");
                }
                p[i] = sc.nextInt();
                if (!sc.hasNextInt()) {
                    throw new IllegalArgumentException("Line " + (i + 2) + " missing d");
                }
                d[i] = sc.nextInt();
                if (!sc.hasNextInt()) {
                    throw new IllegalArgumentException("Line " + (i + 2) + " missing wE");
                }
                wE[i] = sc.nextInt();
                if (!sc.hasNextInt()) {
                    throw new IllegalArgumentException("Line " + (i + 2) + " missing wT");
                }
                wT[i] = sc.nextInt();
            }

            for (int m : machineNums) {
                if (m <= 0) {
                    throw new IllegalArgumentException("m must be positive");
                }
                writeConvertedInstance(in, n, p, d, wE, wT, m);
            }
        }
    }

    private static void writeConvertedInstance(Path in, int n, int[] p, int[] d, int[] wE, int[] wT, int m)
            throws IOException {
        int[] scaledD = new int[n];
        for (int i = 0; i < n; i++) {
            scaledD[i] = (d[i] + m - 1) / m;
        }
        int[][] setup = buildSetupMatrix(p, scaledD, wE, wT, m);

        Path sourceDir = in.getParent();
        Path rootDir = sourceDir == null ? null : sourceDir.getParent();
        Path outDir = rootDir == null ? Paths.get(n + "-" + m) : rootDir.resolve(n + "-" + m);
        Files.createDirectories(outDir);
        String base = in.getFileName().toString();
        int dot = base.lastIndexOf('.');
        String stem = dot >= 0 ? base.substring(0, dot) : base;
        Path out = outDir.resolve(stem + "_" + m + "m.dat");

        try (BufferedWriter bw = Files.newBufferedWriter(out)) {
            bw.write(Integer.toString(n));
            bw.write(' ');
            bw.write(Integer.toString(m));
            bw.newLine();
            for (int i = 0; i < n; i++) {
                bw.write(String.format(Locale.US, "%d %d %d %d", p[i], scaledD[i], wE[i], wT[i]));
                bw.newLine();
            }
            bw.write("SETUP");
            bw.newLine();
            for (int i = 0; i <= n; i++) {
                for (int j = 0; j <= n; j++) {
                    if (j > 0) {
                        bw.write(' ');
                    }
                    bw.write(Integer.toString(setup[i][j]));
                }
                bw.newLine();
            }
        }
        System.out.printf("Converted %s -> %s%n", in.getFileName(), out);
    }

    /**
     * 2026-04-17: setup 直接跟任务对绑定，首任务 setup 记在 s[0][j]，末任务到虚拟终点保持 0。
     */
    private static int[][] buildSetupMatrix(int[] p, int[] d, int[] wE, int[] wT, int m) {
        int n = p.length;
        int[][] setup = new int[n + 1][n + 1];
        double avgProcessing = 0.0;
        for (int value : p) {
            avgProcessing += value;
        }
        avgProcessing /= n;
        double avgSetup = SETUP_ETA * avgProcessing;
        double stdDev = avgSetup / 2.0;
        double upperBound = 2.0 * avgSetup;
        Random random = new Random(buildSetupSeed(p, d, wE, wT, m));

        for (int j = 1; j <= n; j++) {
            setup[0][j] = sampleRoundedTruncatedNormal(random, avgSetup, stdDev, 0.0, upperBound);
        }
        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= n; j++) {
                if (i == j) {
                    continue;
                }
                setup[i][j] = sampleRoundedTruncatedNormal(random, avgSetup, stdDev, 0.0, upperBound);
            }
        }
        return setup;
    }

    private static long buildSetupSeed(int[] p, int[] d, int[] wE, int[] wT, int m) {
        long seed = SETUP_BASE_SEED;
        seed = 31L * seed + p.length;
        seed = 31L * seed + m;
        for (int i = 0; i < p.length; i++) {
            seed = 31L * seed + p[i];
            seed = 31L * seed + d[i];
            seed = 31L * seed + wE[i];
            seed = 31L * seed + wT[i];
        }
        return seed;
    }

    private static int sampleRoundedTruncatedNormal(Random random, double mean, double stdDev, double lowerBound,
            double upperBound) {
        if (stdDev <= 0.0) {
            return (int) Math.rint(mean);
        }
        double value;
        do {
            value = mean + random.nextGaussian() * stdDev;
        } while (value < lowerBound || value > upperBound);
        return (int) Math.rint(value);
    }

    public static void main(String[] args) throws IOException {
        final String rootDir = "D:/软件/eclipse/workspace/TWETScheduling/data";
        final int[] machines = new int[] { 2, 4 };

        Files.walk(Paths.get(rootDir)).filter(p -> p.toString().endsWith(".dat")).filter(p -> {
            Path parent = p.getParent();
            if (parent == null) {
                return false;
            }
            String dirName = parent.getFileName().toString();
            return "40-1".equals(dirName) || "50-1".equals(dirName) || "100-1".equals(dirName);
        }).forEach(p -> {
            try {
                convertFile(p.toString(), machines);
            } catch (Exception e) {
                System.err.println("Skip " + p + " : " + e.getMessage());
            }
        });
    }
}
