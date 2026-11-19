package kz.bulaevo.radar.dsp;

/** Закон парктроника: как расстояние превращается в темп и тон гудков. */
public final class Parking {
    private Parking() {
    }

    /** Ближе этого расстояния звук сплошной, м. */
    public static final float CONTINUOUS_M = 0.30f;

    /** Период гудков, мс: чем ближе, тем чаще. */
    public static float intervalMs(float d) {
        return Math.max(110f, Math.min(1100f, 110f + d * 270f));
    }

    /** Частота тона, Гц: чем ближе, тем выше (0.6–1.5 кГц, ниже полосы сонара). */
    public static float pitchHz(float d, float maxD) {
        float x = Math.max(0f, Math.min(1f, d / Math.max(0.5f, maxD)));
        return 1500f - 900f * x;
    }
}
