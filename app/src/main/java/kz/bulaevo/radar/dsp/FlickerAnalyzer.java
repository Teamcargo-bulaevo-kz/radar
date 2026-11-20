package kz.bulaevo.radar.dsp;

/**
 * Мерцание ламп по «полосам» на кадре. Строки матрицы считываются по очереди (rolling shutter), поэтому
 * свет, мигающий 100+ раз в секунду, оставляет на кадре горизонтальные полосы. Профиль яркости по строкам
 * делится на свой средний (так уходит сама картинка), к остатку подгоняется синусоида на сетке частот
 * (точнее обычного спектра, когда полос на кадре всего 1–3), а время чтения строки переводит её в герцы.
 */
public final class FlickerAnalyzer {
    private static final double C_MIN = 0.8;
    private static final double LIN_STEP = 0.02;
    private static final double LIN_UNTIL = 5.0;
    private static final double LOG_STEP = 1.01;

    private final int h;
    private final double[] prof;
    private final double[] avg;
    private final double[] res;
    /** Частоты сетки, полос на кадр. */
    public final double[] cycles;
    /** Средняя амплитуда модуляции (доли) по сетке частот. */
    public final double[] spec;
    private int frames;

    public double modulation;
    public double cyclesPerFrame;
    public double hz = Double.NaN;

    public FlickerAnalyzer(int frameH) {
        h = frameH;
        prof = new double[frameH];
        avg = new double[frameH];
        res = new double[frameH];
        double cMax = frameH / 4.0;
        java.util.List<Double> cs = new java.util.ArrayList<>();
        for (double c = C_MIN; c <= cMax; c = c < LIN_UNTIL ? c + LIN_STEP : c * LOG_STEP) {
            cs.add(c);
        }
        cycles = new double[cs.size()];
        for (int i = 0; i < cycles.length; i++) {
            cycles[i] = cs.get(i);
        }
        spec = new double[cycles.length];
    }

    public void reset() {
        frames = 0;
        java.util.Arrays.fill(spec, 0);
        hz = Double.NaN;
        modulation = 0;
    }

    /**
     * Кадр яркости (строки — как читает матрица). rowTimeNs — время между чтением соседних строк этого кадра;
     * 0, если неизвестно.
     */
    public void addFrame(byte[] y, int w, double rowTimeNs) {
        int step = Math.max(1, w / 160);
        for (int r = 0; r < h; r++) {
            int s = 0;
            int c = 0;
            for (int x = 0; x < w; x += step) {
                s += y[r * w + x] & 0xFF;
                c++;
            }
            prof[r] = s / (double) c;
        }
        // средний профиль — это сама картинка; полосы двигаются от кадра к кадру и в нём размываются
        double a = frames == 0 ? 1.0 : 0.08;
        for (int r = 0; r < h; r++) {
            avg[r] += a * (prof[r] - avg[r]);
        }
        double mean = 0;
        int good = 0;
        for (int r = 0; r < h; r++) {
            if (avg[r] > 4) {
                mean += prof[r] / avg[r] - 1;
                good++;
            }
        }
        mean = good > 0 ? mean / good : 0;
        for (int r = 0; r < h; r++) {
            res[r] = avg[r] > 4 ? prof[r] / avg[r] - 1 - mean : 0;
        }
        double k2 = frames < 10 ? 1.0 / (frames + 1) : 0.1;
        for (int i = 0; i < cycles.length; i++) {
            spec[i] += k2 * (fitAmplitude(cycles[i]) - spec[i]);
        }
        frames++;

        int best = -1;
        double bv = 0;
        double floor = 0;
        for (int i = 0; i < spec.length; i++) {
            floor += spec[i];
            if (spec[i] > bv) {
                bv = spec[i];
                best = i;
            }
        }
        floor /= spec.length;
        modulation = bv;
        if (best < 0 || frames < 5 || bv < 3 * floor || bv < 0.004) {
            cyclesPerFrame = 0;
            hz = Double.NaN;
            return;
        }
        double c = cycles[best];
        if (best > 0 && best < spec.length - 1) {
            double y0 = spec[best - 1];
            double y1 = spec[best];
            double y2 = spec[best + 1];
            double den = y0 - 2 * y1 + y2;
            if (den < 0) {
                double off = Math.max(-0.5, Math.min(0.5, 0.5 * (y0 - y2) / den));
                c = off >= 0 ? c + off * (cycles[best + 1] - c) : c + off * (c - cycles[best - 1]);
            }
        }
        cyclesPerFrame = c;
        hz = rowTimeNs > 0 ? (c / h) / (rowTimeNs * 1e-9) : Double.NaN;
    }

    /** Амплитуда синусоиды c полос на кадр, подогнанной по МНК (с учётом неортогональности на малом числе периодов). */
    private double fitAmplitude(double c) {
        double w = 2 * Math.PI * c / h;
        double cw = Math.cos(w);
        double sw = Math.sin(w);
        double cr = 1;
        double sr = 0;
        double scc = 0;
        double sss = 0;
        double scs = 0;
        double syc = 0;
        double sys = 0;
        for (int r = 0; r < h; r++) {
            double v = res[r];
            scc += cr * cr;
            sss += sr * sr;
            scs += cr * sr;
            syc += v * cr;
            sys += v * sr;
            double nc = cr * cw - sr * sw;
            sr = sr * cw + cr * sw;
            cr = nc;
        }
        double det = scc * sss - scs * scs;
        if (Math.abs(det) < 1e-9) {
            return 0;
        }
        double ac = (syc * sss - sys * scs) / det;
        double as = (sys * scc - syc * scs) / det;
        return Math.hypot(ac, as);
    }
}
