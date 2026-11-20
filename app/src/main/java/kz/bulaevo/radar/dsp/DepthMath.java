package kz.bulaevo.radar.dsp;

import java.util.Arrays;

/** Обработка вокруг нейросети глубины: поворот кадра, масштаб, раскраска, перевод в метры по сонару. */
public final class DepthMath {
    private DepthMath() {
    }

    /** Ширина кадра после поворота в вертикальное положение. */
    public static int uprightW(int w, int h, int rot) {
        return rot == 90 || rot == 270 ? h : w;
    }

    public static int uprightH(int w, int h, int rot) {
        return rot == 90 || rot == 270 ? w : h;
    }

    /**
     * Взять из кадра яркости (w×h, как его отдаёт матрица) вертикальный кадр ow×oh: поворот на rot по часовой
     * и растяжение. Значение — среднее по небольшому окну (меньше шума, чем у одной точки).
     */
    public static void sampleUpright(byte[] y, int w, int h, int rot, byte[] dst, int ow, int oh, int channels) {
        int uw = uprightW(w, h, rot);
        int uh = uprightH(w, h, rot);
        float sx = uw / (float) ow;
        float sy = uh / (float) oh;
        int r = Math.max(0, Math.min(2, (int) (Math.min(sx, sy) / 2)));
        int o = 0;
        for (int vy = 0; vy < oh; vy++) {
            int uy = Math.min(uh - 1, (int) ((vy + 0.5f) * sy));
            for (int vx = 0; vx < ow; vx++) {
                int ux = Math.min(uw - 1, (int) ((vx + 0.5f) * sx));
                int sum = 0;
                int cnt = 0;
                for (int dy = -r; dy <= r; dy++) {
                    for (int dx = -r; dx <= r; dx++) {
                        int px = clamp(ux + dx, uw);
                        int py = clamp(uy + dy, uh);
                        int x;
                        int yy;
                        switch (rot) {
                            case 90:
                                x = py;
                                yy = h - 1 - px;
                                break;
                            case 180:
                                x = w - 1 - px;
                                yy = h - 1 - py;
                                break;
                            case 270:
                                x = w - 1 - py;
                                yy = px;
                                break;
                            default:
                                x = px;
                                yy = py;
                                break;
                        }
                        sum += y[yy * w + x] & 0xFF;
                        cnt++;
                    }
                }
                byte v = (byte) (sum / cnt);
                for (int c = 0; c < channels; c++) {
                    dst[o++] = v;
                }
            }
        }
    }

    private static int clamp(int v, int n) {
        return v < 0 ? 0 : (v >= n ? n - 1 : v);
    }

    /** Значение по перцентилю (0..1) — без учёта NaN и бесконечностей. */
    public static float percentile(float[] a, int n, float q, float[] scratch) {
        int m = 0;
        for (int i = 0; i < n; i++) {
            float v = a[i];
            if (!Float.isNaN(v) && !Float.isInfinite(v)) {
                scratch[m++] = v;
            }
        }
        if (m == 0) {
            return Float.NaN;
        }
        Arrays.sort(scratch, 0, m);
        int k = Math.max(0, Math.min(m - 1, Math.round(q * (m - 1))));
        return scratch[k];
    }

    /** Среднее в квадратном окне вокруг точки (cx, cy) в долях 0..1. */
    public static float windowMean(float[] a, int w, int h, float cx, float cy, int half) {
        int x0 = Math.round(cx * (w - 1));
        int y0 = Math.round(cy * (h - 1));
        double s = 0;
        int n = 0;
        for (int y = y0 - half; y <= y0 + half; y++) {
            for (int x = x0 - half; x <= x0 + half; x++) {
                if (x >= 0 && y >= 0 && x < w && y < h) {
                    s += a[y * w + x];
                    n++;
                }
            }
        }
        return n == 0 ? Float.NaN : (float) (s / n);
    }

    /**
     * Расстояние до точки по сонару. Нейросеть даёт обратную глубину d с точностью до масштаба: z ≈ k / d.
     * Сонар слышит ближайший предмет — ему соответствует почти максимальная обратная глубина dNear.
     * Отсюда k = zSonar · dNear, и для точки с обратной глубиной d: z = zSonar · dNear / d.
     */
    public static float metersFromSonar(float sonarNearest, float dNear, float d) {
        if (!(sonarNearest > 0) || !(dNear > 0) || !(d > 0)) {
            return Float.NaN;
        }
        return sonarNearest * dNear / d;
    }

    /** Цвет для «близости» t (0 — далеко, 1 — близко): тёмно-синий → голубой → зелёный → жёлтый → красный. */
    public static int color(float t) {
        if (Float.isNaN(t)) {
            return 0xFF000000;
        }
        t = Math.max(0f, Math.min(1f, t));
        float r;
        float g;
        float b;
        if (t < 0.25f) {
            float k = t / 0.25f;
            r = 0f;
            g = 0.1f + 0.6f * k;
            b = 0.35f + 0.65f * k;
        } else if (t < 0.5f) {
            float k = (t - 0.25f) / 0.25f;
            r = 0f;
            g = 0.7f + 0.3f * k;
            b = 1f - 0.8f * k;
        } else if (t < 0.75f) {
            float k = (t - 0.5f) / 0.25f;
            r = k;
            g = 1f;
            b = 0.2f - 0.2f * k;
        } else {
            float k = (t - 0.75f) / 0.25f;
            r = 1f;
            g = 1f - 0.85f * k;
            b = 0f;
        }
        return 0xFF000000 | ((int) (r * 255) << 16) | ((int) (g * 255) << 8) | (int) (b * 255);
    }
}
