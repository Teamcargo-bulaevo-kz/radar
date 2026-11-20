package kz.bulaevo.radar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import kz.bulaevo.radar.dsp.DepthMath;
import kz.bulaevo.radar.dsp.Guide;

import org.junit.Test;

/** Поворот кадра в вертикальное положение, перцентили и перевод в метры по сонару. */
public class DepthMathTest {
    /** Кадр 4×2 с одной яркой точкой; после поворота она должна оказаться там, где её покажет экран. */
    private static int brightAfter(int rot, int sx, int sy) {
        int w = 4;
        int h = 2;
        byte[] y = new byte[w * h];
        y[sy * w + sx] = (byte) 200;
        int ow = DepthMath.uprightW(w, h, rot);
        int oh = DepthMath.uprightH(w, h, rot);
        byte[] out = new byte[ow * oh];
        DepthMath.sampleUpright(y, w, h, rot, out, ow, oh, 1);
        int found = -1;
        for (int i = 0; i < out.length; i++) {
            if ((out[i] & 0xFF) == 200) {
                assertEquals("одна яркая точка", -1, found);
                found = i;
            }
        }
        return found;
    }

    @Test
    public void rotationMatchesScreen() {
        // без поворота — на месте
        assertEquals(1 * 4 + 3, brightAfter(0, 3, 1));
        // 90° по часовой: точка (x=3, y=0) кадра 4×2 → (x = h-1-y = 1, y = x = 3) кадра 2×4
        assertEquals(3 * 2 + 1, brightAfter(90, 3, 0));
        // 270°: (x=3, y=0) → (x = y = 0, y = w-1-x = 0)
        assertEquals(0, brightAfter(270, 3, 0));
        // 180°: (x=3, y=0) → (x=0, y=1)
        assertEquals(1 * 4, brightAfter(180, 3, 0));
    }

    @Test
    public void rgbChannelsAndDownscale() {
        int w = 8;
        int h = 8;
        byte[] y = new byte[w * h];
        for (int i = 0; i < y.length; i++) {
            y[i] = (byte) 100;
        }
        byte[] out = new byte[4 * 4 * 3];
        DepthMath.sampleUpright(y, w, h, 90, out, 4, 4, 3);
        for (byte b : out) {
            assertEquals(100, b & 0xFF);
        }
    }

    @Test
    public void percentileIgnoresNaN() {
        float[] a = {5, Float.NaN, 1, 3, Float.POSITIVE_INFINITY, 2, 4};
        float[] s = new float[a.length];
        assertEquals(1f, DepthMath.percentile(a, a.length, 0f, s), 0f);
        assertEquals(5f, DepthMath.percentile(a, a.length, 1f, s), 0f);
        assertEquals(3f, DepthMath.percentile(a, a.length, 0.5f, s), 0f);
    }

    @Test
    public void sonarScale() {
        // ближайшее по сонару 1 м; точка с вдвое меньшей обратной глубиной — вдвое дальше
        assertEquals(2f, DepthMath.metersFromSonar(1f, 0.8f, 0.4f), 1e-6f);
        assertTrue(Float.isNaN(DepthMath.metersFromSonar(Float.NaN, 0.8f, 0.4f)));
        assertTrue(Float.isNaN(DepthMath.metersFromSonar(1f, 0.8f, 0f)));
    }

    @Test
    public void colorsGoFromBlueToRed() {
        int far = DepthMath.color(0f);
        int near = DepthMath.color(1f);
        assertTrue((far & 0xFF) > ((far >> 16) & 0xFF));   // далеко — синее
        assertTrue(((near >> 16) & 0xFF) > (near & 0xFF)); // близко — красное
    }

    @Test
    public void structureFindsObjectEdges() {
        // стена (далеко) и посередине ящик (близко): граница — по краю ящика, внутри и на стене — ровно
        int w = 32;
        int h = 32;
        float[] d = new float[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                d[y * w + x] = x >= 10 && x < 22 && y >= 10 && y < 22 ? 1f : 0.2f;
            }
        }
        float[] e = new float[w * h];
        float[] s = new float[w * h];
        DepthMath.structure(d, w, h, 0.2f, 1f, e, s);
        assertTrue("край ящика", e[16 * w + 10] > 0.9f);
        assertTrue("стена ровная", e[3 * w + 3] < 0.05f);
        assertTrue("внутри ящика ровно", e[16 * w + 16] < 0.05f);
        // ровные места освещены одинаково, левый край ящика (смотрит на свет) — светлее правого
        assertEquals(s[3 * w + 3], s[16 * w + 16], 1e-4f);
        assertTrue("рельеф", s[16 * w + 10] != s[16 * w + 21]);
    }

    @Test
    public void guidePicksCloseCentralObject() {
        // сцена: стена далеко (0.2), стул прямо и близко (0.9), человек слева подальше (0.5)
        int w = 40;
        int h = 40;
        float[] d = new float[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float v = 0.2f;
                if (x >= 16 && x < 26 && y >= 20 && y < 36) {
                    v = 0.9f;
                } else if (x >= 2 && x < 10 && y >= 8 && y < 34) {
                    v = 0.5f;
                }
                d[y * w + x] = v;
            }
        }
        float[] boxes = {0.05f, 0.2f, 0.25f, 0.85f, 0.4f, 0.5f, 0.65f, 0.9f};
        String[] names = {"человек", "стул"};
        // сонар слышит ближайшее — стул — на 1.0 м
        List<Guide.Item> items = Guide.describe(boxes, names, 2, d, w, h, 0.2f, 0.9f, 1.0f);
        assertEquals("стул", items.get(0).name);
        assertEquals(0, items.get(0).side);
        assertEquals(1.0f, items.get(0).meters, 0.05f);
        Guide.Item man = items.get(1);
        assertEquals(-1, man.side);
        assertTrue("человек дальше стула: " + man.meters, man.meters > 1.5f);
        assertEquals("стул, прямо, 1,0 метра", items.get(0).phrase());
        // без сонара — словами
        List<Guide.Item> rel = Guide.describe(boxes, names, 2, d, w, h, 0.2f, 0.9f, Float.NaN);
        assertEquals("стул, прямо, близко", rel.get(0).phrase());
    }

    @Test
    public void colorConversion() {
        int w = 4;
        int h = 2;
        byte[] y = new byte[w * h];
        byte[] u = new byte[2];
        byte[] v = new byte[2];
        java.util.Arrays.fill(y, (byte) 100);
        u[0] = (byte) 128; v[0] = (byte) 128;  // левая половина — серая
        u[1] = (byte) 128; v[1] = (byte) 200;  // правая — красноватая
        byte[] out = new byte[w * h * 3];
        DepthMath.sampleUprightRgb(y, u, v, w, h, 0, out, w, h);
        assertEquals(100, out[0] & 0xFF);
        assertEquals(100, out[1] & 0xFF);
        assertEquals(100, out[2] & 0xFF);
        int r = out[3 * 3] & 0xFF;
        int g = out[3 * 3 + 1] & 0xFF;
        assertTrue("красное " + r + " > зелёное " + g, r > 180 && g < 60);
    }
}
