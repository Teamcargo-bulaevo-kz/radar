package kz.bulaevo.radar.dsp;

import java.util.Arrays;

/**
 * Частотный тест "динамик → микрофон": телефон проигрывает плавный свип, записывает его же и
 * определяет, на каких частотах звук реально доходит до микрофона. По результату подбирается
 * лучший диапазон зондирования для сонара.
 */
public final class ResponseAnalyzer {
    private ResponseAnalyzer() {
    }

    public static final double BIN_HZ = 250.0;
    public static final int FRAME = 2048;

    public static final class Result {
        public final int count;
        /** Частота центра ячейки, Гц. */
        public final float[] freq;
        /** Уровень принятого тона, дБFS (NaN — нет данных). */
        public final float[] level;
        /** Превышение над шумом рядом, дБ (NaN — нет данных). */
        public final float[] snr;

        Result(int count) {
            this.count = count;
            freq = new float[count];
            level = new float[count];
            snr = new float[count];
            for (int i = 0; i < count; i++) {
                freq[i] = (float) ((i + 0.5) * BIN_HZ);
                level[i] = Float.NaN;
                snr[i] = Float.NaN;
            }
        }
    }

    public static final class Pick {
        public int band;
        public double score;
        public final double[] scores = new double[Bands.NAMES.length];
        public boolean ultrasoundOk;
    }

    /** Линейный свип f0→f1 за seconds секунд, с плавным входом и выходом. */
    public static short[] makeSweep(double fs, double seconds, double f0, double f1, double amp) {
        int len = (int) (fs * seconds);
        short[] s = new short[len];
        double k = (f1 - f0) / seconds;
        int fade = (int) (0.05 * fs);
        for (int i = 0; i < len; i++) {
            double t = i / fs;
            double ph = 2.0 * Math.PI * (f0 * t + 0.5 * k * t * t);
            double a = amp;
            if (i < fade) {
                a *= i / (double) fade;
            } else if (i > len - fade) {
                a *= (len - i) / (double) fade;
            }
            s[i] = (short) Math.round(a * 32767.0 * Math.sin(ph));
        }
        return s;
    }

    public static Result analyze(short[] rx, int len, double fs) {
        final int count = (int) Math.floor(fs / 2.0 / BIN_HZ);
        Result r = new Result(count);
        SpectrumAnalyzer sa = new SpectrumAnalyzer(FRAME, fs);
        final double binHz = sa.binHz();
        final int kMin = (int) Math.ceil(500.0 / binHz);
        final int kMax = (int) Math.floor((fs / 2.0 - 250.0) / binHz);
        final int near = (int) Math.round(1500.0 / binHz);
        final int excl = (int) Math.round(300.0 / binHz);
        float[] tmp = new float[2 * near + 1];
        for (int off = 0; off + FRAME <= len; off += FRAME / 2) {
            sa.analyze(rx, off);
            int pk = kMin;
            float pv = -1000f;
            for (int k = kMin; k <= kMax; k++) {
                if (sa.db[k] > pv) {
                    pv = sa.db[k];
                    pk = k;
                }
            }
            int c = 0;
            for (int k = Math.max(2, pk - near); k <= Math.min(FRAME / 2 - 1, pk + near); k++) {
                if (Math.abs(k - pk) > excl) {
                    tmp[c++] = sa.db[k];
                }
            }
            if (c < 8) {
                continue;
            }
            Arrays.sort(tmp, 0, c);
            float noise = tmp[c / 2];
            float snr = pv - noise;
            if (snr < 6f) {
                continue; // тона нет — шум
            }
            int cell = (int) ((pk * binHz) / BIN_HZ);
            if (cell < 0 || cell >= count) {
                continue;
            }
            if (Float.isNaN(r.snr[cell]) || snr > r.snr[cell]) {
                r.snr[cell] = snr;
                r.level[cell] = pv;
            }
        }
        return r;
    }

    /** Средняя "годность" полосы: среднее превышение над шумом (до 40 дБ), пропуски считаются нулём. */
    public static double bandScore(Result r, double f0, double f1) {
        double sum = 0;
        int cnt = 0;
        for (int i = 0; i < r.count; i++) {
            double f = r.freq[i];
            if (f >= f0 && f < f1) {
                float s = r.snr[i];
                double v = Float.isNaN(s) ? 0.0 : Math.max(0.0, Math.min(40.0, s));
                sum += v;
                cnt++;
            }
        }
        return cnt == 0 ? 0 : sum / cnt;
    }

    /** Выбрать лучший диапазон: тихий ультразвук, если он слышен микрофону, иначе лучший из слышимых. */
    public static Pick pick(Result r) {
        Pick p = new Pick();
        int bestUs = -1;
        double bestUsScore = -1;
        int bestAll = 0;
        double bestAllScore = -1;
        for (int b = 0; b < Bands.NAMES.length; b++) {
            double f0 = Bands.FC[b] - Bands.BW[b] / 2.0;
            double f1 = Bands.FC[b] + Bands.BW[b] / 2.0;
            double sc = bandScore(r, f0, f1);
            p.scores[b] = sc;
            if (sc > bestAllScore) {
                bestAllScore = sc;
                bestAll = b;
            }
            if (!Bands.AUDIBLE[b] && sc > bestUsScore) {
                bestUsScore = sc;
                bestUs = b;
            }
        }
        if (bestUs >= 0 && bestUsScore >= 18.0) {
            p.band = bestUs;
            p.score = bestUsScore;
            p.ultrasoundOk = true;
        } else {
            p.band = bestAll;
            p.score = bestAllScore;
            p.ultrasoundOk = false;
        }
        return p;
    }
}
