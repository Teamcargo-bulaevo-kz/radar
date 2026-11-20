package kz.bulaevo.radar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Random;

import kz.bulaevo.radar.dsp.IrLockIn;

import org.junit.Test;

/** Синхронный детектор ИК: находит слабое пятно подсветки под шумом и не выдумывает его, когда пятна нет. */
public class IrLockInTest {
    private static final int W = 160;
    private static final int H = 120;

    /** Сцена: фон, шум матрицы ±6, медленный дрейф яркости, при включённом ИК — пятно +amp в правом верхнем углу. */
    private static IrLockIn run(double amp, long seed) {
        Random rnd = new Random(seed);
        IrLockIn li = new IrLockIn(W, H);
        byte[] y = new byte[W * H];
        int frame = 0;
        for (int cycle = 0; cycle < 10; cycle++) {
            for (int k = 0; k < 16; k++) {
                int phase = k < 2 || (k >= 8 && k < 10) ? 0 : (k < 8 ? 1 : -1);
                boolean irOn = k < 8;
                double drift = 4 * Math.sin(frame * 0.05);
                for (int py = 0; py < H; py++) {
                    for (int px = 0; px < W; px++) {
                        double v = 40 + 0.2 * px + drift + rnd.nextGaussian() * 6;
                        if (irOn && px > 100 && py < 40) {
                            v += amp;
                        }
                        y[py * W + px] = (byte) Math.max(0, Math.min(255, (int) Math.round(v)));
                    }
                }
                li.addFrame(y, phase, cycle);
                frame++;
            }
        }
        li.addFrame(y, 0, 10); // закрыть последний цикл
        return li;
    }

    @Test
    public void findsWeakIrSpot() {
        IrLockIn li = run(1.5, 1); // пятно в 1.5 единицы яркости: на экране его не видно
        assertEquals("maxT=" + li.maxT + " strong=" + li.strongBlocks, 1, li.verdict());
        // сильнее всего — в зоне пятна
        int best = 0;
        for (int i = 1; i < li.t.length; i++) {
            if (li.t[i] > li.t[best]) {
                best = i;
            }
        }
        int bx = best % li.gw;
        int by = best / li.gw;
        assertTrue("bx=" + bx + " by=" + by, bx * IrLockIn.BLOCK >= 96 && by * IrLockIn.BLOCK < 40);
    }

    @Test
    public void noFalseAlarmWithoutIr() {
        for (long seed = 1; seed <= 5; seed++) {
            IrLockIn li = run(0.0, seed);
            assertEquals("seed " + seed + " maxT=" + li.maxT + " strong=" + li.strongBlocks, -1, li.verdict());
        }
    }
}
