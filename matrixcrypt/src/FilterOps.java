import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Photo Filter Studio: colour filters as matrix multiplication.
 *
 * Every pixel is a vector [R, G, B]. A filter is a 3 x 3 matrix F (plus an optional offset).
 *
 *   | R' |   | f00 f01 f02 |   | R |   | o0 |
 *   | G' | = | f10 f11 f12 | x | G | + | o1 |      then clamp to 0..255
 *   | B' |   | f20 f21 f22 |   | B |   | o2 |
 *
 * Seen as one big product: the image is an (n*n) x 3 matrix Q (one row per pixel),
 * and the result is Q x F^T. The same four threading strategies as MatrixOps are used:
 *   single : 1 thread
 *   row    : one thread per image row        -> n threads
 *   cell   : one thread per pixel            -> n^2 threads
 *   pool   : fixed pool of CPU-core workers  -> p threads
 */
final class FilterOps {

    record Filter(String id, String name, String about, double[][] m) {}   // m is 3 x 4: [F | offset]

    static final Filter[] PRESETS = {
        new Filter("grayscale", "Grayscale", "Luminance weights from the ITU-R BT.601 standard.", new double[][]{
            {0.299, 0.587, 0.114, 0}, {0.299, 0.587, 0.114, 0}, {0.299, 0.587, 0.114, 0}}),
        new Filter("sepia", "Sepia", "Classic warm brown photo tone.", new double[][]{
            {0.393, 0.769, 0.189, 0}, {0.349, 0.686, 0.168, 0}, {0.272, 0.534, 0.131, 0}}),
        new Filter("invert", "Invert", "Negative: new = 255 − old (uses the offset column).", new double[][]{
            {-1, 0, 0, 255}, {0, -1, 0, 255}, {0, 0, -1, 255}}),
        new Filter("warm", "Warm", "Boosts red, slightly reduces blue.", new double[][]{
            {1.15, 0.05, 0, 0}, {0.02, 1.02, 0, 0}, {0, 0, 0.85, 0}}),
        new Filter("cool", "Cool", "Boosts blue, slightly reduces red.", new double[][]{
            {0.85, 0, 0, 0}, {0, 1.0, 0.05, 0}, {0, 0.05, 1.18, 0}}),
        new Filter("vintage", "Vintage", "Faded, low-contrast film look.", new double[][]{
            {0.62, 0.32, 0.08, 28}, {0.22, 0.66, 0.10, 22}, {0.20, 0.30, 0.42, 30}}),
        new Filter("protanopia", "Protanopia (red-blind)", "Simulates red-cone colour blindness (Machado et al., 2009).", new double[][]{
            {0.152286, 1.052583, -0.204868, 0}, {0.114503, 0.786281, 0.099216, 0}, {-0.003882, -0.048116, 1.051998, 0}}),
        new Filter("deuteranopia", "Deuteranopia (green-blind)", "Simulates green-cone colour blindness, the most common type (Machado et al., 2009).", new double[][]{
            {0.367322, 0.860646, -0.227968, 0}, {0.280085, 0.672501, 0.047413, 0}, {-0.011820, 0.042940, 0.968881, 0}}),
        new Filter("tritanopia", "Tritanopia (blue-blind)", "Simulates blue-cone colour blindness (Machado et al., 2009).", new double[][]{
            {1.255528, -0.076749, -0.178779, 0}, {-0.078411, 0.930809, 0.147602, 0}, {0.004733, 0.691367, 0.303900, 0}}),
    };

    private FilterOps() {}

    static Filter preset(String id) {
        for (Filter f : PRESETS) if (f.id().equals(id)) return f;
        return null;
    }

    /** Applies F to every pixel using the chosen threading method. */
    static int[][][] apply(String method, double[][] F, int[][][] P, Progress p) throws InterruptedException {
        int n = P[0].length;
        int[][][] out = new int[3][n][n];
        p.start();
        switch (method) {
            case "single" -> {
                p.threadsCreated.set(1);
                for (int y = 0; y < n; y++) { row(F, P, out, y); p.rowDone(y, 0); }
            }
            case "row" -> {
                Thread[] ts = new Thread[n];
                for (int y = 0; y < n; y++) {
                    final int r = y;
                    ts[y] = new Thread(() -> { row(F, P, out, r); p.rowDone(r, r); });
                    ts[y].start();
                    p.threadsCreated.incrementAndGet();
                }
                for (Thread t : ts) t.join();
            }
            case "cell" -> {
                AtomicInteger[] left = new AtomicInteger[n];
                for (int y = 0; y < n; y++) left[y] = new AtomicInteger(n);
                Semaphore alive = new Semaphore(MatrixOps.MAX_LIVE_CELL_THREADS);
                for (int y = 0; y < n; y++) {
                    for (int x = 0; x < n; x++) {
                        final int r = y, c = x;
                        alive.acquire();
                        new Thread(() -> {
                            try {
                                pixel(F, P, out, r, c);
                                if (left[r].decrementAndGet() == 0) p.rowDone(r, Progress.MANY_THREADS);
                            } finally { alive.release(); }
                        }).start();
                        p.threadsCreated.incrementAndGet();
                    }
                }
                alive.acquire(MatrixOps.MAX_LIVE_CELL_THREADS);
                alive.release(MatrixOps.MAX_LIVE_CELL_THREADS);
            }
            case "pool" -> {
                int workers = Math.min(MatrixOps.cores(), n);
                AtomicInteger next = new AtomicInteger();
                ExecutorService pool = Executors.newFixedThreadPool(workers);
                for (int w = 0; w < workers; w++) {
                    final int id = w;
                    pool.submit(() -> {
                        int r;
                        while ((r = next.getAndIncrement()) < n) { row(F, P, out, r); p.rowDone(r, id); }
                    });
                }
                p.threadsCreated.set(workers);
                pool.shutdown();
                pool.awaitTermination(1, TimeUnit.DAYS);
            }
            default -> throw new IllegalArgumentException("Unknown method: " + method);
        }
        p.finish();
        return out;
    }

    static void row(double[][] F, int[][][] P, int[][][] out, int y) {
        int n = P[0].length;
        for (int x = 0; x < n; x++) pixel(F, P, out, y, x);
    }

    /** The 3 x 3 matrix-vector multiplication for one pixel. */
    static void pixel(double[][] F, int[][][] P, int[][][] out, int y, int x) {
        int r = P[0][y][x], g = P[1][y][x], b = P[2][y][x];
        for (int c = 0; c < 3; c++) {
            double v = F[c][0] * r + F[c][1] * g + F[c][2] * b + F[c][3];
            out[c][y][x] = v <= 0 ? 0 : v >= 255 ? 255 : (int) Math.round(v);
        }
    }
}
