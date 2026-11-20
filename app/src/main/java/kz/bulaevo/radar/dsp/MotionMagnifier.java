package kz.bulaevo.radar.dsp;

/**
 * Усиление незаметных глазу изменений (упрощённый метод Eulerian video magnification): у каждой точки кадра
 * выделяются колебания яркости в нужной полосе частот (разность двух сглаживаний) и прибавляются обратно,
 * умноженные в alpha раз. Видно дыхание, пульс на коже, дрожание предметов.
 */
public final class MotionMagnifier {
    public final int w;
    public final int h;
    private final float[] fast;
    private final float[] slow;
    private boolean init;
    public float alpha = 30f;
    public double fLow = 0.4;
    public double fHigh = 3.0;
    /** Средняя сила усиленного колебания (для подсказки). */
    public float activity;

    public MotionMagnifier(int w, int h) {
        this.w = w;
        this.h = h;
        fast = new float[w * h];
        slow = new float[w * h];
    }

    public void reset() {
        init = false;
    }

    /** in — кадр яркости w×h (0..255), fs — текущая частота кадров, out — результат 0..255. */
    public void process(byte[] in, double fs, byte[] out) {
        int n = w * h;
        if (!init) {
            for (int i = 0; i < n; i++) {
                fast[i] = in[i] & 0xFF;
                slow[i] = fast[i];
                out[i] = in[i];
            }
            init = true;
            activity = 0;
            return;
        }
        double fh = Math.min(fHigh, fs * 0.45);
        float af = (float) (1 - Math.exp(-2 * Math.PI * fh / fs));
        float as = (float) (1 - Math.exp(-2 * Math.PI * fLow / fs));
        double act = 0;
        for (int i = 0; i < n; i++) {
            float x = in[i] & 0xFF;
            fast[i] += af * (x - fast[i]);
            slow[i] += as * (x - slow[i]);
            float band = fast[i] - slow[i];
            act += Math.abs(band);
            float o = x + alpha * band;
            out[i] = (byte) (o < 0 ? 0 : (o > 255 ? 255 : (int) o));
        }
        activity = (float) (act / n);
    }
}
