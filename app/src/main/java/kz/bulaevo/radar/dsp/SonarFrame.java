package kz.bulaevo.radar.dsp;

/**
 * Результат обработки одного окна: профиль эха по дальности. Массивы переиспользуются —
 * получатель обязан скопировать нужное внутри вызова обратной связи.
 */
public final class SonarFrame {
    public static final int MAX_PEAKS = 8;

    /** Число отсчётов дальности. */
    public final int bins;
    /** Метров на один отсчёт. */
    public final float binM;
    /** Дальность нулевого отсчёта (поправка на расстояние динамик–микрофон). */
    public final float rangeOff;

    /** Сила эха по дальности, дБ над шумом. */
    public final float[] snrDb;
    /** Сила изменений (движения) по дальности, дБ над шумом. */
    public final float[] motionDb;

    public int peakCount;
    public final float[] peakR = new float[MAX_PEAKS];
    public final float[] peakDb = new float[MAX_PEAKS];

    /** Ближайший объект, м (NaN — нет). */
    public float nearestM = Float.NaN;
    public float nearestDb;

    /** Прямой сигнал динамик→микрофон над шумом, дБ: показатель качества канала. */
    public float quality;
    /** Максимум движения и его дальность. */
    public float motionScore;
    public float motionRangeM = Float.NaN;

    /** Идёт прогрев/захват. */
    public boolean warming = true;
    /** Микрофон перегружен. */
    public boolean clipped;
    public long index;

    public SonarFrame(int bins, float binM, float rangeOff) {
        this.bins = bins;
        this.binM = binM;
        this.rangeOff = rangeOff;
        this.snrDb = new float[bins];
        this.motionDb = new float[bins];
    }

    public float rangeOf(double bin) {
        return (float) (rangeOff + bin * binM);
    }

    public float maxRange() {
        return rangeOf(bins - 1);
    }

    public void copyFrom(SonarFrame o) {
        System.arraycopy(o.snrDb, 0, snrDb, 0, Math.min(bins, o.bins));
        System.arraycopy(o.motionDb, 0, motionDb, 0, Math.min(bins, o.bins));
        peakCount = o.peakCount;
        System.arraycopy(o.peakR, 0, peakR, 0, MAX_PEAKS);
        System.arraycopy(o.peakDb, 0, peakDb, 0, MAX_PEAKS);
        nearestM = o.nearestM;
        nearestDb = o.nearestDb;
        quality = o.quality;
        motionScore = o.motionScore;
        motionRangeM = o.motionRangeM;
        warming = o.warming;
        clipped = o.clipped;
        index = o.index;
    }
}
