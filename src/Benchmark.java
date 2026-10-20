import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;

/**
 * Times every method on random n x n matrices of increasing size.
 *
 * - Runs a JIT warm-up first (Java gets faster after code has run a few times).
 * - Repeats each measurement and reports the median (less noise).
 * - Checks every method produced exactly the same matrix (correctness).
 * - Thread-per-cell is skipped above 512 (over a million threads would take very long).
 *
 * Can be used from the web UI or from the terminal:  ./run.sh bench
 */
final class Benchmark {

    static final int CELL_MAX_N = 512;

    record Result(int n, String method, double ms, long threads, boolean correct, boolean skipped) {}

    interface Listener {
        void status(String message);
        void result(Result r);
    }

    private Benchmark() {}

    static List<Result> run(int[] sizes, List<String> methods, Listener listener) throws InterruptedException {
        List<Result> results = new ArrayList<>();
        listener.status("Warming up the Java JIT compiler…");
        warmUp(methods);

        for (int n : sizes) {
            int[][] A = randomMatrix(n, 1000L + n);
            int[][][] B = {randomMatrix(n, 2000L + n)};
            int[][][] reference = null;

            for (String m : methods) {
                if (m.equals("cell") && n > CELL_MAX_N) {
                    Result r = new Result(n, m, -1, (long) n * n, true, true);
                    results.add(r);
                    listener.result(r);
                    continue;
                }
                int reps = repeatsFor(n, m);
                double[] times = new double[reps];
                long threads = 0;
                int[][][] C = null;
                for (int rep = 0; rep < reps; rep++) {
                    listener.status(String.format("n = %d · %s · run %d of %d", n, label(m), rep + 1, reps));
                    Progress p = new Progress(m, n);
                    C = MatrixOps.multiply(m, A, B, p);
                    times[rep] = p.elapsedMs();
                    threads = p.threadsCreated.get();
                }
                boolean correct = true;
                if (reference == null) reference = C;
                else correct = MatrixOps.equal(reference, C);
                Result r = new Result(n, m, median(times), threads, correct, false);
                results.add(r);
                listener.result(r);
            }
        }
        listener.status("Done");
        return results;
    }

    private static void warmUp(List<String> methods) throws InterruptedException {
        int[][] A = randomMatrix(96, 1);
        int[][][] B = {randomMatrix(96, 2)};
        for (int round = 0; round < 3; round++)
            for (String m : methods) MatrixOps.multiply(m, A, B, new Progress(m, 96));
    }

    private static int repeatsFor(int n, String method) {
        if (n >= 1024) return 1;
        if (method.equals("cell")) return n >= 256 ? 1 : 3;
        if (n >= 512) return 3;
        return 5;
    }

    static String label(String m) {
        return switch (m) {
            case "single" -> "Single-threaded";
            case "row" -> "Thread per row";
            case "cell" -> "Thread per cell";
            case "pool" -> "Thread pool";
            default -> m;
        };
    }

    static int[][] randomMatrix(int n, long seed) {
        SplittableRandom r = new SplittableRandom(seed);
        int[][] m = new int[n][n];
        for (int i = 0; i < n; i++)
            for (int j = 0; j < n; j++) m[i][j] = r.nextInt(256);
        return m;
    }

    private static double median(double[] t) {
        double[] s = t.clone();
        Arrays.sort(s);
        int k = s.length / 2;
        return s.length % 2 == 1 ? s[k] : (s[k - 1] + s[k]) / 2;
    }

    // ---------------------------------------------------------------- terminal mode

    /** ./run.sh bench [sizes]   e.g.  ./run.sh bench 64,128,256,512 */
    static void cli(String[] args) throws Exception {
        int[] sizes = args.length > 1
                ? Arrays.stream(args[1].split(",")).mapToInt(s -> Integer.parseInt(s.trim())).toArray()
                : new int[]{64, 128, 256, 512};
        List<String> methods = List.of(MatrixOps.METHODS);

        System.out.println("MatrixCrypt benchmark  ·  CPU cores: " + MatrixOps.cores()
                + "  ·  Java " + System.getProperty("java.version"));
        System.out.println("Each result is the median of several runs (C = A x B mod 256, one channel).\n");

        List<Result> results = run(sizes, methods, new Listener() {
            public void status(String s) { System.out.print("\r\033[K  " + s); }
            public void result(Result r) { }
        });
        System.out.print("\r\033[K");

        System.out.printf("%-6s %-18s %14s %12s %10s %9s%n", "n", "method", "time", "threads", "vs single", "correct");
        System.out.println("-".repeat(74));
        for (Result r : results) {
            double base = results.stream().filter(x -> x.n() == r.n() && x.method().equals("single"))
                    .mapToDouble(Result::ms).findFirst().orElse(-1);
            String time = r.skipped() ? "skipped" : String.format(Locale.ROOT, "%.2f ms", r.ms());
            String vs = (r.skipped() || base <= 0) ? "-" : String.format(Locale.ROOT, "%.2fx", base / r.ms());
            System.out.printf("%-6d %-18s %14s %12d %10s %9s%n", r.n(), label(r.method()), time, r.threads(), vs,
                    r.skipped() ? "-" : (r.correct() ? "yes" : "NO"));
        }

        Path csv = Path.of("bench-results.csv");
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(csv, StandardCharsets.UTF_8))) {
            w.println("n,method,time_ms,threads,correct");
            for (Result r : results)
                w.printf(Locale.ROOT, "%d,%s,%s,%d,%s%n", r.n(), r.method(),
                        r.skipped() ? "" : String.format(Locale.ROOT, "%.3f", r.ms()), r.threads(), r.correct());
        } catch (IOException e) {
            System.err.println("Could not write CSV: " + e.getMessage());
        }
        System.out.println("\nSaved: " + csv.toAbsolutePath());
        System.out.println("Speed-up above 1x = faster than single-threaded. Max possible ≈ number of cores ("
                + MatrixOps.cores() + ").");
    }
}
