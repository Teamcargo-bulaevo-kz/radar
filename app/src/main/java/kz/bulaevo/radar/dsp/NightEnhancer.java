package kz.bulaevo.radar.dsp;

/**
 * Ночное "видение" из слабого кадра: накопление кадров во времени (снижает шум),
 * растяжка яркости по процентилям, гамма-подъём теней, контуры, подсветка движения, цветовая палитра.
 * Работает по яркостной (Y) плоскости, без зависимостей от Android.
 */
public final class NightEnhancer {
    public static final int PAL_GRAY = 0;
    public static final int PAL_GREEN = 1;
    public static final int PAL_THERMAL = 2;

    private static final int BLOCK = 8;

    public final int w;
    public final int h;

    /** Доля нового кадра при накоплении: 1 — без накопления, 0.08 — сильное накопление. */
    public volatile float alpha = 0.25f;
    public volatile float gamma = 0.55f;
    public volatile boolean edges = false;
    public volatile boolean motionMask = false;
    public volatile int palette = PAL_GREEN;

    /** Доля блоков кадра, где замечено движение (0..1). */
    public float motionFraction;
    /** Средняя яркость исходного кадра 0..255. */
    public float meanLuma;

    private final float[] acc;
    private final byte[] vals;
    private final int bw;
    private final int bh;
    private final float[] blockBg;
    private final float[] blockCur;
    private final boolean[] blockMoving;
    private final int[] hist = new int[256];
    private final int[][] lut = new int[3][256];
    private final float[] gammaLut = new float[1024];
    private float lastGamma = -1f;
    private boolean first = true;
    private float lo = 0f;
    private float hi = 255f;
    private boolean levelsInit;
    private boolean bgInit;

    public NightEnhancer(int w, int h) {
        this.w = w;
        this.h = h;
        acc = new float[w * h];
        vals = new byte[w * h];
        bw = w / BLOCK;
        bh = h / BLOCK;
        blockBg = new float[bw * bh];
        blockCur = new float[bw * bh];
        blockMoving = new boolean[bw * bh];
        buildPalettes();
    }

    private void buildPalettes() {
        for (int i = 0; i < 256; i++) {
            float v = i / 255f;
            lut[PAL_GRAY][i] = argb(i, i, i);
            lut[PAL_GREEN][i] = argb(Math.round(v * 70f), i, Math.round(v * 90f));
            // тепловая: чёрный → синий → пурпур → красный → жёлтый → белый
            float r;
            float g;
            float b;
            if (v < 0.25f) {
                float t = v / 0.25f;
                r = 0;
                g = 0;
                b = t * 0.8f;
            } else if (v < 0.5f) {
                float t = (v - 0.25f) / 0.25f;
                r = t * 0.8f;
                g = 0;
                b = 0.8f - t * 0.3f;
            } else if (v < 0.75f) {
                float t = (v - 0.5f) / 0.25f;
                r = 0.8f + t * 0.2f;
                g = t * 0.6f;
                b = 0.5f - t * 0.5f;
            } else {
                float t = (v - 0.75f) / 0.25f;
                r = 1f;
                g = 0.6f + t * 0.4f;
                b = t;
            }
            lut[PAL_THERMAL][i] = argb(Math.round(r * 255), Math.round(g * 255), Math.round(b * 255));
        }
    }

    private static int argb(int r, int g, int b) {
        return 0xFF000000 | (Math.min(255, r) << 16) | (Math.min(255, g) << 8) | Math.min(255, b);
    }

    private void ensureGammaLut() {
        float gm = gamma;
        if (gm != lastGamma) {
            for (int i = 0; i < gammaLut.length; i++) {
                gammaLut[i] = (float) Math.pow(i / (double) (gammaLut.length - 1), gm);
            }
            lastGamma = gm;
        }
    }

