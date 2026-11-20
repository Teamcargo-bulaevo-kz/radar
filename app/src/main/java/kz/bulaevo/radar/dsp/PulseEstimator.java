package kz.bulaevo.radar.dsp;

/**
 * Частота пульса по неравномерно пришедшим отсчётам (кадры камеры или огибающая звука сердца).
 * Отсчёты пересчитываются на равную сетку FS Гц, убирается медленный дрейф, частота ищется по
 * автокорреляции в диапазоне 40–200 ударов в минуту.
 */
public final class PulseEstimator {
    public static final double FS = 25.0;
    private static final int CAP = 4096;

    private final double windowSec;
    private final long[] t = new long[CAP];
    private final double[] v = new double[CAP];
    private int n;
    private int head;

    /** Последний результат. */
    public double bpm = Double.NaN;
    /** Уверенность 0..1 (высота пика автокорреляции). */
    public double quality;
    /** Отфильтрованный сигнал на равной сетке (для графика) и его длина. */
    public final float[] wave = new float[(int) (FS * 12)];
    public int waveN;

    public PulseEstimator(double windowSec) {
        this.windowSec = windowSec;
    }

    public void reset() {
        n = 0;
        head = 0;
        bpm = Double.NaN;
        quality = 0;
        waveN = 0;
    }

    /** Добавить отсчёт: время в миллисекундах, значение. */
    public void add(long timeMs, double value) {
        if (n > 0 && timeMs <= t[(head - 1 + CAP) % CAP]) {
            return;
        }
        t[head] = timeMs;
        v[head] = value;
        head = (head + 1) % CAP;
        if (n < CAP) {
            n++;
        }
    }

    /** Сколько секунд данных накоплено. */
    public double seconds() {
        if (n < 2) {
            return 0;
        }
        return (t[(head - 1 + CAP) % CAP] - t[(head - n + CAP) % CAP]) / 1000.0;
    }

    /** Пересчитать пульс по последним windowSec секундам. */
    public void update() {
        if (n < 10) {
            return;
        }
        long tEnd = t[(head - 1 + CAP) % CAP];
        long tStart = Math.max(t[(head - n + CAP) % CAP], tEnd - (long) (windowSec * 1000));
        int m = (int) ((tEnd - tStart) / 1000.0 * FS);
        if (m < FS * 4) {
            return;
        }
        double[] u = new double[m];
        // линейная интерполяция на равную сетку
        int idx = (head - n + CAP) % CAP; // самый старый отсчёт
        while ((idx + 1) % CAP != head && t[(idx + 1) % CAP] <= tStart) {
            idx = (idx + 1) % CAP;
        }
        for (int i = 0; i < m; i++) {
            double ti = tStart + i * 1000.0 / FS;
            int nx = (idx + 1) % CAP;
            while (nx != head && t[nx] < ti) {
                idx = nx;
                nx = (idx + 1) % CAP;
            }
            if (nx == head) {
                u[i] = v[idx];
            } else {
                double a = (ti - t[idx]) / Math.max(1.0, (double) (t[nx] - t[idx]));
                a = Math.max(0, Math.min(1, a));
                u[i] = v[idx] + a * (v[nx] - v[idx]);
            }
        }
        // убрать дрейф: вычесть скользящее среднее за ~1.5 с, затем сгладить шум (~0.2 с)
        double[] d = detrend(u, (int) (FS * 1.5));
        double[] s = smooth(d, 2);
        double e = 0;
        for (double x : s) {
            e += x * x;
        }
        int keep = Math.min(wave.length, s.length);
        double sd = Math.sqrt(e / s.length) + 1e-12;
        for (int i = 0; i < keep; i++) {
            wave[i] = (float) (s[s.length - keep + i] / sd);
        }
        waveN = keep;
        if (e <= 0) {
            return;
        }
        // автокорреляция
        int lagMin = (int) Math.floor(FS * 60.0 / 200.0);
        int lagMax = Math.min(s.length / 2, (int) Math.ceil(FS * 60.0 / 40.0));
        double[] r = new double[lagMax + 2];
        for (int lag = lagMin - 1; lag <= lagMax + 1 && lag < s.length; lag++) {
            if (lag < 1) {
                continue;
            }
            double acc = 0;
            double ea = 0;
            double eb = 0;
            for (int i = 0; i + lag < s.length; i++) {
                acc += s[i] * s[i + lag];
                ea += s[i] * s[i];
                eb += s[i + lag] * s[i + lag];
            }
            r[lag] = acc / Math.sqrt(ea * eb + 1e-18);
        }
        int best = -1;
        double bestR = 0;
        for (int lag = lagMin; lag <= lagMax; lag++) {
            if (r[lag] > r[lag - 1] && r[lag] >= r[lag + 1] && r[lag] > bestR) {
                bestR = r[lag];
                best = lag;
            }
        }
        if (best < 0) {
            quality = 0;
            return;
        }
        // предпочесть самый короткий период с почти таким же пиком (иначе можно поймать удвоенный период)
        for (int lag = lagMin; lag < best; lag++) {
            if (r[lag] > r[lag - 1] && r[lag] >= r[lag + 1] && r[lag] >= 0.85 * bestR
                    && Math.abs(best / (double) lag - Math.round(best / (double) lag)) < 0.15) {
                best = lag;
                bestR = r[lag];
                break;
            }
        }
        double y0 = r[best - 1];
        double y1 = r[best];
        double y2 = r[best + 1];
        double den = y0 - 2 * y1 + y2;
        double off = den != 0 ? 0.5 * (y0 - y2) / den : 0;
        double lag = best + Math.max(-0.5, Math.min(0.5, off));
        bpm = 60.0 * FS / lag;
        quality = Math.max(0, Math.min(1, bestR));
    }

    private static double[] detrend(double[] u, int win) {
        int m = u.length;
        double[] pre = new double[m + 1];
        for (int i = 0; i < m; i++) {
            pre[i + 1] = pre[i] + u[i];
        }
        double[] out = new double[m];
        int h = win / 2;
        for (int i = 0; i < m; i++) {
            int a = Math.max(0, i - h);
            int b = Math.min(m, i + h + 1);
            out[i] = u[i] - (pre[b] - pre[a]) / (b - a);
        }
        return out;
    }

    private static double[] smooth(double[] u, int h) {
        int m = u.length;
        double[] out = new double[m];
        for (int i = 0; i < m; i++) {
            double s = 0;
            int c = 0;
            for (int j = Math.max(0, i - h); j <= Math.min(m - 1, i + h); j++) {
                s += u[j];
                c++;
            }
            out[i] = s / c;
        }
        return out;
    }
}
