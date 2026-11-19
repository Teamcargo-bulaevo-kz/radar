package kz.bulaevo.radar.dsp;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Самопроверка математики на симуляции: виртуальная комната с известными расстояниями.
 * Запускается в юнит-тестах сборки и кнопкой "Самотест" в приложении (там же замеряется скорость).
 */
public final class SelfTest {
    private SelfTest() {
    }

    public static final class Report {
        public final List<String> lines = new ArrayList<>();
        public int failures;
        public double msPerFrame;

        void ok(String name, String detail) {
            lines.add("OK    " + name + (detail.isEmpty() ? "" : " — " + detail));
        }

        void fail(String name, String detail) {
            failures++;
            lines.add("FAIL  " + name + " — " + detail);
        }

        void check(boolean cond, String name, String detail) {
            if (cond) {
                ok(name, detail);
            } else {
                fail(name, detail);
            }
        }

        public String text() {
            StringBuilder sb = new StringBuilder();
            for (String s : lines) {
                sb.append(s).append('\n');
            }
            sb.append(failures == 0 ? "ВСЕ ПРОВЕРКИ ПРОЙДЕНЫ" : ("ОШИБОК: " + failures));
            return sb.toString();
        }
    }

    public static Report runAll() {
        Report r = new Report();
        testFft(r);
        testChirpPeriodic(r);
        testRanging(r, 2048, 0, "дальность 7 м, УЗ 18–22 кГц");
        testRanging(r, 1024, 1, "дальность 3.5 м, УЗ 17–21 кГц");
        testRanging(r, 4096, 5, "дальность 14 м, звук 2–18 кГц");
        testDrift(r);
        testMotion(r);
        testLeakCalibration(r);
        testSpectrum(r);
        testResponse(r);
        testRoomMap(r);
        testNight(r);
        testParking(r);
        testSectors(r);
        testSpeed(r);
        return r;
    }

    // ------------------------------------------------------------------------------------------

    private static void testFft(Report r) {
        int n = 1024;
        FFT fft = new FFT(n);
        double[] re = new double[n];
        double[] im = new double[n];
        double[] orig = new double[n];
        Random rnd = new Random(1);
        for (int i = 0; i < n; i++) {
            orig[i] = rnd.nextGaussian();
            re[i] = orig[i];
        }
        fft.forward(re, im);
        fft.inverse(re, im);
        double err = 0;
        for (int i = 0; i < n; i++) {
            err = Math.max(err, Math.abs(re[i] - orig[i]) + Math.abs(im[i]));
        }
        // чистый синус: пик в нужном бине
        double[] a = new double[n];
        double[] b = new double[n];
        for (int i = 0; i < n; i++) {
            a[i] = Math.sin(2 * Math.PI * 100 * i / n);
        }
        fft.forward(a, b);
        int pk = 0;
        double pv = 0;
        for (int k = 0; k < n / 2; k++) {
            double m = Math.hypot(a[k], b[k]);
            if (m > pv) {
                pv = m;
                pk = k;
            }
        }
        r.check(err < 1e-9 && pk == 100, "БПФ", String.format(Locale.US, "ошибка круга %.1e, пик в бине %d", err, pk));
    }

    private static void testChirpPeriodic(Report r) {
        ChirpDesign cd = new ChirpDesign(48000, 2048, 20000, 4000);
        double t = 0.00123;
        double v0 = cd.value(t);
        double v1 = cd.value(t + 2048 / 48000.0);
        double v2 = cd.value(t + 5 * 2048 / 48000.0);
        double edge = Math.max(Math.abs(cd.samples[0]), Math.abs(cd.samples[2047]));
        double peak = 0;
        for (double v : cd.samples) {
            peak = Math.max(peak, Math.abs(v));
        }
        r.check(Math.abs(v0 - v1) < 1e-6 && Math.abs(v0 - v2) < 1e-6 && edge < 0.01 && peak > 0.99 && peak <= 1.0,
                "Чирп периодичен, края сведены в ноль", String.format(Locale.US, "f0=%.0f f1=%.0f Гц, на стыке %.4f", cd.f0, cd.f1, edge));
    }

