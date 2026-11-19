package kz.bulaevo.radar.dsp;

import java.util.Arrays;

/**
 * Превращает окно записи микрофона (ровно n отсчётов) в профиль эха по дальности.
 * <p>
 * Идея: динамик непрерывно играет периодический чирп с периодом n отсчётов. Микрофон слышит его
 * свёрнутым с "каналом" (прямой звук динамик→микрофон + эхо от стен и предметов). Так как сигнал
 * периодический, любое окно из n отсчётов даёт циклическую свёртку, и канал восстанавливается
 * делением спектров (Y / X, с регуляризацией). Модуль результата — профиль эха; номер отсчёта
 * соответствует задержке, а значит и дальности: R = c * задержка / 2.
 * <p>
 * Неизвестная задержка между воспроизведением и записью убирается привязкой к прямому сигналу
 * (он самый сильный и стоит ближе всех): профиль циклически сдвигается так, чтобы он стал нулём.
 */
public final class SonarProcessor {
    public static final double C = 343.0;
    /** Поправка дальности: расстояние динамик–микрофон ~6 см, эхо проходит путь туда-обратно. */
    public static final double DIRECT_OFFSET_M = 0.03;
    /** Ближе этого эхо не ищем: хвост прямого сигнала и звон корпуса. */
    public static final double MIN_RANGE_M = 0.20;

    private static final int LOCK_WIN = 6;
    private static final int WARMUP = 3;
    private static final int PEAK_GUARD = 12;
    private static final double NEAR_LEAK_M = 0.8;
    private static final int MOTION_SMOOTH = 9;

    public final double fs;
    public final int n;
    public final ChirpDesign chirp;
    public final int bins;
    public final double binM;
    public final SonarFrame out;

    /** Порог обнаружения пика, дБ над шумом. */
    public volatile double detectDb = 12.0;
    /** Скорость забывания фона для режима движения (доля на кадр). */
    public volatile double bgAlpha = 0.05;

    private final FFT fft;
    private final double[] xRe;
    private final double[] xIm;
    private final double[] wgt;
    private final int kLo;
    private final int kHi;
    private final int kc;
    private final double lambda;

    private final double[] re;
    private final double[] im;
    private final double[] zRe;
    private final double[] zIm;
    private final double[] env;
    private final double[] sortBuf;
    private final double[] zrRe;
    private final double[] zrIm;
    private final double[] bgRe;
    private final double[] bgIm;
    private final double[] mag;
    private final double[] prefix;

    private final int nearBins;
    private final double[] leakRe;
    private final double[] leakIm;
    private final double[] leakAccRe;
    private final double[] leakAccIm;

    private int lock = -1;
    private int lockBad;
    private int frames;
    private boolean bgInit;
    private double noise;
    private boolean haveLeak;
    private int calibLeft;
    private int calibTotal;

    public SonarProcessor(double fs, int n, ChirpDesign chirp) {
        if (chirp.n != n) {
            throw new IllegalArgumentException("Размер чирпа не совпадает с окном");
        }
        this.fs = fs;
        this.n = n;
        this.chirp = chirp;
        this.binM = C / (2.0 * fs);
        this.bins = n - 48;
        this.fft = new FFT(n);

        xRe = new double[n];
        xIm = new double[n];
        System.arraycopy(chirp.samples, 0, xRe, 0, n);
        fft.forward(xRe, xIm);

        kLo = Math.max(1, chirp.binLo());
        kHi = Math.min(n / 2 - 1, chirp.binHi());
        kc = chirp.kc;
        wgt = new double[n];
        double sumP = 0;
        int cnt = 0;
        int width = kHi - kLo + 1;
        for (int k = kLo; k <= kHi; k++) {
            // окно Ханна по полосе: подавляет боковые лепестки по дальности
            wgt[k] = 0.5 * (1.0 - Math.cos(2.0 * Math.PI * (k - kLo + 0.5) / width));
            sumP += xRe[k] * xRe[k] + xIm[k] * xIm[k];
            cnt++;
        }
        lambda = 0.05 * (sumP / Math.max(1, cnt));

        re = new double[n];
        im = new double[n];
        zRe = new double[n];
        zIm = new double[n];
        env = new double[n];
        sortBuf = new double[n];
        zrRe = new double[bins];
        zrIm = new double[bins];
        bgRe = new double[bins];
        bgIm = new double[bins];
        mag = new double[bins];
        prefix = new double[bins + 1];

        nearBins = Math.min(bins, (int) Math.ceil(NEAR_LEAK_M / binM));
        leakRe = new double[nearBins];
        leakIm = new double[nearBins];
        leakAccRe = new double[nearBins];
        leakAccIm = new double[nearBins];

        out = new SonarFrame(bins, (float) binM, (float) DIRECT_OFFSET_M);
    }

