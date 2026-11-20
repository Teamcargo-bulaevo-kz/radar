package kz.bulaevo.radar.dsp;

/**
 * Анализатор вибраций по акселерометру: спектр колебаний, главная частота, сила (среднеквадратичное ускорение)
 * и оценка скорости вибрации на главной частоте (мм/с — так нормируют вибрацию машин).
 */
public final class VibrationAnalyzer {
    public static final int N = 512;
    private static final int CAP = 4096;

    private final long[] tNs = new long[CAP];
    private final float[] v = new float[CAP];
    private int n;
    private int head;
    private final FFT fft = new FFT(N);
    private final double[] re = new double[N];
    private final double[] im = new double[N];
    private final double[] hann = new double[N];

    /** Амплитуда по частотам, м/с². */
    public final float[] spec = new float[N / 2];
    public double fs;
    public double peakHz = Double.NaN;
    public double peakAmp;
    public double rms;
    public double velocityMmS;

    public VibrationAnalyzer() {
        for (int i = 0; i < N; i++) {
            hann[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (N - 1));
        }
    }

    public synchronized void reset() {
        n = 0;
        head = 0;
        peakHz = Double.NaN;
    }

    /** Отсчёт ускорения: время датчика (нс) и модуль ускорения (м/с²). */
    public synchronized void add(long timeNs, float a) {
        tNs[head] = timeNs;
        v[head] = a;
        head = (head + 1) % CAP;
        if (n < CAP) {
            n++;
        }
    }

    public synchronized boolean update() {
        if (n < 64) {
            return false;
        }
        int last = (head - 1 + CAP) % CAP;
        int first = (head - Math.min(n, N) + CAP) % CAP;
        int cnt = Math.min(n, N);
        double span = (tNs[last] - tNs[first]) * 1e-9;
        if (span <= 0) {
            return false;
        }
        fs = (cnt - 1) / span;
        // равная сетка N точек, последние N/fs секунд
        double dt = 1.0 / fs;
        double tEnd = tNs[last] * 1e-9;
        int idx = first;
        double mean = 0;
        for (int i = 0; i < N; i++) {
            double ti = tEnd - (N - 1 - i) * dt;
            int nx = (idx + 1) % CAP;
            while (nx != head && tNs[nx] * 1e-9 < ti) {
                idx = nx;
                nx = (idx + 1) % CAP;
            }
            double val;
            if (nx == head) {
                val = v[idx];
            } else {
                double a = (ti - tNs[idx] * 1e-9) / Math.max(1e-9, (tNs[nx] - tNs[idx]) * 1e-9);
                a = Math.max(0, Math.min(1, a));
                val = v[idx] + a * (v[nx] - v[idx]);
            }
            re[i] = val;
            mean += val;
        }
        mean /= N;
        double ss = 0;
        for (int i = 0; i < N; i++) {
            double x = re[i] - mean; // убираем силу тяжести и наклон
            ss += x * x;
            re[i] = x * hann[i];
            im[i] = 0;
        }
        rms = Math.sqrt(ss / N);
        fft.forward(re, im);
        // амплитуда синуса: 2|X| / сумма окна (= N/2 для Ханна)
        int best = -1;
        double bv = 0;
        for (int k = 0; k < N / 2; k++) {
            double amp = 2 * Math.hypot(re[k], im[k]) / (N / 2.0);
            spec[k] = (float) amp;
            if (k >= 2 && amp > bv) {
                bv = amp;
                best = k;
            }
        }
        peakAmp = bv;
        peakHz = best > 0 ? best * fs / N : Double.NaN;
        velocityMmS = best > 0 ? bv / (2 * Math.PI * peakHz) / Math.sqrt(2) * 1000.0 : 0;
        return true;
    }
}
