package kz.bulaevo.radar.dsp;

/** Цифровой фильтр второго порядка (формулы RBJ): ФНЧ, ФВЧ, полосовой. */
public final class Biquad {
    private final double b0;
    private final double b1;
    private final double b2;
    private final double a1;
    private final double a2;
    private double x1;
    private double x2;
    private double y1;
    private double y2;

    private Biquad(double b0, double b1, double b2, double a0, double a1, double a2) {
        this.b0 = b0 / a0;
        this.b1 = b1 / a0;
        this.b2 = b2 / a0;
        this.a1 = a1 / a0;
        this.a2 = a2 / a0;
    }

    public static Biquad lowpass(double fs, double f, double q) {
        double w = 2 * Math.PI * f / fs;
        double cs = Math.cos(w);
        double al = Math.sin(w) / (2 * q);
        return new Biquad((1 - cs) / 2, 1 - cs, (1 - cs) / 2, 1 + al, -2 * cs, 1 - al);
    }

    public static Biquad highpass(double fs, double f, double q) {
        double w = 2 * Math.PI * f / fs;
        double cs = Math.cos(w);
        double al = Math.sin(w) / (2 * q);
        return new Biquad((1 + cs) / 2, -(1 + cs), (1 + cs) / 2, 1 + al, -2 * cs, 1 - al);
    }

    public double process(double x) {
        double y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
        x2 = x1;
        x1 = x;
        y2 = y1;
        y1 = y;
        return y;
    }

    public void reset() {
        x1 = 0;
        x2 = 0;
        y1 = 0;
        y2 = 0;
    }
}
