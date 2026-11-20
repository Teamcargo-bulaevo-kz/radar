package kz.bulaevo.radar.dsp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * ИИ-гид: объединяет распознанные предметы, карту глубины и сонар. Для каждого предмета — насколько близко
 * (по глубине внутри рамки), сколько метров (если есть масштаб от сонара) и с какой стороны. Выбирает самое
 * важное, что стоит сказать вслух: близкое и прямо по курсу важнее далёкого и сбоку.
 */
public final class Guide {
    public static final class Item {
        public String name;
        /** Рамка в долях кадра. */
        public float l;
        public float t;
        public float r;
        public float b;
        /** Близость 0 (далеко) … 1 (близко) по нейросети глубины. */
        public float near;
        /** Расстояние, м (NaN — масштаба нет). */
        public float meters = Float.NaN;
        /** -1 слева, 0 прямо, 1 справа. */
        public int side;
        public float importance;

        public String sideWord() {
            return side < 0 ? "слева" : (side > 0 ? "справа" : "прямо");
        }

        /** Коротко для экрана и голоса: «стул, прямо, 1,2 метра» или «стул, прямо, близко». */
        public String phrase() {
            String dist;
            if (!Float.isNaN(meters)) {
                dist = String.format(Locale.US, "%.1f", meters).replace('.', ',') + " метра";
            } else {
                dist = near > 0.75f ? "близко" : (near > 0.4f ? "недалеко" : "далеко");
            }
            return name + ", " + sideWord() + ", " + dist;
        }
    }

    private Guide() {
    }

    /**
     * boxes — по 4 числа на предмет (l, t, r, b в долях вертикального кадра), names — названия.
     * depth — обратная глубина w×h того же кадра; lo/hi — её 2-й и 98-й перцентили; sonar — ближайшее по
     * сонару, м (NaN — нет).
     */
    public static List<Item> describe(float[] boxes, String[] names, int n, float[] depth, int w, int h,
                                      float lo, float hi, float sonar) {
        List<Item> out = new ArrayList<>();
        float span = hi - lo;
        float[] tmp = new float[w * h];
        for (int i = 0; i < n; i++) {
            Item it = new Item();
            it.name = names[i];
            it.l = boxes[i * 4];
            it.t = boxes[i * 4 + 1];
            it.r = boxes[i * 4 + 2];
            it.b = boxes[i * 4 + 3];
            // середина рамки (края часто захватывают фон)
            float cw = (it.r - it.l) * 0.25f;
            float ch = (it.b - it.t) * 0.25f;
            int x0 = clamp((int) ((it.l + cw) * w), w);
            int x1 = clamp((int) Math.ceil((it.r - cw) * w), w);
            int y0 = clamp((int) ((it.t + ch) * h), h);
            int y1 = clamp((int) Math.ceil((it.b - ch) * h), h);
            int m = 0;
            for (int y = y0; y <= y1; y++) {
                for (int x = x0; x <= x1; x++) {
                    tmp[m++] = depth[y * w + x];
                }
            }
            float d;
            if (m == 0) {
                d = lo;
            } else {
                Arrays.sort(tmp, 0, m);
                d = tmp[m / 2];
            }
            it.near = span > 0 ? Math.max(0f, Math.min(1f, (d - lo) / span)) : 0f;
            // как у вкладки «Глубина»: масштаб по ближайшему предмету, который слышит сонар
            it.meters = DepthMath.metersFromSonar(sonar, hi - lo + 1e-3f, d - lo + 1e-3f);
            float cx = (it.l + it.r) / 2f;
            it.side = cx < 0.36f ? -1 : (cx > 0.64f ? 1 : 0);
            float central = 1f - Math.min(1f, Math.abs(cx - 0.5f) * 2f);
            float size = Math.min(1f, (it.r - it.l) * (it.b - it.t) * 3f);
            it.importance = it.near * 0.6f + central * 0.25f + size * 0.15f;
            out.add(it);
        }
        Collections.sort(out, new Comparator<Item>() {
            @Override
            public int compare(Item a, Item b) {
                return Float.compare(b.importance, a.importance);
            }
        });
        return out;
    }

    private static int clamp(int v, int n) {
        return Math.max(0, Math.min(n - 1, v));
    }
}
