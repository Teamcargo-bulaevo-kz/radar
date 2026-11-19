package kz.bulaevo.radar.dsp;

import java.util.Arrays;

/**
 * Полярная карта эха: по углу (куда смотрит телефон) и по дальности.
 * Каждый профиль эха "закрашивается" на текущем азимуте; при вращении телефона карта накапливается,
 * и проявляются стены и предметы вокруг. По карте строится контур — ближайшее заметное эхо по каждому углу.
 */
public final class RoomMap {
    public final int angleBins;
    public final int rangeBins;
    /** Яркость ячеек 0..1, индекс = angle * rangeBins + range. */
    public final float[] cells;
    /** Дальность ближайшего эха по каждому углу (NaN — нет данных). */
    public final float[] outlineR;

    private double maxRange = 7.0;
    private final float[] v;
    private volatile int version;

    public RoomMap(int angleBins, int rangeBins) {
        this.angleBins = angleBins;
        this.rangeBins = rangeBins;
        this.cells = new float[angleBins * rangeBins];
        this.outlineR = new float[angleBins];
        this.v = new float[rangeBins];
        Arrays.fill(outlineR, Float.NaN);
    }

    public synchronized void setMaxRange(double meters) {
        if (Math.abs(meters - maxRange) > 1e-6) {
            maxRange = meters;
            reset();
        }
    }

    public synchronized double maxRange() {
        return maxRange;
    }

    public synchronized void reset() {
        Arrays.fill(cells, 0f);
        Arrays.fill(outlineR, Float.NaN);
        version++;
    }

    /** Меняется при каждом обновлении — удобно, чтобы знать, пора ли перерисовать. */
    public int version() {
        return version;
    }

    public int angleIndex(double deg) {
        double d = deg % 360.0;
        if (d < 0) {
            d += 360.0;
        }
        int a = (int) (d / 360.0 * angleBins);
        return Math.min(a, angleBins - 1);
    }

    /**
     * Добавить профиль на азимуте.
     *
     * @param azimuthDeg угол, градусы
     * @param lowDb      с какого уровня (дБ над шумом) начинается свечение
     * @param highDb     уровень полной яркости
     * @param spreadDeg  угловая ширина "мазка" (луч динамика широкий)
     */
    public synchronized void update(double azimuthDeg, SonarFrame f, double lowDb, double highDb, double spreadDeg) {
        Arrays.fill(v, 0f);
        final double span = Math.max(1.0, highDb - lowDb);
        final int mMin = Math.max(1, (int) Math.ceil((SonarProcessor.MIN_RANGE_M - f.rangeOff) / f.binM));
        for (int m = mMin; m < f.bins; m++) {
            double r = f.rangeOf(m);
            int k = (int) (r / maxRange * rangeBins);
            if (k >= rangeBins) {
                break;
            }
            double x = (f.snrDb[m] - lowDb) / span;
            if (x > v[k]) {
                v[k] = (float) Math.min(1.0, x);
            }
        }
        final double binDeg = 360.0 / angleBins;
        final double sigma = Math.max(binDeg, spreadDeg / 2.0);
        final int reach = (int) Math.ceil(2.0 * sigma / binDeg);
        final int a0 = angleIndex(azimuthDeg);
        for (int d = -reach; d <= reach; d++) {
            double dd = d * binDeg / sigma;
            float w = (float) Math.exp(-dd * dd);
            int a = ((a0 + d) % angleBins + angleBins) % angleBins;
            int base = a * rangeBins;
            for (int k = 0; k < rangeBins; k++) {
                float c = cells[base + k];
                float t = v[k];
                float rate = (t > c ? 0.5f : 0.04f) * w;
                cells[base + k] = c + rate * (t - c);
            }
        }
        version++;
    }

    /** Медленное общее забывание (вызывать редко). */
    public synchronized void decayAll(float factor) {
        for (int i = 0; i < cells.length; i++) {
            cells[i] *= factor;
        }
        version++;
    }

    /** Построить контур: по каждому углу берём ближайшую ячейку ярче порога. */
    public synchronized void computeOutline(float threshold, double minRange) {
        final int kMin = (int) Math.max(0, Math.floor(minRange / maxRange * rangeBins));
        for (int a = 0; a < angleBins; a++) {
            int base = a * rangeBins;
            float found = Float.NaN;
            for (int k = kMin; k < rangeBins; k++) {
                float c = cells[base + k];
                if (c >= threshold) {
                    // уточняем до локального максимума рядом
                    int best = k;
                    float bv = c;
                    for (int j = k + 1; j < Math.min(rangeBins, k + 4); j++) {
                        if (cells[base + j] > bv) {
                            bv = cells[base + j];
                            best = j;
                        }
                    }
                    found = (float) ((best + 0.5) / rangeBins * maxRange);
                    break;
                }
            }
            outlineR[a] = found;
        }
    }
}
