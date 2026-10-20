import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Tiny web server built into Java (no frameworks).
 * Serves the web/ folder and a small JSON API used by the browser UI.
 */
final class WebServer {

    private final AppState st = new AppState();
    private final Path webRoot;

    private WebServer(Path webRoot) {
        this.webRoot = webRoot.toAbsolutePath().normalize();
        st.n = 512;
        st.original = ImageCipher.sample(st.n);
    }

    static void start(int port, Path webRoot) throws IOException {
        WebServer ws = new WebServer(webRoot);
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        server.createContext("/api/", ws::handleApi);
        server.createContext("/", ws::handleStatic);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
        // Warm up the JIT compiler so the very first measured run is not unfairly slow.
        Thread warm = new Thread(() -> {
            try {
                int[][] A = Benchmark.randomMatrix(96, 1);
                int[][][] B = {Benchmark.randomMatrix(96, 2), Benchmark.randomMatrix(96, 3), Benchmark.randomMatrix(96, 4)};
                double[][] F = FilterOps.PRESETS[1].m();
                for (int r = 0; r < 4; r++)
                    for (String m : MatrixOps.METHODS) {
                        if (m.equals("cell") && r > 0) continue;
                        MatrixOps.multiply(m, A, B, new Progress(m, 96));
                        FilterOps.apply(m, F, B, new Progress(m, 96));
                    }
            } catch (InterruptedException ignored) { }
        }, "jit-warmup");
        warm.setDaemon(true);
        warm.start();
        System.out.println();
        System.out.println("  MatrixCrypt is running");
        System.out.println("  Open  ->  http://localhost:" + port);
        System.out.println("  (In GitHub Codespaces: open the PORTS tab and click the globe icon next to " + port + ")");
        System.out.println("  CPU cores available: " + MatrixOps.cores());
        System.out.println("  Press Ctrl+C to stop.");
        System.out.println();
    }

    // ================================================================= API

    private void handleApi(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            String verb = ex.getRequestMethod();
            Map<String, String> q = query(ex);
            switch (verb + " " + path) {
                case "GET /api/state" -> state(ex);
                case "POST /api/image" -> uploadImage(ex, q);
                case "GET /api/img/original.png" -> png(ex, st.original);
                case "GET /api/img/encrypted.png" -> png(ex, st.encrypted);
                case "GET /api/img/decrypted.png" -> png(ex, st.decrypted);
                case "GET /api/img/filtered.png" -> png(ex, st.filtered);
                case "GET /api/filters" -> filters(ex);
                case "POST /api/run" -> run(ex, q);
                case "GET /api/job" -> job(ex);
                case "GET /api/timeline" -> timeline(ex);
                case "GET /api/race" -> race(ex);
                case "GET /api/verify" -> verify(ex);
                case "GET /api/inspect" -> inspect(ex, q);
                case "POST /api/bench" -> startBench(ex, q);
                case "GET /api/bench" -> benchStatus(ex);
                default -> error(ex, 404, "Unknown API endpoint: " + verb + " " + path);
            }
        } catch (Exception e) {
            e.printStackTrace();
            error(ex, 500, e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            ex.close();
        }
    }

    private void state(HttpExchange ex) throws IOException {
        StringBuilder sb = new StringBuilder("{");
        synchronized (st) {
            sb.append("\"n\":").append(st.n)
              .append(",\"cores\":").append(MatrixOps.cores())
              .append(",\"java\":").append(str(System.getProperty("java.version")))
              .append(",\"sample\":").append(st.source == null)
              .append(",\"hasEncrypted\":").append(st.encrypted != null)
              .append(",\"hasDecrypted\":").append(st.decrypted != null)
              .append(",\"encSeed\":").append(st.encSeed)
              .append(",\"decSeed\":").append(st.decSeed)
              .append(",\"busy\":").append(st.busy());
        }
        sb.append('}');
        json(ex, sb.toString());
    }

