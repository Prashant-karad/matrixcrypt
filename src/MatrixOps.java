import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Matrix multiplication modulo 256:   C = A x B (mod 256)
 *
 * A is an n x n matrix (the key). B has one n x n matrix per colour channel
 * (3 for an RGB image, 1 for a plain benchmark matrix). The same A multiplies
 * every channel.
 *
 * Four ways to compute it (this is the heart of the DAA project):
 *   single : one thread computes every cell                     -> 1 thread
 *   row    : one new thread per row of C                        -> n threads
 *   cell   : one new thread per cell C[i][j]                    -> n^2 threads
 *   pool   : a fixed pool of (CPU cores) threads shares the rows -> p threads (bonus)
 *
 * All four do exactly the same arithmetic (computeCell), so the time difference
 * comes only from how the work is split between threads.
 */
final class MatrixOps {

    /**
     * Thread-per-cell creates n^2 threads. To avoid exhausting the operating system
     * we let at most this many of them be alive at the same moment. Every cell still
     * gets its own brand-new thread.
     */
    static final int MAX_LIVE_CELL_THREADS = 1000;

    static final String[] METHODS = {"single", "row", "pool", "cell"};

    private MatrixOps() {}

    static int cores() {
        return Runtime.getRuntime().availableProcessors();
    }

    static boolean isMethod(String m) {
        for (String s : METHODS) if (s.equals(m)) return true;
        return false;
    }

    /** C = A x B (mod 256) using the chosen method. Records the timeline in p. */
    static int[][][] multiply(String method, int[][] A, int[][][] B, Progress p) throws InterruptedException {
        int n = A.length;
        int channels = B.length;
        // Transpose B once so column j of B becomes a contiguous array.
        // This makes every dot product cache-friendly for ALL methods (fair comparison).
        int[][][] BT = transpose(B);
        int[][][] C = new int[channels][n][n];

        p.start();
        switch (method) {
            case "single" -> single(A, BT, C, p);
            case "row"    -> threadPerRow(A, BT, C, p);
            case "cell"   -> threadPerCell(A, BT, C, p);
            case "pool"   -> threadPool(A, BT, C, p);
            default -> throw new IllegalArgumentException("Unknown method: " + method);
        }
        p.finish();
        return C;
    }

    /**
     * The actual matrix multiplication for one cell:
     *   C[c][i][j] = ( sum over k of A[i][k] * B[c][k][j] ) mod 256
     * BT[c][j] is column j of B[c].
     */
    static void computeCell(int[][] A, int[][][] BT, int[][][] C, int i, int j) {
        int[] a = A[i];
        int n = a.length;
        for (int c = 0; c < BT.length; c++) {
            int[] b = BT[c][j];
            long sum = 0;                       // long: safe from overflow for any size
            for (int k = 0; k < n; k++) {
                sum += (long) a[k] * b[k];
            }
            C[c][i][j] = (int) (sum % 256);
        }
    }

    static void computeRow(int[][] A, int[][][] BT, int[][][] C, int i) {
        int n = A.length;
        for (int j = 0; j < n; j++) computeCell(A, BT, C, i, j);
    }

    // ---------------------------------------------------------------- 1. single-threaded

    private static void single(int[][] A, int[][][] BT, int[][][] C, Progress p) {
        p.threadsCreated.set(1);
        int n = A.length;
        for (int i = 0; i < n; i++) {
            computeRow(A, BT, C, i);
            p.rowDone(i, 0);
        }
    }

    // ---------------------------------------------------------------- 2. one thread per row

    private static void threadPerRow(int[][] A, int[][][] BT, int[][][] C, Progress p) throws InterruptedException {
        int n = A.length;
        Thread[] threads = new Thread[n];
        for (int i = 0; i < n; i++) {
            final int row = i;
            threads[i] = new Thread(() -> {
                computeRow(A, BT, C, row);
                p.rowDone(row, row);            // thread id = row number
            }, "row-" + i);
            threads[i].start();
            p.threadsCreated.incrementAndGet();
        }
        for (Thread t : threads) t.join();      // wait for every row
    }

    // ---------------------------------------------------------------- 3. one thread per cell

    private static void threadPerCell(int[][] A, int[][][] BT, int[][][] C, Progress p) throws InterruptedException {
        int n = A.length;
        AtomicInteger[] cellsLeftInRow = new AtomicInteger[n];
        for (int i = 0; i < n; i++) cellsLeftInRow[i] = new AtomicInteger(n);
        Semaphore alive = new Semaphore(MAX_LIVE_CELL_THREADS);

        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                final int row = i, col = j;
                alive.acquire();
                Thread t = new Thread(() -> {
                    try {
                        computeCell(A, BT, C, row, col);
                        if (cellsLeftInRow[row].decrementAndGet() == 0) {
                            p.rowDone(row, Progress.MANY_THREADS);
                        }
                    } finally {
                        alive.release();
                    }
                });
                t.start();
                p.threadsCreated.incrementAndGet();
            }
        }
        // All permits back = every cell thread has finished.
        alive.acquire(MAX_LIVE_CELL_THREADS);
        alive.release(MAX_LIVE_CELL_THREADS);
    }

    // ---------------------------------------------------------------- 4. thread pool (bonus)

    private static void threadPool(int[][] A, int[][][] BT, int[][][] C, Progress p) throws InterruptedException {
        int n = A.length;
        int workers = Math.min(cores(), n);
        AtomicInteger nextRow = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        for (int w = 0; w < workers; w++) {
            final int worker = w;
            pool.submit(() -> {
                int row;
                // Each worker keeps taking the next unfinished row (dynamic load balancing).
                while ((row = nextRow.getAndIncrement()) < n) {
                    computeRow(A, BT, C, row);
                    p.rowDone(row, worker);
                }
            });
        }
        p.threadsCreated.set(workers);
        pool.shutdown();
        pool.awaitTermination(1, TimeUnit.DAYS);
    }

    // ---------------------------------------------------------------- helpers

    static int[][][] transpose(int[][][] B) {
        int ch = B.length, n = B[0].length;
        int[][][] T = new int[ch][n][n];
        for (int c = 0; c < ch; c++)
            for (int k = 0; k < n; k++)
                for (int j = 0; j < n; j++)
                    T[c][j][k] = B[c][k][j];
        return T;
    }

    /** Plain single-channel product (mod 256) using the thread pool. Used for key generation. */
    static int[][] multiplyFast(int[][] A, int[][] B) throws InterruptedException {
        return multiply("pool", A, new int[][][]{B}, new Progress("pool", A.length))[0];
    }

    static boolean equal(int[][][] X, int[][][] Y) {
        if (X.length != Y.length) return false;
        for (int c = 0; c < X.length; c++)
            for (int i = 0; i < X[c].length; i++)
                if (!java.util.Arrays.equals(X[c][i], Y[c][i])) return false;
        return true;
    }
}
