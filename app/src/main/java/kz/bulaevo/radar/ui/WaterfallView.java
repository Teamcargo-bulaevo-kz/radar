package kz.bulaevo.radar.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

/** Водопад: по горизонтали время (новое справа), по вертикали дальность или частота, цвет — сила сигнала. */
public class WaterfallView extends TickView {
    private static final int[] PAL = new int[256];

    static {
        // чёрный → фиолетовый → красный → оранжевый → жёлтый → белый
        float[][] stops = {
                {0.00f, 0, 0, 0},
                {0.20f, 40, 8, 90},
                {0.45f, 170, 25, 90},
                {0.70f, 250, 120, 20},
                {0.90f, 255, 225, 90},
                {1.00f, 255, 255, 245}
        };
        for (int i = 0; i < 256; i++) {
            float v = i / 255f;
            int k = 0;
            while (k < stops.length - 2 && v > stops[k + 1][0]) {
                k++;
            }
            float t = (v - stops[k][0]) / (stops[k + 1][0] - stops[k][0]);
            t = Math.max(0f, Math.min(1f, t));
            int r = Math.round(stops[k][1] + t * (stops[k + 1][1] - stops[k][1]));
            int g = Math.round(stops[k][2] + t * (stops[k + 1][2] - stops[k][2]));
            int b = Math.round(stops[k][3] + t * (stops[k + 1][3] - stops[k][3]));
            PAL[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
    }

    private final Object lock = new Object();
    private final int cols;
    private final int rows;
    private final int[] pix;
    private final Bitmap bmp;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF dst = new RectF();
    private float vMin = 0f;
    private float vMax = 40f;
    private boolean flip;
    private String capTop = "";
    private String capBottom = "";

    public WaterfallView(Context c, int cols, int rows) {
        super(c);
        this.cols = cols;
        this.rows = rows;
        this.pix = new int[cols * rows];
        this.bmp = Bitmap.createBitmap(cols, rows, Bitmap.Config.ARGB_8888);
        text.setColor(0xCCFFFFFF);
        text.setTextSize(10.5f * c.getResources().getDisplayMetrics().density);
        text.setShadowLayer(3f, 0f, 0f, 0xFF000000);
        clear();
        setBackgroundColor(0xFF000000);
    }

    /** Значения vMin → чёрный, vMax → белый. */
    public void setScale(float vMin, float vMax) {
        this.vMin = vMin;
        this.vMax = Math.max(vMax, vMin + 1f);
    }

    /** flip = true: первое значение внизу (удобно для частоты). */
    public void setFlip(boolean flip) {
        this.flip = flip;
    }

    public void setCaptions(String top, String bottom) {
        capTop = top == null ? "" : top;
        capBottom = bottom == null ? "" : bottom;
    }

    public void clear() {
        synchronized (lock) {
            java.util.Arrays.fill(pix, 0xFF000000);
        }
    }

    /** Добавить новый столбец: count значений по оси дальности/частоты. */
    public void push(float[] v, int count) {
        if (count <= 0) {
            return;
        }
        synchronized (lock) {
            for (int r = 0; r < rows; r++) {
                System.arraycopy(pix, r * cols + 1, pix, r * cols, cols - 1);
            }
            final float span = vMax - vMin;
            for (int r = 0; r < rows; r++) {
                int i0 = (int) ((long) r * count / rows);
                int i1 = Math.max(i0 + 1, (int) ((long) (r + 1) * count / rows));
                if (i1 > count) {
                    i1 = count;
                }
                float m = -1e9f;
                for (int i = i0; i < i1; i++) {
                    if (v[i] > m) {
                        m = v[i];
                    }
                }
                float t = (m - vMin) / span;
                if (t < 0f) {
                    t = 0f;
                } else if (t > 1f) {
                    t = 1f;
                }
                int rr = flip ? rows - 1 - r : r;
                pix[rr * cols + cols - 1] = PAL[(int) (t * 255f)];
            }
        }
    }

    @Override
    protected void onDraw(Canvas cv) {
        synchronized (lock) {
            bmp.setPixels(pix, 0, cols, 0, 0, cols, rows);
        }
        dst.set(0, 0, getWidth(), getHeight());
        cv.drawBitmap(bmp, null, dst, paint);
        float d = getResources().getDisplayMetrics().density;
        if (!capTop.isEmpty()) {
            cv.drawText(capTop, 6f * d, 14f * d, text);
        }
        if (!capBottom.isEmpty()) {
            cv.drawText(capBottom, 6f * d, getHeight() - 6f * d, text);
        }
    }
}