    private void uploadImage(HttpExchange ex, Map<String, String> q) throws IOException {
        if (st.busy()) { error(ex, 409, "Please wait for the current run to finish."); return; }
        int n = intParam(q, "n", st.n);
        if (n != 128 && n != 256 && n != 512 && n != 1024) { error(ex, 400, "Size must be 128, 256, 512 or 1024."); return; }

        byte[] body;
        try (InputStream in = ex.getRequestBody()) { body = in.readAllBytes(); }

        BufferedImage src;
        boolean sample = "1".equals(q.get("sample"));
        boolean reuse = "1".equals(q.get("reuse"));
        if (sample) {
            src = null;
        } else if (reuse) {
            src = st.source;
        } else {
            if (body.length == 0) { error(ex, 400, "No image received."); return; }
            src = ImageIO.read(new ByteArrayInputStream(body));
            if (src == null) { error(ex, 400, "Could not read that image. Please use PNG, JPG, GIF or BMP."); return; }
        }
        int[][][] m = (src == null) ? ImageCipher.sample(n) : ImageCipher.fromImage(src, n);
        synchronized (st) {
            st.n = n;
            st.source = src;
            st.original = m;
            st.encrypted = st.decrypted = st.decryptedFrom = null;
            st.encSeed = st.decSeed = -1;
            st.filtered = null;
        }
        json(ex, "{\"ok\":true,\"n\":" + n + "}");
    }

    private void png(HttpExchange ex, int[][][] m) throws IOException {
        if (m == null) { error(ex, 404, "Image not available yet."); return; }
        byte[] bytes = ImageCipher.toPng(m);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        send(ex, 200, "image/png", bytes);
    }

    private void run(HttpExchange ex, Map<String, String> q) throws IOException {
        if (st.busy()) { error(ex, 409, "Something is already running. Please wait."); return; }
        String op = q.getOrDefault("op", "encrypt");
        String method = q.getOrDefault("method", "row");
        long seed = longParam(q, "seed", 2026);
        if (!op.equals("encrypt") && !op.equals("decrypt") && !op.equals("race") && !op.equals("filter")) { error(ex, 400, "Unknown op."); return; }
        if (!op.equals("race") && !MatrixOps.isMethod(method)) { error(ex, 400, "Unknown method."); return; }
        double[][] filter = null;
        if (op.equals("filter")) {
            String[] parts = q.getOrDefault("matrix", "").split(",");
            if (parts.length != 12) { error(ex, 400, "Filter matrix needs 12 numbers (3 x 3 + offset)."); return; }
            filter = new double[3][4];
            try {
                for (int i = 0; i < 12; i++) filter[i / 4][i % 4] = Double.parseDouble(parts[i].trim());
            } catch (NumberFormatException e) { error(ex, 400, "Filter matrix has an invalid number."); return; }
        }
        final boolean withCell = !"0".equals(q.get("cell"));

        int[][][] input;
        int n;
        synchronized (st) {
            n = st.n;
            input = op.equals("decrypt") ? st.encrypted : st.original;
        }
        if (input == null) { error(ex, 400, "Encrypt an image first."); return; }

        AppState.Job job = new AppState.Job(op, method, n, seed);
        job.filter = filter;
        st.job = job;
        final int[][][] in = input;
        Thread worker = new Thread(() -> runJob(job, in, withCell), "job-worker");
        worker.setDaemon(true);
        worker.start();
        json(ex, "{\"ok\":true}");
    }