    /** Дальность (м) для дробного номера отсчёта. */
    public double rangeOf(double bin) {
        return DIRECT_OFFSET_M + bin * binM;
    }

    /** Запомнить текущую картину ближней зоны как "утечку" корпуса (держите телефон в пустое пространство). */
    public void beginLeakCalibration(int framesToAverage) {
        calibTotal = Math.max(4, framesToAverage);
        calibLeft = calibTotal;
        Arrays.fill(leakAccRe, 0);
        Arrays.fill(leakAccIm, 0);
    }

    public void clearLeak() {
        haveLeak = false;
        calibLeft = 0;
        Arrays.fill(leakRe, 0);
        Arrays.fill(leakIm, 0);
    }

    public boolean hasLeak() {
        return haveLeak;
    }

    public boolean isCalibrating() {
        return calibLeft > 0;
    }

    /** Забыть фон движения (например, после смены положения). */
    public void resetBackground() {
        bgInit = false;
    }

    /**
     * Обработать окно записи.
     *
     * @param y ровно n отсчётов 16 бит
     * @return внутренний объект результата (переиспользуется)
     */
    public SonarFrame process(short[] y) {
        final int n = this.n;
        double mean = 0;
        boolean clip = false;
        for (int i = 0; i < n; i++) {
            mean += y[i];
            if (y[i] >= 32000 || y[i] <= -32000) {
                clip = true;
            }
        }
        mean /= n;
        for (int i = 0; i < n; i++) {
            re[i] = y[i] - mean;
            im[i] = 0;
        }
        fft.forward(re, im);

        Arrays.fill(zRe, 0);
        Arrays.fill(zIm, 0);
        for (int k = kLo; k <= kHi; k++) {
            double xr = xRe[k];
            double xi = xIm[k];
            double den = xr * xr + xi * xi + lambda;
            double hr = (re[k] * xr + im[k] * xi) / den;
            double hi = (im[k] * xr - re[k] * xi) / den;
            double w = wgt[k];
            int kk = k - kc;
            if (kk < 0) {
                kk += n;
            }
            zRe[kk] = hr * w;
            zIm[kk] = hi * w;
        }
        fft.inverse(zRe, zIm);

        int g = 0;
        double vg = -1;
        for (int i = 0; i < n; i++) {
            double e = Math.hypot(zRe[i], zIm[i]);
            env[i] = e;
            if (e > vg) {
                vg = e;
                g = i;
            }
        }

        // шум: медиана модуля по всему кругу
        System.arraycopy(env, 0, sortBuf, 0, n);
        Arrays.sort(sortBuf);
        double med = Math.max(sortBuf[n / 2], 1e-12);
        noise = (frames < WARMUP + 2) ? med : (0.9 * noise + 0.1 * med);

        // привязка к прямому сигналу
        boolean relocked = false;
        if (lock < 0) {
            lock = g;
            lockBad = 0;
            relocked = true;
        } else {
            int best = lock;
            double bv = -1;
            for (int d = -LOCK_WIN; d <= LOCK_WIN; d++) {
                int idx = lock + d;
                if (idx < 0) {
                    idx += n;
                } else if (idx >= n) {
                    idx -= n;
                }
                if (env[idx] > bv) {
                    bv = env[idx];
                    best = idx;
                }
            }
            if (bv >= 0.35 * vg) {
                lock = best;
                lockBad = 0;
            } else if (++lockBad >= 6) {
                lock = g;
                lockBad = 0;
                relocked = true;
            }
        }
        if (relocked) {
            bgInit = false;
        }

        final SonarFrame o = out;
        o.index = frames;
        o.clipped = clip;
        double refR = zRe[lock];
        double refI = zIm[lock];
        double refM = Math.max(Math.hypot(refR, refI), 1e-12);
        o.quality = (float) (20.0 * Math.log10(refM / noise));

        if (frames < WARMUP) {
            frames++;
            o.warming = true;
            o.peakCount = 0;
            o.nearestM = Float.NaN;
            o.motionScore = 0;
            o.motionRangeM = Float.NaN;
            Arrays.fill(o.snrDb, 0f);
            Arrays.fill(o.motionDb, 0f);
            return o;
        }
        frames++;
        o.warming = false;

        // циклический сдвиг и нормировка фазы по прямому сигналу
        final double pr = refR / refM;
        final double pi = -refI / refM;
        int idx = lock;
        for (int m = 0; m < bins; m++) {
            double a = zRe[idx];
            double b = zIm[idx];
            zrRe[m] = a * pr - b * pi;
            zrIm[m] = a * pi + b * pr;
            if (++idx == n) {
                idx = 0;
            }
        }

        // калибровка утечки
        if (calibLeft > 0) {
            for (int m = 0; m < nearBins; m++) {
                leakAccRe[m] += zrRe[m];
                leakAccIm[m] += zrIm[m];
            }
            if (--calibLeft == 0) {
                for (int m = 0; m < nearBins; m++) {
                    leakRe[m] = leakAccRe[m] / calibTotal;
                    leakIm[m] = leakAccIm[m] / calibTotal;
                }
                haveLeak = true;
            }
        }

        // сила эха
        final double invNoise = 1.0 / noise;
        for (int m = 0; m < bins; m++) {
            double a = zrRe[m];
            double b = zrIm[m];
            if (haveLeak && m < nearBins) {
                a -= leakRe[m];
                b -= leakIm[m];
            }
            double amp = Math.hypot(a, b);
            double db = 20.0 * Math.log10(Math.max(amp * invNoise, 1e-6));
            o.snrDb[m] = (float) Math.max(-10.0, Math.min(90.0, db));
        }

        // движение: комплексная разность с медленным фоном
        if (!bgInit) {
            System.arraycopy(zrRe, 0, bgRe, 0, bins);
            System.arraycopy(zrIm, 0, bgIm, 0, bins);
            bgInit = true;
            Arrays.fill(mag, 0);
            Arrays.fill(o.motionDb, 0f);
        } else {
            final double al = bgAlpha;
            for (int m = 0; m < bins; m++) {
                double dr = zrRe[m] - bgRe[m];
                double di = zrIm[m] - bgIm[m];
                double mg = Math.hypot(dr, di);
                mag[m] = mg;
                double db = 20.0 * Math.log10(Math.max(mg * invNoise, 1e-6));
                o.motionDb[m] = (float) Math.max(-10.0, Math.min(90.0, db));
                bgRe[m] += al * dr;
                bgIm[m] += al * di;
            }
        }

        // оценка движения: максимум сглаженной разности вне ближней зоны
        final int mMin = Math.max(1, (int) Math.ceil((MIN_RANGE_M - DIRECT_OFFSET_M) / binM));
        prefix[0] = 0;
        for (int m = 0; m < bins; m++) {
            prefix[m + 1] = prefix[m] + mag[m];
        }
        double bestMotion = 0;
        int bestMotionBin = -1;
        final int half = MOTION_SMOOTH / 2;
        for (int m = Math.max(mMin, half); m < bins - half; m++) {
            double s = (prefix[m + half + 1] - prefix[m - half]) / MOTION_SMOOTH;
            if (s > bestMotion) {
                bestMotion = s;
                bestMotionBin = m;
            }
        }
        if (bestMotionBin >= 0) {
            o.motionScore = (float) (20.0 * Math.log10(Math.max(bestMotion * invNoise, 1e-6)));
            o.motionRangeM = (float) rangeOf(bestMotionBin);
        } else {
            o.motionScore = 0;
            o.motionRangeM = Float.NaN;
        }

        findPeaks(o, mMin);
        return o;
    }

