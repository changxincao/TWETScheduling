package Common;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Convert a Tanaka‑style single‑machine E/T instance (n then n rows: p, d, wE, wT)
 * into an identical‑parallel‑machine instance (due‑date scaled by 1/m).
 * <p>
 * Public API: {@link #convertFile(String, int)} – call once per file, no CLI args.
 */
public final class ETConverter {

    private ETConverter() { }

    /**
     * Convert given instance file for <code>m</code> identical machines.
     * <ul>
     *   <li>Input format<br> 1st line: n<br> next n lines: p  d  wE  wT (integers, whitespace‑separated)</li>
     *   <li>Transformation: new&nbsp;d = ceil(old&nbsp;d / m)</li>
     *   <li>Output directory: <code>n-<em>m</em>m/</code> (creates if absent)</li>
     *   <li>Output file: <code>&lt;basename&gt;_<em>m</em>m.dat</code> with same 4‑column rows</li>
     * </ul>
     * @param path input file path (absolute or relative)
     * @param m    number of identical machines (>0)
     * @throws IOException if I/O error or bad format
     */
    public static void convertFile(String path, int[] machine_nums) throws IOException {
    	for(int m:machine_nums) {
    	if (m <= 0) throw new IllegalArgumentException("m must be positive");
        Path in = Paths.get(path);
        try (Scanner sc = new Scanner(in)) {
            if (!sc.hasNextInt()) throw new IllegalArgumentException("First token must be n");
            int n = sc.nextInt();
            int[] p = new int[n];
            int[] d = new int[n];
            int[] wE = new int[n];
            int[] wT = new int[n];
            for (int i = 0; i < n; i++) {
                if (!sc.hasNextInt()) throw new IllegalArgumentException("Line " + (i + 2) + " missing p");
                p[i]  = sc.nextInt();
                if (!sc.hasNextInt()) throw new IllegalArgumentException("Line " + (i + 2) + " missing d");
                d[i]  = (sc.nextInt() + m - 1) / m; // scale & ceil
                if (!sc.hasNextInt()) throw new IllegalArgumentException("Line " + (i + 2) + " missing wE");
                wE[i] = sc.nextInt();
                if (!sc.hasNextInt()) throw new IllegalArgumentException("Line " + (i + 2) + " missing wT");
                wT[i] = sc.nextInt();
            }
            // prepare output directory & file
            Path outDir = in.getParent() == null ? Paths.get(n + "-" + m )
                                                 : in.getParent().resolve(n + "-" + m);
            Files.createDirectories(outDir);
            String base = in.getFileName().toString();
            int dot = base.lastIndexOf('.');
            String stem = dot >= 0 ? base.substring(0, dot) : base;
            Path out = outDir.resolve(stem + "_" + m + "m.dat");

            try (BufferedWriter bw = Files.newBufferedWriter(out)) {
                bw.write(Integer.toString(n)+" "+m);
                bw.newLine();
                for (int i = 0; i < n; i++) {
                    bw.write(String.format(Locale.US, "%d %d %d %d", p[i], d[i], wE[i], wT[i]));
                    bw.newLine();
                }
            }
            System.out.printf("Converted %s -> %s%n", in.getFileName(), out);
        }
    	}
    }

    /**
     * Minimal demonstration: change ROOT_DIR & machines as needed then run <tt>main</tt>.
     */
    public static void main(String[] args) throws IOException {
        final String ROOT_DIR = "D:/软件/eclipse/workspace/TWETScheduling/data"; // 根目录
        final int[] MACHINES = new int[] {2,4}; // m 值
        
        Files.walk(Paths.get(ROOT_DIR))
             .filter(p -> p.toString().endsWith(".dat"))
             .forEach(p -> {
                 try { convertFile(p.toString(), MACHINES); }
                 catch (Exception e) { System.err.println("Skip " + p + " : " + e.getMessage()); }
             });
    }
}
