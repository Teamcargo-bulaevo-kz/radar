package kz.bulaevo.radar.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;

import java.util.Locale;

/** Карта отклонений (строки × столбцы): красное — поле сильнее среднего, синее — слабее. */
public class HeatmapView extends TickView {
    private final Paint fill = new Paint();
    private final Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float[][] data = new float[0][0];
    private String empty = "";

    public HeatmapView(Context c) {
        super(c);
        float d = c.getResources().getDisplayMetrics().density;
        txt.setColor(Ui.DIM);
        txt.setTextSize(11f * d);
        setBackgroundColor(Ui.PANEL);
    }

    public void setData(float[][] d, String emptyText) {
        data = d;
        empty = emptyText;
    }

    @Override
    protected void onDraw(Canvas cv) {
        float w = getWidth();
        float h = getHeight();
        float d = getResources().getDisplayMetrics().density;
        float[][] a = data;
        if (a.length == 0) {
            cv.drawText(empty, 8 * d, h / 2, txt);
            return;
        }
        float mx = 1f;
        for (float[] row : a) {
            for (float v : row) {
                mx = Math.max(mx, Math.abs(v));
            }
        }
        int rows = a.length;
        int cols = a[0].length;
        float top = 14 * d;
        float ch = Math.min((h - top) / rows, 28 * d);
        float cw = w / cols;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                fill.setColor(color(a[r][c] / mx));
                cv.drawRect(c * cw, top + r * ch, (c + 1) * cw + 0.5f, top + (r + 1) * ch + 0.5f, fill);
            }
        }
        cv.drawText(String.format(Locale.US, "строк: %d · ±%.1f µT · ← начало строки   конец →", rows, mx),
                4 * d, 11 * d, txt);
    }

    private static int color(float t) {
        t = Math.max(-1f, Math.min(1f, t));
        int base = 0x18;
        if (t >= 0) {
            int r = (int) (base + (255 - base) * t);
            int g = (int) (base + (90 - base) * t);
            return 0xFF000000 | (r << 16) | (Math.max(0, g) << 8) | base;
        }
        float k = -t;
        int b = (int) (base + (255 - base) * k);
        int g = (int) (base + (150 - base) * k);
        return 0xFF000000 | (base << 16) | (g << 8) | b;
    }
}