    private void runJob(AppState.Job job, int[][][] input, boolean withCell) {
        try {
            if (job.op.equals("filter")) {
                job.phase = "running";
                Progress p = new Progress(job.method, job.n);
                job.progress = p;
                int[][][] out = FilterOps.apply(job.method, job.filter, input, p);
                synchronized (st) {
                    st.filtered = out;
                    st.filterMatrix = job.filter;
                }
                job.phase = "done";
                return;
            }
            job.phase = st.hasKey(job.n, job.seed) ? "key-cached" : "key";
            KeyGenerator.KeyPair key = st.key(job.n, job.seed);
            job.phase = "running";
            switch (job.op) {
                case "encrypt" -> {
                    Progress p = new Progress(job.method, job.n);
                    job.progress = p;
                    int[][][] c = MatrixOps.multiply(job.method, key.k(), input, p);
                    synchronized (st) {
                        st.encrypted = c;
                        st.encSeed = job.seed;
                        st.encMethod = job.method;
                        st.decrypted = st.decryptedFrom = null;
                        st.decSeed = -1;
                    }
                }
                case "decrypt" -> {
                    Progress p = new Progress(job.method, job.n);
                    job.progress = p;
                    int[][][] d = MatrixOps.multiply(job.method, key.kinv(), input, p);
                    synchronized (st) {
                        st.decrypted = d;
                        st.decryptedFrom = input;
                        st.decSeed = job.seed;
                        st.decMethod = job.method;
                    }
                }
                case "race" -> {
                    job.phase = "warmup";
                    for (String m : MatrixOps.METHODS)          // quick JIT warm-up so the race is fair
                        if (!m.equals("cell")) MatrixOps.multiply(m, key.k(), input, new Progress(m, job.n));
                    job.phase = "running";
                    int[][][] c = null;
                    for (String m : MatrixOps.METHODS) {
                        if (m.equals("cell") && !withCell) continue;
                        Progress p = new Progress(m, job.n);
                        job.progress = p;
                        job.race.put(m, p);
                        c = MatrixOps.multiply(m, key.k(), input, p);
                    }
                    synchronized (st) {
                        st.encrypted = c;
                        st.encSeed = job.seed;
                        st.encMethod = "race";
                        st.decrypted = st.decryptedFrom = null;
                        st.decSeed = -1;
                    }
                }
                default -> throw new IllegalStateException();
            }
            job.phase = "done";
        } catch (Throwable t) {
            t.printStackTrace();
            job.error = t.getClass().getSimpleName() + ": " + t.getMessage();
            job.phase = "error";
        } finally {
            job.finished = true;
        }
    }