    /**
     * Обработать кадр.
     *
     * @param y         яркость, w*h байт подряд (без отступов строк)
     * @param outPixels w*h пикселей ARGB
     */
    public void process(byte[] y, int[] outPixels) {
        final int n = w * h;
        ensureGammaLut();
        final float a = first ? 1f : Math.max(0.02f, Math.min(1f, alpha));
        first = false;

        // накопление + средняя яркость
        double sum = 0;
        java.util.Arrays.fill(hist, 0);
        for (int i = 0; i < n; i++) {
            int v = y[i] & 0xFF;
            sum += v;
            float c = acc[i] + a * (v - acc[i]);
            acc[i] = c;
            hist[(int) c]++;
        }
        meanLuma = (float) (sum / n);

        // процентили для растяжки
        final int lowCount = (int) (n * 0.005);
        final int highCount = (int) (n * 0.997);
        int cum = 0;
        float newLo = 0;
        float newHi = 255;
        boolean gotLo = false;
        for (int i = 0; i < 256; i++) {
            cum += hist[i];
            if (!gotLo && cum >= lowCount) {
                newLo = i;
                gotLo = true;
            }
            if (cum >= highCount) {
                newHi = i + 1;
                break;
            }
        }
        if (newHi - newLo < 24f) {
            // почти пустой кадр: не раздуваем шум в бесконечность
            float mid = (newHi + newLo) / 2f;
            newLo = mid - 12f;
            newHi = mid + 12f;
        }
        if (!levelsInit) {
            lo = newLo;
            hi = newHi;
            levelsInit = true;
        } else {
            lo += 0.12f * (newLo - lo);
            hi += 0.12f * (newHi - hi);
        }
        final float span = Math.max(8f, hi - lo);
        final float inv = 1f / span;
        final int gl = gammaLut.length - 1;
        for (int i = 0; i < n; i++) {
            float t = (acc[i] - lo) * inv;
            if (t < 0f) {
                t = 0f;
            } else if (t > 1f) {
                t = 1f;
            }
            float g = gammaLut[(int) (t * gl)];
            vals[i] = (byte) Math.round(g * 255f);
        }

        // контуры (Собель) поверх изображения
        final boolean doEdges = edges;
        final int[] pal = lut[Math.max(0, Math.min(2, palette))];
        if (doEdges) {
            for (int yy = 0; yy < h; yy++) {
                for (int xx = 0; xx < w; xx++) {
                    int i = yy * w + xx;
                    int base = vals[i] & 0xFF;
                    if (xx > 0 && yy > 0 && xx < w - 1 && yy < h - 1) {
                        int p00 = vals[i - w - 1] & 0xFF;
                        int p01 = vals[i - w] & 0xFF;
                        int p02 = vals[i - w + 1] & 0xFF;
                        int p10 = vals[i - 1] & 0xFF;
                        int p12 = vals[i + 1] & 0xFF;
                        int p20 = vals[i + w - 1] & 0xFF;
                        int p21 = vals[i + w] & 0xFF;
                        int p22 = vals[i + w + 1] & 0xFF;
                        int gx = -p00 - 2 * p10 - p20 + p02 + 2 * p12 + p22;
                        int gy = -p00 - 2 * p01 - p02 + p20 + 2 * p21 + p22;
                        int e = (int) Math.min(255, (Math.abs(gx) + Math.abs(gy)) * 0.5);
                        base = Math.min(255, base / 2 + e);
                    }
                    outPixels[i] = pal[base];
                }
            }
        } else {
            for (int i = 0; i < n; i++) {
                outPixels[i] = pal[vals[i] & 0xFF];
            }
        }

        // движение по блокам: сравниваем среднюю яркость блока с медленным фоном
        for (int by = 0; by < bh; by++) {
            for (int bx = 0; bx < bw; bx++) {
                float s = 0;
                int y0 = by * BLOCK;
                int x0 = bx * BLOCK;
                for (int yy = 0; yy < BLOCK; yy++) {
                    int row = (y0 + yy) * w + x0;
                    for (int xx = 0; xx < BLOCK; xx++) {
                        s += y[row + xx] & 0xFF;
                    }
                }
                blockCur[by * bw + bx] = s / (BLOCK * BLOCK);
            }
        }
        int movingBlocks = 0;
        if (!bgInit) {
            System.arraycopy(blockCur, 0, blockBg, 0, blockCur.length);
            bgInit = true;
            java.util.Arrays.fill(blockMoving, false);
        } else {
            final float thr = Math.max(4f, 0.15f * meanLuma);
            for (int i = 0; i < blockCur.length; i++) {
                float d = blockCur[i] - blockBg[i];
                boolean mv = Math.abs(d) > thr;
                blockMoving[i] = mv;
                if (mv) {
                    movingBlocks++;
                }
                blockBg[i] += 0.06f * d;
            }
        }
        motionFraction = movingBlocks / (float) blockCur.length;

        if (motionMask && movingBlocks > 0) {
            for (int by = 0; by < bh; by++) {
                for (int bx = 0; bx < bw; bx++) {
                    if (!blockMoving[by * bw + bx]) {
                        continue;
                    }
                    int y0 = by * BLOCK;
                    int x0 = bx * BLOCK;
                    for (int yy = 0; yy < BLOCK; yy++) {
                        int row = (y0 + yy) * w + x0;
                        for (int xx = 0; xx < BLOCK; xx++) {
                            int c = outPixels[row + xx];
                            int r = Math.min(255, ((c >> 16) & 0xFF) + 110);
                            int g = ((c >> 8) & 0xFF) / 2;
                            int b = (c & 0xFF) / 2;
                            outPixels[row + xx] = argb(r, g, b);
                        }
                    }
                }
            }
        }
    }

    public void reset() {
        first = true;
        levelsInit = false;
        bgInit = false;
    }
}