    private void findPeaks(SonarFrame o, int mMin) {
        final float[] s = o.snrDb;
        final double thr = detectDb;
        int cnt = 0;
        for (int m = Math.max(1, mMin); m < bins - 1 && cnt < SonarFrame.MAX_PEAKS; m++) {
            float a = s[m - 1];
            float b = s[m];
            float c = s[m + 1];
            if (b < thr || b <= a || b < c) {
                continue;
            }
            boolean ok = true;
            for (int d = -PEAK_GUARD; d <= PEAK_GUARD; d++) {
                int j = m + d;
                if (j >= 0 && j < bins && s[j] > b) {
                    ok = false;
                    break;
                }
            }
            if (!ok) {
                continue;
            }
            double den = a - 2.0 * b + c;
            double delta = Math.abs(den) < 1e-9 ? 0.0 : 0.5 * (a - c) / den;
            if (delta > 1) {
                delta = 1;
            } else if (delta < -1) {
                delta = -1;
            }
            o.peakR[cnt] = (float) rangeOf(m + delta);
            o.peakDb[cnt] = b;
            cnt++;
            m += PEAK_GUARD;
        }
        o.peakCount = cnt;
        if (cnt > 0) {
            o.nearestM = o.peakR[0];
            o.nearestDb = o.peakDb[0];
        } else {
            o.nearestM = Float.NaN;
            o.nearestDb = 0;
        }
    }
}
