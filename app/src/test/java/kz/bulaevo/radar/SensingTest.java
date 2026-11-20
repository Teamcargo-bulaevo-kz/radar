package kz.bulaevo.radar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Random;

import kz.bulaevo.radar.dsp.FlickerAnalyzer;
import kz.bulaevo.radar.dsp.HeartSound;
import kz.bulaevo.radar.dsp.MagScan;
import kz.bulaevo.radar.dsp.MotionMagnifier;
import kz.bulaevo.radar.dsp.PulseEstimator;
import kz.bulaevo.radar.dsp.VibrationAnalyzer;

import org.junit.Test;

/** Пульс (камера и звук), мерцание ламп, усиление движения, вибрация, магнитная карта — на сигналах с известным ответом. */
public class SensingTest {

    /** Сигнал «как с камеры»: кадры ~30 в секунду с дрожанием времени, дрейф яркости, шум, пульс. */
    private static PulseEstimator cameraPulse(double bpm, double amp, long seed) {
        Random r = new Random(seed);
        PulseEstimator pe = new PulseEstimator(10.0);
        double t = 0;
        while (t < 15.0) {
            double f = bpm / 60.0;
            double ph = 2 * Math.PI * f * t;
            // форма пульсовой волны: основной тон + вторая гармоника
            double v = 120 + 3 * Math.sin(2 * Math.PI * 0.1 * t) + amp * (Math.sin(ph) + 0.4 * Math.sin(2 * ph + 1))
                    + r.nextGaussian() * 0.15;
            pe.add((long) (t * 1000), v);
            t += 1.0 / 30 + (r.nextDouble() - 0.5) * 0.01;
        }
        pe.update();
        return pe;
    }

    @Test
    public void pulseFromCameraSignal() {
        double[] rates = {48, 72, 95, 150};
        for (double bpm : rates) {
            PulseEstimator pe = cameraPulse(bpm, 0.6, (long) bpm);
            assertEquals("пульс " + bpm, bpm, pe.bpm, 3.0);
            assertTrue("уверенность " + pe.quality, pe.quality > 0.5);
        }
    }

    @Test
    public void noPulseGivesLowConfidence() {
        PulseEstimator pe = cameraPulse(72, 0.0, 7);
        assertTrue("уверенность без пульса " + pe.quality, Double.isNaN(pe.bpm) || pe.quality < 0.5);
    }

    @Test
    public void pulseFromHeartSounds() {
        int fs = HeartSound.FS_IN;
        double bpm = 66;
        double period = 60.0 / bpm;
        Random r = new Random(3);
        HeartSound hs = new HeartSound();
        short[] blk = new short[2400];
        int total = fs * 14;
        for (int i0 = 0; i0 < total; i0 += blk.length) {
            for (int j = 0; j < blk.length; j++) {
                double t = (i0 + j) / (double) fs;
                double tb = t % period;
                double v = 0;
                if (tb < 0.06) { // первый тон: «тук»
                    v += 0.25 * Math.sin(2 * Math.PI * 55 * tb) * Math.sin(Math.PI * tb / 0.06);
                }
                double t2 = tb - 0.32;
                if (t2 > 0 && t2 < 0.045) { // второй тон: «так»
                    v += 0.15 * Math.sin(2 * Math.PI * 90 * t2) * Math.sin(Math.PI * t2 / 0.045);
                }
                v += 0.3 * Math.sin(2 * Math.PI * 1200 * t); // писк выше полосы сердца — должен отсечься
                v += r.nextGaussian() * 0.01;
                blk[j] = (short) Math.max(-32767, Math.min(32767, v * 32767));
            }
            hs.process(blk, blk.length, 1.0);
        }
        hs.pulse.update();
        assertEquals("пульс по звуку", bpm, hs.pulse.bpm, 3.0);
    }

