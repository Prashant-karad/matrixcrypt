import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.SplittableRandom;

/**
 * Converts between images and matrices.
 *
 * An n x n RGB image becomes three n x n matrices (Red, Green, Blue), values 0..255.
 * Encryption:  C = K x P (mod 256)  for each channel
 * Decryption:  P = K^-1 x C (mod 256)
 *
 * Everything here is pure Java (no fonts or graphics drivers needed),
 * so it also works on headless servers like GitHub Codespaces.
 */
final class ImageCipher {

    private ImageCipher() {}

    /** Centre-crops the image to a square and resizes it to n x n (box-filter averaging). */
    static int[][][] fromImage(BufferedImage img, int n) {
        int w = img.getWidth(), h = img.getHeight();
        int s = Math.min(w, h);
        int x0 = (w - s) / 2, y0 = (h - s) / 2;
        int[] px = img.getRGB(x0, y0, s, s, null, 0, s);

        int[][][] m = new int[3][n][n];
        double scale = (double) s / n;
        for (int y = 0; y < n; y++) {
            int ya = (int) (y * scale);
            int yb = Math.min(s, Math.max(ya + 1, (int) Math.ceil((y + 1) * scale)));
            for (int x = 0; x < n; x++) {
                int xa = (int) (x * scale);
                int xb = Math.min(s, Math.max(xa + 1, (int) Math.ceil((x + 1) * scale)));
                long r = 0, g = 0, b = 0;
                int count = 0;
                for (int sy = ya; sy < yb; sy++) {
                    int base = sy * s;
                    for (int sx = xa; sx < xb; sx++) {
                        int rgb = px[base + sx];
                        r += (rgb >> 16) & 0xFF;
                        g += (rgb >> 8) & 0xFF;
                        b += rgb & 0xFF;
                        count++;
                    }
                }
                m[0][y][x] = (int) (r / count);
                m[1][y][x] = (int) (g / count);
                m[2][y][x] = (int) (b / count);
            }
        }
        return m;
    }

    static BufferedImage toImage(int[][][] m) {
        int n = m[0].length;
        int[] px = new int[n * n];
        for (int y = 0; y < n; y++)
            for (int x = 0; x < n; x++)
                px[y * n + x] = (m[0][y][x] << 16) | (m[1][y][x] << 8) | m[2][y][x];
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, n, n, px, 0, n);
        return img;
    }

    static byte[] toPng(int[][][] m) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(toImage(m), "png", out);
        return out.toByteArray();
    }

    /** Number of pixels that differ in any channel. */
    static int countDifferences(int[][][] a, int[][][] b) {
        int n = a[0].length, diff = 0;
        for (int y = 0; y < n; y++)
            for (int x = 0; x < n; x++)
                if (a[0][y][x] != b[0][y][x] || a[1][y][x] != b[1][y][x] || a[2][y][x] != b[2][y][x]) diff++;
        return diff;
    }

    // ------------------------------------------------------------ built-in sample picture

    private static final String[] GLYPH_D = {"11110", "10001", "10001", "10001", "10001", "10001", "11110"};
    private static final String[] GLYPH_A = {"01110", "10001", "10001", "11111", "10001", "10001", "10001"};

    /** A procedurally drawn sunset landscape with "DAA" written on it, drawn at any size. */
    static int[][][] sample(int n) {
        int[][][] m = new int[3][n][n];
        SplittableRandom rnd = new SplittableRandom(7);
        double sunX = 0.70, sunY = 0.40, sunR = 0.10;

        for (int y = 0; y < n; y++) {
            double v = (y + 0.5) / n;
            for (int x = 0; x < n; x++) {
                double u = (x + 0.5) / n;
                double[] c;
                double back = 0.50 + 0.06 * Math.sin(u * 9.0) + 0.03 * Math.sin(u * 23.0 + 1.0);
                double front = 0.63 + 0.05 * Math.sin(u * 6.0 + 2.0) + 0.02 * Math.sin(u * 31.0);
                if (v > 0.80) {                                        // lake reflecting the sky
                    double mv = 1.60 - v;
                    c = sky(u, mv, sunX, sunY, sunR);
                    double ripple = 0.80 + 0.12 * Math.sin(v * 220.0 + Math.sin(u * 30.0) * 2.0);
                    c = new double[]{c[0] * ripple * 0.75, c[1] * ripple * 0.80, c[2] * ripple * 0.95};
                } else if (v > front) {                                // near hills
                    double shade = 0.85 + 0.15 * Math.sin(u * 40.0 + v * 15.0);
                    c = new double[]{38 * shade, 42 * shade, 74 * shade};
                } else if (v > back) {                                 // far mountains
                    double t = (v - back) / 0.2;
                    c = new double[]{96 - 30 * t, 64 - 10 * t, 128 - 20 * t};
                } else {
                    c = sky(u, v, sunX, sunY, sunR);
                }
                for (int ch = 0; ch < 3; ch++) {
                    int val = (int) Math.round(c[ch] + rnd.nextInt(9) - 4); // slight film grain
                    m[ch][y][x] = Math.max(0, Math.min(255, val));
                }
            }
        }

        // "DAA" in a chunky pixel font, top-left
        int block = Math.max(1, n / 40);
        int ox = n / 14, oy = n / 12;
        String[][] text = {GLYPH_D, GLYPH_A, GLYPH_A};
        for (int g = 0; g < text.length; g++) {
            for (int gy = 0; gy < 7; gy++) {
                for (int gx = 0; gx < 5; gx++) {
                    if (text[g][gy].charAt(gx) != '1') continue;
                    int px = ox + (g * 6 + gx) * block, py = oy + gy * block;
                    for (int yy = py; yy < Math.min(n, py + block); yy++)
                        for (int xx = px; xx < Math.min(n, px + block); xx++) {
                            m[0][yy][xx] = 255; m[1][yy][xx] = 246; m[2][yy][xx] = 228;
                        }
                }
            }
        }
        return m;
    }

    private static double[] sky(double u, double v, double sx, double sy, double sr) {
        double t = Math.min(1, Math.max(0, v / 0.62));
        double r = 24 + (255 - 24) * t;
        double g = 32 + (138 - 32) * t * t;
        double b = 92 + (70 - 92) * t;
        double d = Math.hypot(u - sx, v - sy);
        if (d < sr) return new double[]{255, 226, 140};
        double glow = Math.max(0, 1 - (d - sr) / 0.25);
        glow *= glow;
        return new double[]{
                Math.min(255, r + 60 * glow),
                Math.min(255, g + 70 * glow),
                Math.min(255, b + 20 * glow)};
    }
}
