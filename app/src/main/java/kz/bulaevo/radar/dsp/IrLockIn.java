package kz.bulaevo.radar.dsp;

/**
 * Синхронное детектирование ИК-подсветки (lock-in): подсветка циклами включается и выключается,
 * кадры копятся отдельно «со светом» и «без света». Разность «свет − темнота» по каждому циклу
 * убирает весь постоянный свет; разброс разностей между циклами даёт шум. Сигнал/шум (t) по
 * каждому блоку кадра показывает, куда попал ИК-свет и видит ли его камера вообще.
 */
public final class IrLockIn {
    /** Размер блока в пикселях: усреднение внутри блока снижает шум матрицы. */
    public static final int BLOCK = 8;

    public final int gw;
    public final int gh;
    private final int w;
    private final int h;

    private final float[] onSum;
    private final float[] offSum;
    private int onFrames;
    private int offFrames;
    private int cycle = -1;

    private final double[] sumD;
    private final double[] sumD2;
    private int cycles;

    /** Сигнал/шум по блокам после последнего завершённого цикла. */
    public final float[] t;
    /** Средняя разность яркости «ИК вкл − выкл» по блокам (единицы яркости 0..255). */
    public final float[] diff;
    private final double[] varTmp;
    private final double[] sorted;
    public float maxT;
    public float maxDiff;
    /** Сколько блоков с уверенным сигналом (t ≥ 4). */
    public int strongBlocks;

    public IrLockIn(int w, int h) {
        this.w = w;
        this.h = h;
        gw = Math.max(1, w / BLOCK);
        gh = Math.max(1, h / BLOCK);
        int n = gw * gh;
        onSum = new float[n];
        offSum = new float[n];
        sumD = new double[n];
        sumD2 = new double[n];
        t = new float[n];
        diff = new float[n];
        varTmp = new double[n];
        sorted = new double[n];
    }

    public int cycles() {
        return cycles;
    }

    public void reset() {
        java.util.Arrays.fill(onSum, 0f);
        java.util.Arrays.fill(offSum, 0f);
        java.util.Arrays.fill(sumD, 0.0);
        java.util.Arrays.fill(sumD2, 0.0);
        java.util.Arrays.fill(t, 0f);
        java.util.Arrays.fill(diff, 0f);
        onFrames = 0;
        offFrames = 0;
        cycle = -1;
        cycles = 0;
        maxT = 0f;
        maxDiff = 0f;
        strongBlocks = 0;
    }

    /**
     * Добавить кадр яркости. phase: 1 — ИК горел весь кадр, -1 — ИК не горел, 0 — переход (кадр пропускается).
     * cycleIndex растёт на 1 с каждым новым циклом «вкл+выкл».
     */
    public void addFrame(byte[] y, int phase, int cycleIndex) {
        if (cycleIndex != cycle) {
            finishCycle();
            cycle = cycleIndex;
        }
        if (phase == 0) {
            return;
        }
        float[] acc = phase > 0 ? onSum : offSum;
        for (int by = 0; by < gh; by++) {
            int y0 = by * BLOCK;
            for (int bx = 0; bx < gw; bx++) {
                int x0 = bx * BLOCK;
                int s = 0;
                for (int yy = 0; yy < BLOCK; yy++) {
                    int row = (y0 + yy) * w + x0;
                    for (int xx = 0; xx < BLOCK; xx++) {
                        s += y[row + xx] & 0xFF;
                    }
                }
                acc[by * gw + bx] += s / (float) (BLOCK * BLOCK);
            }
        }
        if (phase > 0) {
            onFrames++;
        } else {
            offFrames++;
        }
    }

    private void finishCycle() {
        if (onFrames > 0 && offFrames > 0) {
            int n = gw * gh;
            for (int i = 0; i < n; i++) {
                varTmp[i] = onSum[i] / onFrames - offSum[i] / offFrames;
            }
            // Общее изменение яркости всего кадра (лампа, остатки автоэкспозиции) — не ИК-пятно: вычитаем медиану.
            System.arraycopy(varTmp, 0, sorted, 0, n);
            java.util.Arrays.sort(sorted);
            double common = sorted[n / 2];
            for (int i = 0; i < n; i++) {
                double d = varTmp[i] - common;
                sumD[i] += d;
                sumD2[i] += d * d;
            }
            cycles++;
            update();
        }
        java.util.Arrays.fill(onSum, 0f);
        java.util.Arrays.fill(offSum, 0f);
        onFrames = 0;
        offFrames = 0;
    }

    private void update() {
        int n = gw * gh;
        maxT = 0f;
        maxDiff = 0f;
        strongBlocks = 0;
        if (cycles < 2) {
            return;
        }
        // Дисперсия разности по каждому блоку. Циклов мало, поэтому своя оценка шума у блока ненадёжна:
        // берём не меньше медианной по кадру (шум матрицы почти одинаков по кадру), а там, где дрожание руки
        // сдвигает границы предметов, своя дисперсия больше — она и работает.
        for (int i = 0; i < n; i++) {
            double mean = sumD[i] / cycles;
            diff[i] = (float) mean;
            varTmp[i] = Math.max(0.0, (sumD2[i] - cycles * mean * mean) / (cycles - 1));
        }
        System.arraycopy(varTmp, 0, sorted, 0, n);
        java.util.Arrays.sort(sorted);
        double med = sorted[n / 2] + 0.0004; // + шум квантования
        for (int i = 0; i < n; i++) {
            double se = Math.sqrt(Math.max(varTmp[i], med) / cycles);
            float tv = (float) (diff[i] / se);
            t[i] = tv;
            if (tv > maxT) {
                maxT = tv;
            }
            if (tv >= 4f) {
                strongBlocks++;
                if (diff[i] > maxDiff) {
                    maxDiff = diff[i];
                }
            }
        }
    }

    /** Итог: 1 — ИК виден уверенно, 0 — пока не ясно, -1 — после достаточного числа циклов ИК не найден. */
    public int verdict() {
        if (cycles >= 4 && maxT >= 6f && strongBlocks >= 3) {
            return 1;
        }
        if (cycles >= 8) {
            return -1;
        }
        return 0;
    }

    public int width() {
        return w;
    }

    public int height() {
        return h;
    }
}
