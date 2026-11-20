package kz.bulaevo.radar.dsp;

/**
 * Звук сердца с микрофона (фонокардиограмма): из 48 кГц оставляем полосу 25–150 Гц, где лежат тоны сердца,
 * строим огибающую (громкость ударов) и по ней считаем пульс. Это не ЭКГ: ЭКГ меряет электричество сердца,
 * здесь — только звук.
 */
public final class HeartSound {
    public static final int FS_IN = 48000;
    /** Частота после прореживания: 2 кГц. */
    public static final int DEC = 24;
    public static final int FS = FS_IN / DEC;
    /** Огибающая для оценки пульса: 100 Гц. */
    private static final int ENV_DEC = FS / 100;

    private final Biquad aa1 = Biquad.lowpass(FS_IN, 400, 0.707);
    private final Biquad aa2 = Biquad.lowpass(FS_IN, 400, 0.707);
    private final Biquad hp1 = Biquad.highpass(FS, 25, 0.707);
    private final Biquad hp2 = Biquad.highpass(FS, 25, 0.707);
    private final Biquad lp1 = Biquad.lowpass(FS, 150, 0.707);
    private final Biquad lp2 = Biquad.lowpass(FS, 150, 0.707);
    private final Biquad env = Biquad.lowpass(FS, 8, 0.707);

    public final PulseEstimator pulse = new PulseEstimator(8.0);

    /** Последние 5 секунд отфильтрованного звука для графика (200 точек в секунду). */
    public static final int VIEW_FS = 200;
    public final float[] view = new float[VIEW_FS * 5];
    private int viewPos;
    private int viewCount;

    /** Отфильтрованный звук на частоте FS — для прослушивания в наушниках. */
    public final short[] listen = new short[8192];
    public int listenN;

    private int decPhase;
    private int envPhase;
    private int viewPhase;
    private float viewPeak;
    private long samples;
    public double level;

    public void reset() {
        pulse.reset();
        samples = 0;
        viewCount = 0;
        viewPos = 0;
    }

    /** Обработать блок с микрофона. gain — усиление для прослушивания. */
    public void process(short[] pcm, int len, double gain) {
        listenN = 0;
        double sumSq = 0;
        int cnt = 0;
        for (int i = 0; i < len; i++) {
            double x = aa2.process(aa1.process(pcm[i] / 32768.0));
            if (++decPhase < DEC) {
                continue;
            }
            decPhase = 0;
            double y = lp2.process(lp1.process(hp2.process(hp1.process(x))));
            sumSq += y * y;
            cnt++;
            if (listenN < listen.length) {
                double o = y * gain * 32767.0;
                listen[listenN++] = (short) Math.max(-32767, Math.min(32767, o));
            }
            double e = env.process(Math.abs(y));
            if (Math.abs(y) > Math.abs(viewPeak)) {
                viewPeak = (float) y;
            }
            if (++viewPhase >= FS / VIEW_FS) {
                viewPhase = 0;
                view[viewPos] = viewPeak;
                viewPos = (viewPos + 1) % view.length;
                viewCount++;
                viewPeak = 0;
            }
            if (++envPhase >= ENV_DEC) {
                envPhase = 0;
                samples += ENV_DEC;
                pulse.add(samples * 1000L / FS, e);
            }
        }
        if (cnt > 0) {
            level = Math.sqrt(sumSq / cnt);
        }
    }

    /** Скопировать окно графика по порядку (старые → новые). Возвращает число точек. */
    public int viewOrdered(float[] out) {
        int n = Math.min(viewCount, view.length);
        for (int i = 0; i < n; i++) {
            out[i] = view[(viewPos - n + i + view.length * 2) % view.length];
        }
        return n;
    }
}
