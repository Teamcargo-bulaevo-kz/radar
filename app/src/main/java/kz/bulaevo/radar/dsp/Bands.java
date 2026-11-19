package kz.bulaevo.radar.dsp;

/** Готовые диапазоны зондирования и режимы дальности. */
public final class Bands {
    private Bands() {
    }

    public static final String[] NAMES = {
            "УЗ 18–22 кГц",
            "УЗ 17–21 кГц",
            "УЗ 16–20 кГц",
            "УЗ 15–19 кГц",
            "Звук 4–12 кГц",
            "Звук 2–18 кГц"
    };
    /** Центр полосы, Гц. */
    public static final double[] FC = {20000, 19000, 18000, 17000, 8000, 10000};
    /** Ширина полосы, Гц. */
    public static final double[] BW = {4000, 4000, 4000, 4000, 8000, 16000};
    /** Слышен ли сигнал человеку. */
    public static final boolean[] AUDIBLE = {false, false, false, false, true, true};

    public static final String[] RANGE_NAMES = {"3.5 м", "7 м", "14 м"};
    public static final int[] RANGE_N = {1024, 2048, 4096};
    /** Максимальная дальность на карте/графиках, м (чуть меньше однозначной c*n/(2*fs)). */
    public static final double[] RANGE_M = {3.5, 7.0, 14.0};

    public static final double FS = 48000.0;

    /** Разрешение по дальности (метры) для полосы: c / (2 * B), с учётом оконного взвешивания ~1.4. */
    public static double resolutionM(int band) {
        return 1.4 * SonarProcessor.C / (2.0 * BW[band]);
    }
}
