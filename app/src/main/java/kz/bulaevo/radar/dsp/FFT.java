package kz.bulaevo.radar.dsp;

/** Быстрое преобразование Фурье (радикс-2, на месте). Размер — степень двойки. */
public final class FFT {
    public final int n;
    private final double[] cosT;
    private final double[] sinT;
    private final int[] rev;

    public FFT(int n) {
        if (n < 2 || (n & (n - 1)) != 0) {
            throw new IllegalArgumentException("Размер БПФ должен быть степенью двойки: " + n);
        }
        this.n = n;
        int levels = Integer.numberOfTrailingZeros(n);
        cosT = new double[n / 2];
        sinT = new double[n / 2];
        for (int i = 0; i < n / 2; i++) {
            double a = 2.0 * Math.PI * i / n;
            cosT[i] = Math.cos(a);
            sinT[i] = Math.sin(a);
        }
        rev = new int[n];
        for (int i = 0; i < n; i++) {
            rev[i] = Integer.reverse(i) >>> (32 - levels);
        }
    }

    /** X[k] = sum x[n] * exp(-j*2*pi*k*n/N) */
    public void forward(double[] re, double[] im) {
        transform(re, im, false);
    }

    /** x[n] = (1/N) * sum X[k] * exp(+j*2*pi*k*n/N) */
    public void inverse(double[] re, double[] im) {
        transform(re, im, true);
    }

    private void transform(double[] re, double[] im, boolean inv) {
        final int n = this.n;
        for (int i = 0; i < n; i++) {
            int j = rev[i];
            if (j > i) {
                double t = re[i];
                re[i] = re[j];
                re[j] = t;
                t = im[i];
                im[i] = im[j];
                im[j] = t;
            }
        }
        for (int size = 2; size <= n; size <<= 1) {
            final int half = size >> 1;
            final int step = n / size;
            for (int i = 0; i < n; i += size) {
                int k = 0;
                for (int j = i; j < i + half; j++, k += step) {
                    final double wr = cosT[k];
                    final double wi = inv ? sinT[k] : -sinT[k];
                    final int l = j + half;
                    final double tr = re[l] * wr - im[l] * wi;
                    final double ti = re[l] * wi + im[l] * wr;
                    re[l] = re[j] - tr;
                    im[l] = im[j] - ti;
                    re[j] += tr;
                    im[j] += ti;
                }
            }
        }
        if (inv) {
            final double s = 1.0 / n;
            for (int i = 0; i < n; i++) {
                re[i] *= s;
                im[i] *= s;
            }
        }
    }
}
