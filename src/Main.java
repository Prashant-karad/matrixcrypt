import java.nio.file.Files;
import java.nio.file.Path;

/**
 * MatrixCrypt — image encryption using (multithreaded) matrix multiplication.
 *
 *   ./run.sh            start the web app on http://localhost:8080
 *   ./run.sh bench      run the benchmark in the terminal and save bench-results.csv
 *   ./run.sh test       quick self-test (key inverse + encrypt/decrypt round trip)
 */
public class Main {

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        String mode = args.length > 0 ? args[0] : "serve";

        switch (mode) {
            case "bench" -> Benchmark.cli(args);
            case "test" -> selfTest();
            default -> {
                int port = 8080;
                String env = System.getenv("PORT");
                if (env != null && !env.isBlank()) port = Integer.parseInt(env.trim());
                Path web = Path.of("web");
                if (!Files.isDirectory(web)) {
                    System.err.println("Cannot find the 'web' folder. Run this from the project folder (use ./run.sh).");
                    System.exit(1);
                }
                WebServer.start(port, web);
            }
        }
    }

    /** Proves the maths works: K x K^-1 = I, and decrypt(encrypt(P)) = P for every method. */
    private static void selfTest() throws Exception {
        int n = 64;
        KeyGenerator.KeyPair key = KeyGenerator.generate(n, 2026);
        int[][] I = MatrixOps.multiplyFast(key.k(), key.kinv());
        boolean identity = true;
        for (int i = 0; i < n; i++)
            for (int j = 0; j < n; j++)
                if (I[i][j] != (i == j ? 1 : 0)) identity = false;
        System.out.println("K x K^-1 = I (mod 256) ............ " + (identity ? "PASS" : "FAIL"));

        int[][][] P = ImageCipher.sample(n);
        boolean all = identity;
        for (String m : MatrixOps.METHODS) {
            int[][][] C = MatrixOps.multiply(m, key.k(), P, new Progress(m, n));
            int[][][] D = MatrixOps.multiply(m, key.kinv(), C, new Progress(m, n));
            boolean ok = MatrixOps.equal(P, D) && !MatrixOps.equal(P, C);
            all &= ok;
            System.out.printf("decrypt(encrypt(P)) = P  [%-6s] ... %s%n", m, ok ? "PASS" : "FAIL");
        }
        KeyGenerator.KeyPair wrong = KeyGenerator.generate(n, 2027);
        int[][][] C = MatrixOps.multiply("pool", key.k(), P, new Progress("pool", n));
        int[][][] W = MatrixOps.multiply("pool", wrong.kinv(), C, new Progress("pool", n));
        int diff = ImageCipher.countDifferences(P, W);
        boolean wrongOk = diff > n * n * 0.9;
        all &= wrongOk;
        System.out.printf("wrong key gives garbage (%d%% wrong) .. %s%n", diff * 100 / (n * n), wrongOk ? "PASS" : "FAIL");
        System.out.println(all ? "\nAll tests passed." : "\nSOME TESTS FAILED.");
        if (!all) System.exit(1);
    }
}
