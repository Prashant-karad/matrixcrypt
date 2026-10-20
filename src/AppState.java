import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** Everything the server remembers between requests (single user, in memory). */
final class AppState {

    // Current images as matrices [channel][row][col]
    int n = 512;
    BufferedImage source;          // uploaded picture (null = built-in sample)
    int[][][] original;            // P
    int[][][] encrypted;           // C = K x P
    int[][][] decrypted;           // P' = K^-1 x C
    int[][][] decryptedFrom;       // the C that P' was computed from
    long encSeed = -1, decSeed = -1;
    String encMethod, decMethod;

    // Photo Filter Studio
    int[][][] filtered;            // F applied to every pixel of P
    double[][] filterMatrix;       // 3 x 4  [F | offset]

    volatile Job job;
    volatile BenchJob bench;

    // Recently used keys, so the same seed/size is generated only once
    private final Map<String, KeyGenerator.KeyPair> keys = new LinkedHashMap<>(8, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, KeyGenerator.KeyPair> e) {
            return size() > 4;
        }
    };

    KeyGenerator.KeyPair key(int n, long seed) throws InterruptedException {
        String id = n + ":" + seed;
        synchronized (keys) {
            KeyGenerator.KeyPair k = keys.get(id);
            if (k != null) return k;
        }
        KeyGenerator.KeyPair k = KeyGenerator.generate(n, seed);
        synchronized (keys) {
            keys.put(id, k);
        }
        return k;
    }

    boolean hasKey(int n, long seed) {
        synchronized (keys) {
            return keys.containsKey(n + ":" + seed);
        }
    }

    boolean busy() {
        Job j = job;
        BenchJob b = bench;
        return (j != null && !j.finished) || (b != null && !b.finished);
    }

    /** One encrypt / decrypt / race request running in the background. */
    static final class Job {
        final String op, method;
        final int n;
        final long seed;
        volatile String phase = "starting";
        volatile String error;
        volatile boolean finished;
        volatile Progress progress;
        double[][] filter;         // only for op = "filter"
        final Map<String, Progress> race = Collections.synchronizedMap(new LinkedHashMap<>());

        Job(String op, String method, int n, long seed) {
            this.op = op;
            this.method = method;
            this.n = n;
            this.seed = seed;
        }
    }

    /** A benchmark running in the background. */
    static final class BenchJob {
        volatile String status = "starting";
        volatile String error;
        volatile boolean finished;
        final List<Benchmark.Result> results = new CopyOnWriteArrayList<>();
    }
}