    // ---- симуляция канала ----

    /** Синтез записи микрофона: прямой сигнал + эхо с точными дробными задержками + шум. */
    static short[] simulate(ChirpDesign cd, int total, double[] delaySamples, double[] amps,
                            double noiseRms, double startOffset, double ppm, Random rnd) {
        short[] y = new short[total];
        final double fs = cd.fs;
        final double scale = 0.25 * 32767.0; // прямой сигнал = 0.25 от полной шкалы
        for (int i = 0; i < total; i++) {
            double tt = (i * (1.0 + ppm * 1e-6) + startOffset) / fs;
            double s = 0;
            for (int p = 0; p < delaySamples.length; p++) {
                s += amps[p] * cd.value(tt - delaySamples[p] / fs);
            }
            double v = scale * s + noiseRms * 32767.0 * rnd.nextGaussian();
            y[i] = (short) Math.max(-32768, Math.min(32767, Math.round(v)));
        }
        return y;
    }

    private static double delayFor(double rangeM) {
        // эхо: путь 2R, прямой: 6 см
        return (2.0 * rangeM) / SonarProcessor.C * Bands.FS;
    }

    private static void testRanging(Report r, int n, int band, String title) {
        ChirpDesign cd = new ChirpDesign(Bands.FS, n, Bands.FC[band], Bands.BW[band]);
        SonarProcessor sp = new SonarProcessor(Bands.FS, n, cd);
        sp.detectDb = 10;
        double maxR = SonarProcessor.C * n / (2 * Bands.FS) - 0.3;
        double[] ranges = {0.9, 2.3, Math.min(3.1, maxR - 0.1)};
        double[] amps = {1.0, 0.030, 0.012, 0.010};
        double[] delays = new double[4];
        delays[0] = 0.06 / SonarProcessor.C * Bands.FS + 523.37; // прямой + неизвестная задержка тракта
        for (int i = 0; i < 3; i++) {
            delays[i + 1] = delayFor(ranges[i]) + 523.37;
        }
        Random rnd = new Random(7);
        int frames = 40;
        short[] stream = simulate(cd, n * frames, delays, amps, 0.0001, 311.7, 0, rnd);
        short[] win = new short[n];
        SonarFrame f = null;
        for (int k = 0; k < frames; k++) {
            System.arraycopy(stream, k * n, win, 0, n);
            f = sp.process(win);
        }
        double[] found = new double[3];
        java.util.Arrays.fill(found, Double.NaN);
        for (int i = 0; i < 3; i++) {
            for (int p = 0; p < f.peakCount; p++) {
                if (Math.abs(f.peakR[p] - ranges[i]) < 0.08) {
                    found[i] = f.peakR[p];
                }
            }
        }
        boolean allFound = !Double.isNaN(found[0]) && !Double.isNaN(found[1]) && !Double.isNaN(found[2]);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            sb.append(String.format(Locale.US, "%.2f→%s ", ranges[i],
                    Double.isNaN(found[i]) ? "нет" : String.format(Locale.US, "%.3f", found[i])));
        }
        sb.append(String.format(Locale.US, "(качество %.0f дБ, пиков %d)", f.quality, f.peakCount));
        boolean nearestOk = !Double.isNaN(f.nearestM) && Math.abs(f.nearestM - ranges[0]) < 0.06;
        r.check(allFound && nearestOk, "Дальномер, " + title, sb.toString());
    }

    private static void testDrift(Report r) {
        int n = 2048;
        ChirpDesign cd = new ChirpDesign(Bands.FS, n, Bands.FC[0], Bands.BW[0]);
        SonarProcessor sp = new SonarProcessor(Bands.FS, n, cd);
        sp.detectDb = 10;
        double R = 1.6;
        double[] delays = {0.06 / SonarProcessor.C * Bands.FS + 100.1, delayFor(R) + 100.1};
        double[] amps = {1.0, 0.030};
        Random rnd = new Random(3);
        int frames = 120;
        // сильный дрейф 300 ppm: сдвиг ~0.6 отсчёта на кадр
        short[] stream = simulate(cd, n * frames, delays, amps, 0.0001, 77.0, 300, rnd);
        short[] win = new short[n];
        double worst = 0;
        int lost = 0;
        for (int k = 0; k < frames; k++) {
            System.arraycopy(stream, k * n, win, 0, n);
            SonarFrame f = sp.process(win);
            if (k < 8) {
                continue;
            }
            double best = 9;
            for (int p = 0; p < f.peakCount; p++) {
                best = Math.min(best, Math.abs(f.peakR[p] - R));
            }
            if (best > 0.08) {
                lost++;
            } else {
                worst = Math.max(worst, best);
            }
        }
        r.check(lost == 0, "Дрейф тактов 300 ppm", String.format(Locale.US, "потерь %d из %d, худшее отклонение %.3f м", lost, frames - 8, worst));
    }

    private static void testMotion(Report r) {
        int n = 2048;
        ChirpDesign cd = new ChirpDesign(Bands.FS, n, Bands.FC[0], Bands.BW[0]);
        SonarProcessor sp = new SonarProcessor(Bands.FS, n, cd);
        Random rnd = new Random(5);
        double wallR = 3.0;
        double personR = 1.8;
        int frames = 70;
        short[] win = new short[n];
        double staticScore = -100;
        double movingScore = -100;
        double movingRange = Double.NaN;
        for (int k = 0; k < frames; k++) {
            double R = personR + (k >= 40 ? 0.012 * Math.sin((k - 40) * 0.9) : 0.0); // шагает ±1.2 см
            double[] delays = {0.06 / SonarProcessor.C * Bands.FS + 40.0, delayFor(wallR) + 40.0, delayFor(R) + 40.0};
            double[] amps = {1.0, 0.020, 0.020};
            short[] w = simulate(cd, n, delays, amps, 0.0001, 0, 0, rnd);
            System.arraycopy(w, 0, win, 0, n);
            SonarFrame f = sp.process(win);
            if (k >= 25 && k < 40) {
                staticScore = Math.max(staticScore, f.motionScore);
            }
            if (k >= 45) {
                if (f.motionScore > movingScore) {
                    movingScore = f.motionScore;
                    movingRange = f.motionRangeM;
                }
            }
        }
        boolean ok = movingScore > staticScore + 8 && Math.abs(movingRange - personR) < 0.15;
        r.check(ok, "Движение (±1.2 см)",
                String.format(Locale.US, "в покое %.1f дБ, при движении %.1f дБ на %.2f м", staticScore, movingScore, movingRange));
    }

    private static void testLeakCalibration(Report r) {
        int n = 2048;
        ChirpDesign cd = new ChirpDesign(Bands.FS, n, Bands.FC[0], Bands.BW[0]);
        SonarProcessor sp = new SonarProcessor(Bands.FS, n, cd);
        Random rnd = new Random(11);
        // "звон корпуса": постоянное ложное эхо на 0.45 м
        double[] delays = {0.06 / SonarProcessor.C * Bands.FS + 200.0, delayFor(0.45) + 200.0};
        double[] amps = {1.0, 0.03};
        sp.detectDb = 10;
        short[] win = new short[n];
        double before = Double.NaN;
        for (int k = 0; k < 30; k++) {
            short[] w = simulate(cd, n, delays, amps, 0.0001, 0, 0, rnd);
            System.arraycopy(w, 0, win, 0, n);
            SonarFrame f = sp.process(win);
            if (k == 10) {
                before = f.nearestM;
                sp.beginLeakCalibration(12);
            }
        }
        SonarFrame f = null;
        for (int k = 0; k < 6; k++) {
            short[] w = simulate(cd, n, delays, amps, 0.0001, 0, 0, rnd);
            System.arraycopy(w, 0, win, 0, n);
            f = sp.process(win);
        }
        boolean ok = Math.abs(before - 0.45) < 0.06 && sp.hasLeak() && (f.peakCount == 0 || f.nearestM > 0.6);
        r.check(ok, "Калибровка утечки корпуса", String.format(Locale.US, "ложное эхо до: %.2f м, после: %s", before,
                f.peakCount == 0 ? "убрано" : String.format(Locale.US, "%.2f м", f.nearestM)));
    }

    private static void testSpectrum(Report r) {
        int n = 4096;
        SpectrumAnalyzer sa = new SpectrumAnalyzer(n, Bands.FS);
        short[] x = new short[n];
        double a = 0.1; // -20 дБFS
        for (int i = 0; i < n; i++) {
            x[i] = (short) Math.round(a * 32767 * Math.sin(2 * Math.PI * 18123.0 * i / Bands.FS));
        }
        sa.analyze(x, 0);
        r.check(Math.abs(sa.peakHz - 18123.0) < 8.0 && Math.abs(sa.peakDb + 20.0) < 1.5, "Спектр-анализатор",
                String.format(Locale.US, "пик %.1f Гц (ждём 18123), %.1f дБFS (ждём -20)", sa.peakHz, sa.peakDb));
    }

    private static void testResponse(Report r) {
        double fs = Bands.FS;
        double secs = 6.0;
        double f0 = 1000;
        double f1 = 23500;
        int len = (int) (fs * (secs + 1.0));
        short[] rx = new short[len];
        Random rnd = new Random(2);
        double k = (f1 - f0) / secs;
        int lead = (int) (0.4 * fs);
        for (int i = 0; i < len; i++) {
            double v = 0;
            double t = (i - lead) / fs;
            if (t >= 0 && t < secs) {
                double f = f0 + k * t;
                // тракт: ровный до 14 кГц, дальше затухание до -45 дБ к 22 кГц
                double gdb = f < 14000 ? 0 : -45.0 * (f - 14000) / 8000.0;
                double g = Math.pow(10, gdb / 20.0);
                v = 0.5 * g * Math.sin(2 * Math.PI * (f0 * t + 0.5 * k * t * t));
            }
            v += 0.01 * rnd.nextGaussian();
            rx[i] = (short) Math.round(v * 32767);
        }
        ResponseAnalyzer.Result res = ResponseAnalyzer.analyze(rx, len, fs);
        ResponseAnalyzer.Pick pk = ResponseAnalyzer.pick(res);
        double lowSnr = ResponseAnalyzer.bandScore(res, 8000, 12000);
        double highSnr = ResponseAnalyzer.bandScore(res, 20000, 22000);
        boolean ok = lowSnr > highSnr + 10 && pk.band != 0;
        r.check(ok, "Частотный тест динамика",
                String.format(Locale.US, "8–12 кГц: %.0f дБ, 20–22 кГц: %.0f дБ → выбран «%s»", lowSnr, highSnr, Bands.NAMES[pk.band]));
        // и "хороший" тракт: ультразвук выбирается
        for (int i = 0; i < len; i++) {
            double v = 0;
            double t = (i - lead) / fs;
            if (t >= 0 && t < secs) {
                v = 0.3 * Math.sin(2 * Math.PI * (f0 * t + 0.5 * k * t * t));
            }
            v += 0.01 * rnd.nextGaussian();
            rx[i] = (short) Math.round(v * 32767);
        }
        res = ResponseAnalyzer.analyze(rx, len, fs);
        pk = ResponseAnalyzer.pick(res);
        r.check(pk.band == 0 && pk.ultrasoundOk, "Автоподбор при хорошем динамике",
                "выбран «" + Bands.NAMES[pk.band] + "»");
    }

    private static void testRoomMap(Report r) {
        RoomMap map = new RoomMap(180, 120);
        map.setMaxRange(6.0);
        SonarFrame f = new SonarFrame(2000, (float) (SonarProcessor.C / (2 * Bands.FS)), 0.03f);
        // комната: круг радиуса 2 м, но "угол" 3.5 м в направлении 90°
        for (int pass = 0; pass < 3; pass++) {
            for (int az = 0; az < 360; az += 3) {
                double wall = (az >= 80 && az <= 100) ? 3.5 : 2.0;
                java.util.Arrays.fill(f.snrDb, 0f);
                for (int m = 0; m < f.bins; m++) {
                    double rr = f.rangeOf(m);
                    double d = (rr - wall) / 0.06;
                    f.snrDb[m] = (float) (35.0 * Math.exp(-d * d));
                }
                map.update(az, f, 8, 30, 8);
            }
        }
        map.computeOutline(0.4f, 0.3);
        int good = 0;
        int total = 0;
        for (int a = 0; a < 180; a++) {
            double deg = a * 2.0 + 1.0;
            double expect = (deg >= 80 && deg <= 100) ? 3.5 : 2.0;
            if (deg > 74 && deg < 106) {
                continue; // границу не считаем
            }
            total++;
            if (!Float.isNaN(map.outlineR[a]) && Math.abs(map.outlineR[a] - expect) < 0.15) {
                good++;
            }
        }
        int step = map.angleIndex(90);
        boolean cornerOk = !Float.isNaN(map.outlineR[step]) && Math.abs(map.outlineR[step] - 3.5) < 0.2;
        r.check(good >= total * 0.95 && cornerOk, "Карта комнаты (контур)", String.format(Locale.US,
                "верных углов %d из %d, ниша на 90°: %.2f м", good, total, map.outlineR[step]));
    }

    private static void testNight(Report r) {
        int w = 320;
        int h = 240;
        NightEnhancer ne = new NightEnhancer(w, h);
        ne.alpha = 0.15f;
        ne.palette = NightEnhancer.PAL_GRAY;
        Random rnd = new Random(9);
        byte[] y = new byte[w * h];
        int[] out = new int[w * h];
        double firstNoise = 0;
        double lastNoise = 0;
        int frames = 40;
        for (int k = 0; k < frames; k++) {
            for (int yy = 0; yy < h; yy++) {
                for (int xx = 0; xx < w; xx++) {
                    double base = 8 + 14.0 * xx / w + ((xx / 40 + yy / 40) % 2 == 0 ? 4 : 0); // тёмная сцена 8..26
                    double v = base + 5.0 * rnd.nextGaussian();
                    y[yy * w + xx] = (byte) Math.max(0, Math.min(255, Math.round(v)));
                }
            }
            ne.process(y, out);
            // шум по ровному участку (левый верх: 20x20)
            double s = 0;
            double s2 = 0;
            int c = 0;
            for (int yy = 2; yy < 20; yy++) {
                for (int xx = 2; xx < 20; xx++) {
                    double g = out[yy * w + xx] & 0xFF;
                    s += g;
                    s2 += g * g;
                    c++;
                }
            }
            double std = Math.sqrt(Math.max(0, s2 / c - (s / c) * (s / c)));
            if (k == 1) {
                firstNoise = std;
            }
            if (k == frames - 1) {
                lastNoise = std;
            }
        }
        int mn = 255;
        int mx = 0;
        for (int i = 0; i < out.length; i++) {
            int g = out[i] & 0xFF;
            mn = Math.min(mn, g);
            mx = Math.max(mx, g);
        }
        r.check(lastNoise < firstNoise * 0.7 && mx - mn > 150, "Ночной режим (накопление + растяжка)",
                String.format(Locale.US, "шум %.1f → %.1f, диапазон яркости %d..%d из исходных 3..32", firstNoise, lastNoise, mn, mx));
    }

    private static void testParking(Report r) {
        boolean mono = true;
        float prevI = Float.MAX_VALUE;
        float prevP = Float.MAX_VALUE;
        // от дальнего к ближнему: период убывает, тон растёт
        float lastI = 1e9f;
        float lastP = -1f;
        for (float d = 3.0f; d >= 0.3f; d -= 0.1f) {
            float iv = Parking.intervalMs(d);
            float p = Parking.pitchHz(d, 3.0f);
            if (iv > lastI + 1e-3 || p < lastP - 1e-3) {
                mono = false;
            }
            lastI = iv;
            lastP = p;
        }
        boolean range = Parking.pitchHz(0.3f, 3f) <= 1500f && Parking.pitchHz(3f, 3f) >= 600f
                && Parking.intervalMs(0.3f) >= 110f && Parking.intervalMs(10f) <= 1100f;
        r.check(mono && range, "Парктроник", String.format(Locale.US,
                "на 3 м: период %.0f мс, %.0f Гц; на 0.5 м: %.0f мс, %.0f Гц",
                Parking.intervalMs(3f), Parking.pitchHz(3f, 3f), Parking.intervalMs(0.5f), Parking.pitchHz(0.5f, 3f)));
    }

    /** Ходьба: шест на +30° от курса, ширина луча 12°. Телефон водят влево-вправо. */
    private static void testSectors(Report r) {
        SectorMemory m = new SectorMemory();
        long now = 1000;
        final double pole = 30.0;
        final float poleR = 1.6f;
        final float maxR = 7f;
        // водим курс от -60° до +60° и обратно, шаг 2°, кадр каждые 20 мс
        double[] heads = new double[122];
        for (int i = 0; i < 61; i++) {
            heads[i] = -60 + 2 * i;
            heads[61 + i] = 60 - 2 * i;
        }
        for (double h : heads) {
            // луч шириной ±12° видит шест, если направлен на него
            float seen = Math.abs(h - pole) <= 12 ? poleR : Float.NaN;
            m.update(h, seen, maxR, now, 12.0);
            now += 20;
        }
        float atPole = m.rangeAt(pole, now);
        float away = m.rangeAt(-40, now);
        float[] out = new float[1];
        // курс прямо на шест: он справа от курса на +30 → если смотрим на 20°, надо обходить
        double dirToward = m.clearestDirection(20, 60, 15, now, out);
        float fwd = m.nearestInCone(30, 6, now);
        // через 6 секунд без измерений память истекает
        float stale = m.rangeAt(pole, now + 6000);
        boolean ok = Math.abs(atPole - poleR) < 0.05 && away > 6.5f && fwd < poleR + 0.05
                && !Double.isNaN(dirToward) && dirToward < -10 && out[0] > 5f && Float.isNaN(stale);
        r.check(ok, "Ходьба (память по секторам)", String.format(Locale.US,
                "шест: %.2f м (ждём 1.60), в стороне: %.1f м, обход %.0f° (свободно %.1f м), через 6 с: %s",
                atPole, away, dirToward, out[0], Float.isNaN(stale) ? "забыто" : "помнит!"));
    }

    private static void testSpeed(Report r) {
        int n = 2048;
        ChirpDesign cd = new ChirpDesign(Bands.FS, n, Bands.FC[0], Bands.BW[0]);
        SonarProcessor sp = new SonarProcessor(Bands.FS, n, cd);
        Random rnd = new Random(1);
        short[] win = new short[n];
        for (int i = 0; i < n; i++) {
            win[i] = (short) (rnd.nextGaussian() * 500);
        }
        for (int i = 0; i < 60; i++) {
            sp.process(win);
        }
        int iters = 300;
        long t0 = System.nanoTime();
        for (int i = 0; i < iters; i++) {
            sp.process(win);
        }
        double ms = (System.nanoTime() - t0) / 1e6 / iters;
        r.msPerFrame = ms;
        double budget = n / Bands.FS * 1000.0;
        r.check(ms < budget * 0.5, "Скорость обработки", String.format(Locale.US, "%.2f мс на кадр (кадр длится %.1f мс)", ms, budget));
    }

    public static void main(String[] args) {
        Report r = runAll();
        System.out.println(r.text());
        System.exit(r.failures == 0 ? 0 : 1);
    }
}
