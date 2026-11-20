package kz.bulaevo.radar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import kz.bulaevo.radar.dsp.DepthMath;

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
}