    private void job(HttpExchange ex) throws IOException {
        AppState.Job j = st.job;
        if (j == null) { json(ex, "{\"none\":true}"); return; }
        Progress p = j.progress;
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"op\":").append(str(j.op))
          .append(",\"method\":").append(str(p != null ? p.method : j.method))
          .append(",\"n\":").append(j.n)
          .append(",\"phase\":").append(str(j.phase))
          .append(",\"finished\":").append(j.finished)
          .append(",\"error\":").append(j.error == null ? "null" : str(j.error))
          .append(",\"rowsDone\":").append(p == null ? 0 : p.rowsDone.get())
          .append(",\"threads\":").append(p == null ? 0 : p.threadsCreated.get())
          .append(",\"elapsedMs\":").append(fmt(p == null ? 0 : p.elapsedMs()))
          .append(",\"raceIndex\":").append(j.race.size())
          .append('}');
        json(ex, sb.toString());
    }

    private void timeline(HttpExchange ex) throws IOException {
        AppState.Job j = st.job;
        if (j == null || j.progress == null || !j.finished) { error(ex, 404, "No finished run yet."); return; }
        json(ex, j.progress.timelineJson());
    }

    private void race(HttpExchange ex) throws IOException {
        AppState.Job j = st.job;
        if (j == null || !j.op.equals("race") || !j.finished) { error(ex, 404, "No finished race yet."); return; }
        StringBuilder sb = new StringBuilder("{\"lanes\":[");
        boolean first = true;
        synchronized (j.race) {
            for (Progress p : j.race.values()) {
                if (!first) sb.append(',');
                sb.append(p.timelineJson());
                first = false;
            }
        }
        sb.append("]}");
        json(ex, sb.toString());
    }

    private void verify(HttpExchange ex) throws IOException {
        synchronized (st) {
            if (st.decrypted == null) { error(ex, 404, "Nothing decrypted yet."); return; }
            int diff = ImageCipher.countDifferences(st.original, st.decrypted);
            json(ex, "{\"differences\":" + diff + ",\"total\":" + (st.n * st.n)
                    + ",\"encSeed\":" + st.encSeed + ",\"decSeed\":" + st.decSeed + "}");
        }
    }

    private void filters(HttpExchange ex) throws IOException {
        StringBuilder sb = new StringBuilder("{\"filters\":[");
        for (int f = 0; f < FilterOps.PRESETS.length; f++) {
            FilterOps.Filter p = FilterOps.PRESETS[f];
            if (f > 0) sb.append(',');
            sb.append("{\"id\":").append(str(p.id())).append(",\"name\":").append(str(p.name()))
              .append(",\"about\":").append(str(p.about())).append(",\"m\":[");
            for (int i = 0; i < 12; i++) {
                if (i > 0) sb.append(',');
                sb.append(p.m()[i / 4][i % 4]);
            }
            sb.append("]}");
        }
        sb.append("]}");
        json(ex, sb.toString());
    }

    /** Explains how one output pixel was computed: C[i][j] = sum_k A[i][k] * B[k][j] (mod 256). */
    private void inspect(HttpExchange ex, Map<String, String> q) throws Exception {
        String which = q.getOrDefault("img", "encrypted");
        int i = intParam(q, "i", 0), j = intParam(q, "j", 0);
        if (which.equals("filtered")) {
            int[][][] P, out;
            double[][] F;
            synchronized (st) { P = st.original; out = st.filtered; F = st.filterMatrix; }
            if (out == null) { error(ex, 404, "Image not ready."); return; }
            int n = out[0].length;
            if (i < 0 || j < 0 || i >= n || j >= n) { error(ex, 400, "Pixel out of range."); return; }
            StringBuilder sb = new StringBuilder("{\"img\":\"filtered\",\"i\":" + i + ",\"j\":" + j + ",\"in\":[");
            sb.append(P[0][i][j]).append(',').append(P[1][i][j]).append(',').append(P[2][i][j])
              .append("],\"out\":[").append(out[0][i][j]).append(',').append(out[1][i][j]).append(',').append(out[2][i][j])
              .append("],\"m\":[");
            for (int k = 0; k < 12; k++) { if (k > 0) sb.append(','); sb.append(F[k / 4][k % 4]); }
            sb.append("]}");
            json(ex, sb.toString());
            return;
        }
        int[][] A;
        int[][][] B, out;
        long seed;
        synchronized (st) {
            if (which.equals("decrypted")) {
                out = st.decrypted; B = st.decryptedFrom; seed = st.decSeed;
            } else {
                out = st.encrypted; B = st.original; seed = st.encSeed;
            }
        }
        if (out == null || B == null) { error(ex, 404, "Image not ready."); return; }
        int n = out[0].length;
        if (i < 0 || j < 0 || i >= n || j >= n) { error(ex, 400, "Pixel out of range."); return; }
        KeyGenerator.KeyPair key = st.key(n, seed);
        A = which.equals("decrypted") ? key.kinv() : key.k();

        StringBuilder sb = new StringBuilder("{");
        sb.append("\"img\":").append(str(which)).append(",\"i\":").append(i).append(",\"j\":").append(j)
          .append(",\"n\":").append(n).append(",\"seed\":").append(seed).append(",\"channels\":[");
        String[] names = {"R", "G", "B"};
        for (int c = 0; c < 3; c++) {
            long sum = 0;
            for (int k = 0; k < n; k++) sum += (long) A[i][k] * B[c][k][j];
            int value = (int) (sum % 256);
            if (c > 0) sb.append(',');
            sb.append("{\"name\":\"").append(names[c]).append("\",\"sum\":").append(sum)
              .append(",\"value\":").append(value).append(",\"stored\":").append(out[c][i][j])
              .append(",\"terms\":[");
            for (int k = 0; k < Math.min(4, n); k++) {
                if (k > 0) sb.append(',');
                sb.append("[").append(A[i][k]).append(',').append(B[c][k][j]).append(']');
            }
            sb.append("]}");
        }
        sb.append("]}");
        json(ex, sb.toString());
    }

    private void startBench(HttpExchange ex, Map<String, String> q) throws IOException {
        if (st.busy()) { error(ex, 409, "Something is already running. Please wait."); return; }
        List<Integer> sizes = new ArrayList<>();
        for (String s : q.getOrDefault("sizes", "64,128,256,512").split(",")) {
            if (s.isBlank()) continue;
            int v = Integer.parseInt(s.trim());
            if (v < 2 || v > 2048) { error(ex, 400, "Sizes must be between 2 and 2048."); return; }
            sizes.add(v);
        }
        List<String> methods = new ArrayList<>();
        for (String m : q.getOrDefault("methods", "single,row,pool,cell").split(",")) {
            if (MatrixOps.isMethod(m.trim())) methods.add(m.trim());
        }
        if (sizes.isEmpty() || methods.isEmpty()) { error(ex, 400, "Pick at least one size and one method."); return; }
        // Single-threaded first so the others are compared against it.
        methods.sort((a, b) -> Integer.compare(order(a), order(b)));
        int[] sz = sizes.stream().mapToInt(Integer::intValue).sorted().toArray();

        AppState.BenchJob b = new AppState.BenchJob();
        st.bench = b;
        Thread t = new Thread(() -> {
            try {
                Benchmark.run(sz, methods, new Benchmark.Listener() {
                    public void status(String s) { b.status = s; }
                    public void result(Benchmark.Result r) { b.results.add(r); }
                });
            } catch (Throwable e) {
                e.printStackTrace();
                b.error = e.getClass().getSimpleName() + ": " + e.getMessage();
            } finally {
                b.finished = true;
            }
        }, "bench-worker");
        t.setDaemon(true);
        t.start();
        json(ex, "{\"ok\":true}");
    }

    private static int order(String m) {
        for (int i = 0; i < MatrixOps.METHODS.length; i++) if (MatrixOps.METHODS[i].equals(m)) return i;
        return 99;
    }

    private void benchStatus(HttpExchange ex) throws IOException {
        AppState.BenchJob b = st.bench;
        if (b == null) { json(ex, "{\"none\":true}"); return; }
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"finished\":").append(b.finished)
          .append(",\"status\":").append(str(b.status))
          .append(",\"error\":").append(b.error == null ? "null" : str(b.error))
          .append(",\"cores\":").append(MatrixOps.cores())
          .append(",\"results\":[");
        boolean first = true;
        for (Benchmark.Result r : b.results) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"n\":").append(r.n()).append(",\"method\":").append(str(r.method()))
              .append(",\"ms\":").append(fmt(r.ms())).append(",\"threads\":").append(r.threads())
              .append(",\"correct\":").append(r.correct()).append(",\"skipped\":").append(r.skipped()).append('}');
        }
        sb.append("]}");
        json(ex, sb.toString());
    }

    // ================================================================= static files

    private void handleStatic(HttpExchange ex) throws IOException {
        try {
            String p = ex.getRequestURI().getPath();
            if (p.equals("/")) p = "/index.html";
            Path file = webRoot.resolve(p.substring(1)).normalize();
            if (!file.startsWith(webRoot) || !Files.isRegularFile(file)) {
                send(ex, 404, "text/plain; charset=utf-8", "Not found".getBytes(StandardCharsets.UTF_8));
                return;
            }
            ex.getResponseHeaders().set("Cache-Control", "no-cache");
            send(ex, 200, contentType(file.toString()), Files.readAllBytes(file));
        } finally {
            ex.close();
        }
    }

    private static String contentType(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".html")) return "text/html; charset=utf-8";
        if (n.endsWith(".js")) return "text/javascript; charset=utf-8";
        if (n.endsWith(".css")) return "text/css; charset=utf-8";
        if (n.endsWith(".svg")) return "image/svg+xml";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".ico")) return "image/x-icon";
        return "application/octet-stream";
    }

    // ================================================================= helpers

    private static Map<String, String> query(HttpExchange ex) {
        Map<String, String> m = new HashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null) return m;
        for (String part : raw.split("&")) {
            int eq = part.indexOf('=');
            String k = eq < 0 ? part : part.substring(0, eq);
            String v = eq < 0 ? "" : part.substring(eq + 1);
            m.put(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
        return m;
    }

    private static int intParam(Map<String, String> q, String k, int def) {
        try { return q.containsKey(k) ? Integer.parseInt(q.get(k).trim()) : def; }
        catch (NumberFormatException e) { return def; }
    }

    private static long longParam(Map<String, String> q, String k, long def) {
        try { return q.containsKey(k) ? Long.parseLong(q.get(k).trim()) : def; }
        catch (NumberFormatException e) { return def; }
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }

    private static String str(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }

    private static void json(HttpExchange ex, String body) throws IOException {
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        send(ex, 200, "application/json; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
    }

    private static void error(HttpExchange ex, int code, String msg) throws IOException {
        send(ex, code, "application/json; charset=utf-8",
                ("{\"error\":" + str(msg) + "}").getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange ex, int code, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(code, body.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(body); }
    }
}
