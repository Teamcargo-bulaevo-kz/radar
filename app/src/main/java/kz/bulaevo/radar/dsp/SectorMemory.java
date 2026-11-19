package kz.bulaevo.radar.dsp;

import java.util.Arrays;

/**
 * Короткая память эха по направлениям (сектора по 5°) для ходьбы: пока вы водите телефоном
 * влево-вправо, здесь копится «где что близко и где свободно». Записи живут несколько секунд —
 * при ходьбе обстановка быстро меняется, старое надо забывать.
 */
public final class SectorMemory {
    public static final int N = 72;
    public static final double SECTOR_DEG = 360.0 / N;

    private final float[] r = new float[N];
    private final long[] t = new long[N];
    private long ttlMs = 4000;

    public SectorMemory() {
        reset();
    }

    public synchronized void reset() {
        Arrays.fill(r, Float.NaN);
        Arrays.fill(t, 0L);
    }

    public synchronized void setTtlMs(long ms) {
        ttlMs = Math.max(500, ms);
    }

    private static int idx(double deg) {
        double d = deg % 360.0;
        if (d < 0) {
            d += 360.0;
        }
        return Math.min(N - 1, (int) (d / SECTOR_DEG));
    }

    /**
     * Записать измерение по направлению азимута.
     *
     * @param nearestM    ближайшее эхо, м; NaN — эха нет, то есть до maxRangeM свободно
     * @param spreadDeg   ширина луча динамика: столько градусов в каждую сторону закрашивается
     */
    public synchronized void update(double azDeg, float nearestM, double maxRangeM, long nowMs, double spreadDeg) {
        final float x = Float.isNaN(nearestM) ? (float) maxRangeM : Math.min(nearestM, (float) maxRangeM);
        final int reach = (int) Math.round(spreadDeg / SECTOR_DEG);
        final int a0 = idx(azDeg);
        for (int d = -reach; d <= reach; d++) {
            int a = ((a0 + d) % N + N) % N;
            boolean fresh = t[a] != 0 && nowMs - t[a] <= ttlMs && !Float.isNaN(r[a]);
            if (!fresh || x < r[a]) {
                r[a] = x; // препятствие запоминается сразу, при пропаже сначала даём «остыть»
            } else if (nowMs - t[a] > 1000) {
                r[a] = x;
            } else {
                r[a] += 0.15f * (x - r[a]);
            }
            t[a] = nowMs;
        }
    }

    private boolean known(int a, long nowMs) {
        return t[a] != 0 && nowMs - t[a] <= ttlMs && !Float.isNaN(r[a]);
    }

    /** Дальность в направлении; NaN, если направление давно не измерялось. */
    public synchronized float rangeAt(double azDeg, long nowMs) {
        int a = idx(azDeg);
        return known(a, nowMs) ? r[a] : Float.NaN;
    }

    /** Ближайшее в конусе ±halfWidthDeg вокруг азимута; NaN, если там ничего не известно. */
    public synchronized float nearestInCone(double azDeg, double halfWidthDeg, long nowMs) {
        float best = Float.NaN;
        int reach = (int) Math.ceil(halfWidthDeg / SECTOR_DEG);
        int a0 = idx(azDeg);
        for (int d = -reach; d <= reach; d++) {
            int a = ((a0 + d) % N + N) % N;
            if (known(a, nowMs) && (Float.isNaN(best) || r[a] < best)) {
                best = r[a];
            }
        }
        return best;
    }

    /**
     * Куда свободнее всего: направление относительно курса (-limit..+limit, + это вправо), где ближайшее
     * препятствие в окне шириной ±windowDeg дальше всего. Направления, измеренные меньше чем наполовину, не берутся.
     * Прямо вперёд слегка предпочтительнее.
     *
     * @param outRange out[0] — запас свободного расстояния в выбранном направлении
     * @return угол относительно курса или NaN, если данных мало
     */
    public synchronized double clearestDirection(double headingDeg, double limitDeg, double windowDeg,
                                                 long nowMs, float[] outRange) {
        double bestAngle = Double.NaN;
        double bestScore = -1e9;
        float bestRange = Float.NaN;
        final int w = (int) Math.ceil(windowDeg / SECTOR_DEG);
        for (double rel = -limitDeg; rel <= limitDeg + 1e-9; rel += SECTOR_DEG) {
            int a0 = idx(headingDeg + rel);
            int knownCount = 0;
            float worst = Float.MAX_VALUE;
            for (int d = -w; d <= w; d++) {
                int a = ((a0 + d) % N + N) % N;
                if (known(a, nowMs)) {
                    knownCount++;
                    worst = Math.min(worst, r[a]);
                }
            }
            if (knownCount * 2 < (2 * w + 1)) {
                continue;
            }
            double score = worst - Math.abs(rel) * 0.004;
            if (score > bestScore) {
                bestScore = score;
                bestAngle = rel;
                bestRange = worst;
            }
        }
        if (outRange != null && outRange.length > 0) {
            outRange[0] = bestRange;
        }
        return bestAngle;
    }
}
