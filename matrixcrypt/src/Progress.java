import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Records what happens during one matrix multiplication:
 *  - when each row of the result was finished (nanoseconds since start)
 *  - which thread finished it
 *  - how many threads were created in total
 *
 * The web UI uses this "timeline" to replay the computation visually.
 * Atomic arrays are used because many threads write into it at once.
 */
final class Progress {

    /** Thread id used for rows produced by thread-per-cell (each cell had its own thread). */
    static final int MANY_THREADS = -1;

    final String method;
    final int n;
    final AtomicIntegerArray rowThread;   // which thread finished row i
    final AtomicLongArray rowDoneNs;      // when row i was finished
    final AtomicInteger rowsDone = new AtomicInteger();
    final AtomicLong threadsCreated = new AtomicLong();

    private volatile long startNs;
    private volatile long endNs;
    private volatile boolean finished;

    Progress(String method, int n) {
        this.method = method;
        this.n = n;
        this.rowThread = new AtomicIntegerArray(n);
        this.rowDoneNs = new AtomicLongArray(n);
    }

    void start() {
        startNs = System.nanoTime();
    }

    void rowDone(int row, int threadId) {
        rowDoneNs.set(row, System.nanoTime() - startNs);
        rowThread.set(row, threadId);
        rowsDone.incrementAndGet();
    }

    void finish() {
        endNs = System.nanoTime();
        finished = true;
    }

    boolean isFinished() {
        return finished;
    }

    double elapsedMs() {
        if (startNs == 0) return 0;
        long end = finished ? endNs : System.nanoTime();
        return (end - startNs) / 1e6;
    }

    /** JSON timeline used by the browser to replay the run. */
    String timelineJson() {
        StringBuilder sb = new StringBuilder(n * 16 + 128);
        sb.append("{\"method\":\"").append(method).append('"')
          .append(",\"n\":").append(n)
          .append(",\"totalMs\":").append(String.format(java.util.Locale.ROOT, "%.3f", elapsedMs()))
          .append(",\"threads\":").append(threadsCreated.get())
          .append(",\"rowThread\":[");
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(',');
            sb.append(rowThread.get(i));
        }
        sb.append("],\"rowDoneMs\":[");
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(',');
            sb.append(String.format(java.util.Locale.ROOT, "%.3f", rowDoneNs.get(i) / 1e6));
        }
        sb.append("]}");
        return sb.toString();
    }
}
