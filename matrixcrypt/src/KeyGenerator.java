import java.util.SplittableRandom;

/**
 * Builds a random n x n key matrix K that is invertible modulo 256, and its inverse K^-1.
 *
 * Trick: K = L x U (mod 256)
 *   L = lower-triangular, 1s on the diagonal, random values below
 *   U = upper-triangular, random ODD values on the diagonal, random values above
 *
 * det(L) = 1 and det(U) = product of odd numbers = odd, so det(K) is odd.
 * An odd number always has an inverse mod 256, so K is guaranteed invertible.
 *
 * Inverting triangular matrices is easy (forward / back substitution), and
 *   K^-1 = U^-1 x L^-1 (mod 256)
 *
 * The same seed always produces the same key, so the seed acts as the "password".
 */
final class KeyGenerator {

    record KeyPair(int n, long seed, int[][] k, int[][] kinv) {}

    private KeyGenerator() {}

    static KeyPair generate(int n, long seed) throws InterruptedException {
        SplittableRandom rnd = new SplittableRandom(seed);
        int[][] L = new int[n][n];
        int[][] U = new int[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (j < i) L[i][j] = rnd.nextInt(256);
                else if (j == i) {
                    L[i][j] = 1;
                    U[i][j] = rnd.nextInt(128) * 2 + 1;   // random odd number 1..255
                } else U[i][j] = rnd.nextInt(256);
            }
        }
        int[][] K = MatrixOps.multiplyFast(L, U);
        int[][] Kinv = MatrixOps.multiplyFast(invertUpper(U), invertUnitLower(L));
        return new KeyPair(n, seed, K, Kinv);
    }

    /** Inverse of an odd number modulo 256 (Newton's method; each step doubles the correct bits). */
    static int inverseMod256(int a) {
        int x = a;                      // correct for the lowest 3 bits when a is odd
        for (int step = 0; step < 4; step++) x = x * (2 - a * x);
        return x & 0xFF;
    }

    /** Inverse of a lower-triangular matrix with 1s on the diagonal (mod 256). */
    static int[][] invertUnitLower(int[][] L) {
        int n = L.length;
        // XT[j][i] stores X[i][j] (transposed for cache-friendly access)
        int[][] XT = new int[n][n];
        for (int i = 0; i < n; i++) {
            XT[i][i] = 1;
            int[] li = L[i];
            for (int j = 0; j < i; j++) {
                int[] xj = XT[j];
                long s = 0;
                for (int k = j; k < i; k++) s += (long) li[k] * xj[k];
                XT[j][i] = (int) (-s & 0xFF);
            }
        }
        int[][] X = new int[n][n];
        for (int i = 0; i < n; i++)
            for (int j = 0; j < n; j++) X[i][j] = XT[j][i];
        return X;
    }

    /** Inverse of an upper-triangular matrix with odd diagonal (mod 256). */
    static int[][] invertUpper(int[][] U) {
        int n = U.length;
        int[][] UT = new int[n][n];
        for (int i = 0; i < n; i++)
            for (int j = 0; j < n; j++) UT[j][i] = U[i][j];
        int[] invDiag = new int[n];
        for (int i = 0; i < n; i++) invDiag[i] = inverseMod256(U[i][i]);

        int[][] Y = new int[n][n];
        for (int i = 0; i < n; i++) {
            int[] yi = Y[i];
            yi[i] = invDiag[i];
            for (int j = i + 1; j < n; j++) {
                int[] uj = UT[j];               // column j of U
                long s = 0;
                for (int k = i; k < j; k++) s += (long) yi[k] * uj[k];
                yi[j] = (int) (((-s) & 0xFF) * invDiag[j] & 0xFF);
            }
        }
        return Y;
    }
}
