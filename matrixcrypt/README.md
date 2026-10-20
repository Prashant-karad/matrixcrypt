# MatrixCrypt — Multithreaded Matrix Multiplication

**DAA Mini Project:** *Implement matrix multiplication, multithreaded matrix multiplication (one thread per row and one thread per cell), and analyze and compare their performance.*

The project applies matrix multiplication to two real applications, inside one web app:

1. **Image Encryption** — an image is three matrices **P** (R, G, B). Encrypt with a random key matrix: **C = K × P (mod 256)**. Decrypt with the inverse key: **P = K⁻¹ × C (mod 256)**, which recovers the image pixel-perfectly.
2. **Photo Filter Studio** — every pixel `[R, G, B]` is multiplied by a 3 × 3 filter matrix (sepia, grayscale, invert, warm, cool, vintage, and **colour-blindness simulation**), plus a custom matrix editor.

Every operation can run with four strategies, and the app shows how each one actually behaved:

| Method | Threads created | Notes |
|---|---|---|
| Single-threaded | 1 | Baseline |
| Thread per row | n | Required by the syllabus |
| Thread per cell | n² | Required by the syllabus |
| Thread pool | number of CPU cores | Bonus: how real software does it |

## Features

- **Live replay** of each run: rows appear in the exact order and relative timing in which the server finished them
- **Thread map**: colours every row by the thread that computed it
- **Pixel inspector**: hover any output pixel to see the real multiplication, e.g. `Σ K[i][k]·P[k][j] = 8,412,331 → mod 256 = 171 ✓`
- **Thread race**: all methods run for real, then replay side by side on the same time scale
- **Benchmark**: time vs size chart (log/linear), speed-up table, correctness check, CSV export, and **observations written automatically from your own measurements**
- **Wrong key demo**: decrypting with seed + 1 leaves the image as noise
- No libraries, no frameworks: plain Java 21 + HTML/CSS/JS

---

## Run it in GitHub Codespaces (no installation)

1. Create a new repository on GitHub and upload all these files (keep the folder structure, including the `.devcontainer` folder).
2. On the repo page, click **Code → Codespaces → Create codespace on main**.
   - Tip: use **⋯ → New with options** and pick a **4-core** machine. More cores give a much clearer speed-up. (It uses your free monthly hours twice as fast as 2-core.)
3. Wait for it to open, then in the terminal run:
   ```bash
   ./run.sh
   ```
4. A browser tab opens automatically. If not, open the **PORTS** tab and click the globe icon next to port **8080**.

## Run it on your own computer

Install **JDK 21** (for example Eclipse Temurin from adoptium.net), then:

```bash
./run.sh          # macOS / Linux
run.bat           # Windows
```

Open <http://localhost:8080>.

## Other commands

```bash
./run.sh test     # self-test: K × K⁻¹ = I, and decrypt(encrypt(P)) = P for all 4 methods
./run.sh bench    # benchmark in the terminal, saves bench-results.csv for your report
./run.sh bench 128,256,512,1024
```

---

## How it works

### Encryption key
A matrix is invertible mod 256 only if its determinant is **odd**. The key is built as **K = L × U**:
- **L**: lower-triangular, 1s on the diagonal
- **U**: upper-triangular, random **odd** numbers on the diagonal

So det(K) = 1 × (product of odd numbers) = odd, which is always invertible. Triangular matrices are inverted by substitution, and **K⁻¹ = U⁻¹ × L⁻¹**. The seed decides the random numbers, so it works like a password.

### The multiplication (same for every method)
```java
long sum = 0;
for (int k = 0; k < n; k++) sum += (long) K[i][k] * P[k][j];
C[i][j] = (int) (sum % 256);
```
`P` is transposed once so column j is contiguous in memory (cache-friendly). All methods use this, so the comparison is fair.

### Filters
```
| R' |   | f00 f01 f02 |   | R |   | o0 |
| G' | = | f10 f11 f12 | × | G | + | o1 |   then clamp to 0..255
| B' |   | f20 f21 f22 |   | B |   | o2 |
```
Colour-blindness matrices are from Machado, Oliveira & Fernandes (2009), *A Physiologically-based Model for Simulation of Color Vision Deficiency*.

### Complexity
| | Encryption (n × n image) | Filter (n × n image) |
|---|---|---|
| Total work | 3·n³ multiply-adds → **O(n³)** | 9·n² multiply-adds → **O(n²)** |
| Best possible parallel time | ≈ n³ / p (p = cores) | ≈ n² / p |

Threads never reduce the total work; they divide it across cores, and every thread adds creation and scheduling overhead.

---

## What results to expect (and how to explain them)

Results depend on your CPU, so **always use your own numbers** (the Benchmark tab writes observations for you). Typical patterns:

- **Single-threaded time grows ~8× when n doubles** → confirms O(n³).
- **Thread per cell is by far the slowest.** It creates n² threads (262,144 at 512 × 512), and each does only n multiplications. Creating a thread costs tens of microseconds, much more than the work it does.
- **Thread per row** helps only when each row has enough work (large n) *and* there are several cores. On a 2-core machine it is often *slower* than single-threaded, because creating n threads costs more than it saves.
- **Thread pool** is usually the fastest: it creates only p threads and reuses them. Its speed-up approaches (but can't exceed) the number of cores.

Example on a 2-core cloud machine (C = A × B mod 256):

| n | Single | Per row | Pool | Per cell |
|---|---|---|---|---|
| 256 | 3.1 ms | 32 ms | 2.9 ms | 6.95 s |
| 512 | 24.8 ms | 96.8 ms | 17.1 ms | 28.6 s |

Pool is 1.45× faster at 512; per cell is ~1,150× slower than single. On a 4–8 core laptop the parallel methods gain much more.

## Limitation (mention it in the viva)

This is a Hill-cipher-style **linear** cipher. If an attacker has one original image and its encrypted version, they can solve for K. Identical columns in P also give identical columns in C. Real systems use AES. The project's goal is the algorithm and its performance analysis, not production security.

## Viva questions

- **Why is thread per cell slowest?** Thread creation and scheduling cost far more than the n multiplications per thread.
- **Time complexity?** O(n³) for all methods; parallelism divides time by at most the number of cores.
- **Why mod 256?** Keeps every value a valid pixel (0–255) and makes exact decryption possible.
- **Why must det(K) be odd?** Mod 256 = 2⁸, only odd numbers have multiplicative inverses.
- **Why transpose P?** Sequential memory access uses the CPU cache well.
- **Why warm up before timing?** Java's JIT compiler optimises code after it runs a few times.
- **Extension ideas:** Strassen's algorithm (O(n^2.81)), Java virtual threads, block (tiled) multiplication.

## Project structure

```
matrixcrypt/
├── src/
│   ├── Main.java          entry point (serve / bench / test)
│   ├── MatrixOps.java     the 4 multiplication strategies  ← core of the project
│   ├── FilterOps.java     filters as 3×3 matrix × pixel vector, same 4 strategies
│   ├── KeyGenerator.java  invertible key K = L×U and K⁻¹ (mod 256)
│   ├── ImageCipher.java   image ↔ matrices, resizing, sample picture
│   ├── Progress.java      records which thread finished which row, and when
│   ├── Benchmark.java     timing, median of runs, correctness check, CSV
│   ├── AppState.java      in-memory state for the server
│   └── WebServer.java     built-in Java HTTP server + JSON API
├── web/                   index.html, style.css, app.js (the UI)
├── .devcontainer/         GitHub Codespaces setup (Java 21, port 8080)
├── run.sh / run.bat
└── README.md
```