    /** Кадры с полосами от лампы, мигающей f Гц: каждая строка читается на rowNs позже предыдущей. */
    private static FlickerAnalyzer flicker(double f, double depth) {
        int w = 320;
        int h = 240;
        double rowNs = 60_000; // 14.4 мс на кадр
        FlickerAnalyzer fa = new FlickerAnalyzer(h);
        Random r = new Random(5);
        byte[] y = new byte[w * h];
        for (int fr = 0; fr < 40; fr++) {
            double t0 = fr / 30.0;
            for (int row = 0; row < h; row++) {
                double t = t0 + row * rowNs * 1e-9;
                double light = 1 + depth * Math.sin(2 * Math.PI * f * t);
                for (int x = 0; x < w; x++) {
                    double scene = 60 + 50 * ((x / 40 + row / 30) % 2) + 0.2 * row; // клетки + градиент
                    double v = scene * light + r.nextGaussian() * 2;
                    y[row * w + x] = (byte) Math.max(0, Math.min(255, (int) v));
                }
            }
            fa.addFrame(y, w, rowNs);
        }
        return fa;
    }

    @Test
    public void lampFlickerFrequency() {
        FlickerAnalyzer fa = flicker(100, 0.10);
        assertEquals("частота мерцания", 100, fa.hz, 5);
        assertTrue("глубина " + fa.modulation, fa.modulation > 0.05 && fa.modulation < 0.15);
        FlickerAnalyzer pwm = flicker(400, 0.05);
        assertEquals("ШИМ-подсветка", 400, pwm.hz, 15);
    }

    @Test
    public void steadyLightHasNoFlicker() {
        FlickerAnalyzer fa = flicker(100, 0.0);
        assertTrue("ложная частота " + fa.hz, Double.isNaN(fa.hz));
    }

    @Test
    public void magnifierAmplifiesTinyChanges() {
        MotionMagnifier mm = new MotionMagnifier(4, 4);
        mm.alpha = 30;
        byte[] in = new byte[16];
        byte[] out = new byte[16];
        double fs = 30;
        int mn = 255;
        int mx = 0;
        for (int i = 0; i < 300; i++) {
            double v = 120 + 0.8 * Math.sin(2 * Math.PI * 1.0 * i / fs);
            for (int k = 0; k < 16; k++) {
                in[k] = (byte) (int) Math.round(v);
            }
            mm.process(in, fs, out);
            if (i > 150) {
                mn = Math.min(mn, out[0] & 0xFF);
                mx = Math.max(mx, out[0] & 0xFF);
            }
        }
        // на входе размах ~2 единицы яркости, на выходе — в разы больше
        assertTrue("размах " + (mx - mn), mx - mn > 15);
    }

    @Test
    public void vibrationPeak() {
        VibrationAnalyzer va = new VibrationAnalyzer();
        double fs = 400;
        for (int i = 0; i < 1500; i++) {
            double t = i / fs;
            float a = (float) (9.81 + 0.3 * Math.sin(2 * Math.PI * 50 * t));
            va.add((long) (t * 1e9), a);
        }
        assertTrue(va.update());
        assertEquals("частота", 50, va.peakHz, 1.0);
        assertEquals("амплитуда", 0.3, va.peakAmp, 0.06);
        // скорость: 0.3 / (2π·50) / √2 = 0.675 мм/с
        assertEquals("скорость", 0.675, va.velocityMmS, 0.15);
    }

    @Test
    public void magneticMapFindsWire() {
        MagScan ms = new MagScan();
        for (int row = 0; row < 5; row++) {
            ms.startRow();
            for (int i = 0; i < 200; i++) {
                double x = i / 199.0;
                double field = 48 + 15 * Math.exp(-Math.pow((x - 0.5) / 0.05, 2)); // провод посередине
                ms.add((float) field);
            }
            assertTrue(ms.endRow());
        }
        float[][] a = ms.anomalies();
        assertEquals(5, a.length);
        int best = 0;
        for (int c = 0; c < MagScan.COLS; c++) {
            if (a[2][c] > a[2][best]) {
                best = c;
            }
        }
        assertTrue("столбец " + best, Math.abs(best - MagScan.COLS / 2) <= 1);
        ms.startRow();
        ms.add(1f);
        assertTrue("короткая строка не сохраняется", !ms.endRow());
    }
}
