package kz.bulaevo.radar.dsp;

/**
 * Зондирующий сигнал: периодический линейный чирп (частота плавно растёт от f0 до f1 за период,
 * затем всё повторяется), края периода плавно сведены в ноль окном Таки.
 * <p>
 * Почему так:
 * <ul>
 *   <li>Каждая частота проходится один раз за период — спектр ровный (в отличие от "вверх-вниз",
 *       где две встречи с частотой интерферируют и дают гребёнку провалов).</li>
 *   <li>Амплитуда на стыке периодов равна нулю — нет щелчков и рассыпания спектра.</li>
 *   <li>Сигнал почти непрерывный — максимум энергии, максимум чувствительности.</li>
 *   <li>Период ровно n отсчётов, поэтому приёмник может обрабатывать любое окно из n отсчётов
 *       (циклическая деконволюция), а дальность однозначна до c*n/(2*fs).</li>
 * </ul>
 */
public final class ChirpDesign {
    /** Доля периода, занятая плавным входом (и такая же — выходом). */
    public static final double TAPER = 0.12;

    public final double fs;
    public final int n;
    /** Реальная центральная частота (привязана к сетке БПФ, это нужно для периодичности фазы). */
    public final double fc;
    public final double bw;
    public final double f0;
    public final double f1;
    /** Номер центрального бина БПФ. */
    public final int kc;
    /** Один период сигнала, амплитуда 1.0. */
    public final double[] samples;
    private final double period;

    public ChirpDesign(double fs, int n, double fcRequested, double bwRequested) {
        this.fs = fs;
        this.n = n;
        this.period = n / fs;
        double grid = fs / n;
        double bwClamped = Math.max(500.0, bwRequested);
        // центр на сетке БПФ
        int k = (int) Math.round(fcRequested / grid);
        double c = k * grid;
        // не выходим за пределы 150 Гц .. 0.485*fs
        double maxHalf = Math.min(c - 150.0, 0.485 * fs - c);
        double half = Math.min(bwClamped / 2.0, Math.max(250.0, maxHalf));
        this.kc = k;
        this.fc = c;
        this.bw = 2.0 * half;
        this.f0 = c - half;
        this.f1 = c + half;
        this.samples = new double[n];
        for (int i = 0; i < n; i++) {
            samples[i] = value(i / fs);
        }
    }

    /** Значение сигнала в произвольный момент времени t (секунды), с периодом n/fs. Нужен и для симуляции. */
    public double value(double t) {
        double tt = t % period;
        if (tt < 0) {
            tt += period;
        }
        double u = tt / period;
        double w;
        if (u < TAPER) {
            w = 0.5 * (1.0 - Math.cos(Math.PI * u / TAPER));
        } else if (u > 1.0 - TAPER) {
            w = 0.5 * (1.0 - Math.cos(Math.PI * (1.0 - u) / TAPER));
        } else {
            w = 1.0;
        }
        double rate = bw / period; // Гц/с
        double phase = f0 * tt + 0.5 * rate * tt * tt;
        return w * Math.sin(2.0 * Math.PI * phase);
    }

    /** Минимальный и максимальный номера бинов, занятых полосой сигнала. */
    public int binLo() {
        return (int) Math.floor(f0 * n / fs);
    }

    public int binHi() {
        return (int) Math.ceil(f1 * n / fs);
    }
}
