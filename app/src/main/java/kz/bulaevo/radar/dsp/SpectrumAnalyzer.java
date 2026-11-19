package kz.bulaevo.radar.dsp;

/** Спектр звука: окно Ханна + БПФ, уровень в дБ относительно полной шкалы (дБFS). */
public final class SpectrumAnalyzer {
    public final int n;
    public final double fs;
    /** Уровень по бинам 0..n/2-1, дБFS (синус полной амплитуды = 0 дБFS). */
    public final float[] db;
    public double peakHz;
    public double peakDb;

    private final FFT fft;
    private final double[] window;
    private final double[] re;
    private final double[] im;
    private final double norm;

    public SpectrumAnalyzer(int n, double fs) {
        this.n = n;
        this.fs = fs;
        this.fft = new FFT(n);
        this.window = new double[n];
        double sum = 0;
        for (int i = 0; i < n; i++) {
            window[i] = 0.5 * (1.0 - Math.cos(2.0 * Math.PI * i / n));
            sum += window[i];
        }
        this.norm = 2.0 / (sum * 32768.0);
        this.re = new double[n];
        this.im = new double[n];
        this.db = new float[n / 2];
    }

    public double binHz() {
        return fs / n;
    }

    /** Анализ n отсчётов, начиная с off. */
    public void analyze(short[] x, int off) {
        double mean = 0;
        for (int i = 0; i < n; i++) {
            mean += x[off + i];
        }
        mean /= n;
        for (int i = 0; i < n; i++) {
            re[i] = (x[off + i] - mean) * window[i];
            im[i] = 0;
        }
        fft.forward(re, im);
        int best = 2;
        float bestV = -1000f;
        for (int k = 0; k < n / 2; k++) {
            double mag = Math.hypot(re[k], im[k]) * norm;
            float d = (float) (20.0 * Math.log10(Math.max(mag, 1e-9)));
            db[k] = d;
            if (k >= 2 && d > bestV) {
                bestV = d;
                best = k;
            }
        }
        // уточнение пика параболой
        double delta = 0;
        if (best > 1 && best < n / 2 - 1) {
            double a = db[best - 1];
            double b = db[best];
            double c = db[best + 1];
            double den = a - 2.0 * b + c;
            if (Math.abs(den) > 1e-9) {
                delta = 0.5 * (a - c) / den;
                if (delta > 1) {
                    delta = 1;
                } else if (delta < -1) {
                    delta = -1;
                }
            }
        }
        peakHz = (best + delta) * binHz();
        peakDb = bestV;
    }
}
