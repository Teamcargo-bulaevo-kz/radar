package kz.bulaevo.radar.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

import java.util.Locale;

import kz.bulaevo.radar.SonarHub;
import kz.bulaevo.radar.dsp.Bands;
import kz.bulaevo.radar.dsp.SectorMemory;

/**
 * Веер впереди: полукруг ±90° от направления телефона. Цветные клинья — что и как далеко
 * обнаружено в последние секунды, пока вы водили телефоном влево-вправо; голубая стрелка — куда свободнее.
 */
public final class WalkView extends TickView {
    public static final double VIEW_LIMIT_DEG = 90.0;

    private final SonarHub hub;
    private final Paint wedge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fwd = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arrow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private volatile double freeDir = Double.NaN;

    public WalkView(Context c, SonarHub hub) {
        super(c);
        this.hub = hub;
        tickMs = 70;
        float d = c.getResources().getDisplayMetrics().density;
        wedge.setStyle(Paint.Style.FILL);
        ring.setColor(0x4433FF99);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(1f * d);
        txt.setColor(0xAA7FE8B0);
        txt.setTextSize(10.5f * d);
        fwd.setColor(0xFFE8FFF2);
        fwd.setStrokeWidth(2f * d);
        arrow.setColor(0xFF4DE8FF);
        arrow.setStrokeWidth(4f * d);
        arrow.setStrokeCap(Paint.Cap.ROUND);
        dot.setColor(0xFFE8FFF2);
        setBackgroundColor(0xFF020A07);
    }

    /** Направление «свободнее», задаётся вкладкой; NaN — нет подсказки. */
    public void setFreeDirection(double relDeg) {
        freeDir = relDeg;
    }

    private static int colorFor(float r, float viewR) {
        if (r < 1.0f) {
            return 0xFFFF4A4A;
        }
        if (r < 2.0f) {
            return 0xFFFF9A2E;
        }
        if (r < 3.0f) {
            return 0xFFFFE04A;
        }
        return r >= viewR ? 0xFF1E6B4A : 0xFF3DFFA0;
    }

    @Override
    protected void onDraw(Canvas cv) {
        final float d = getResources().getDisplayMetrics().density;
        final float w = getWidth();
        final float h = getHeight();
        final float cx = w / 2f;
        final float cy = h - 14f * d;
        final float rv = Math.min(w / 2f - 10f * d, h - 34f * d);
        if (rv < 30f) {
            return;
        }
        final double maxR = Bands.RANGE_M[hub.st.range];
        final float viewR = (float) Math.min(maxR, 4.0);
        final double heading = hub.orient.azimuth();
        final long now = System.currentTimeMillis();

        // клинья по 5°
        final double step = SectorMemory.SECTOR_DEG;
        for (double rel = -VIEW_LIMIT_DEG; rel < VIEW_LIMIT_DEG - 1e-9; rel += step) {
            float r = hub.sectors.rangeAt(heading + rel + step / 2, now);
            if (Float.isNaN(r)) {
                continue;
            }
            float pr = rv * Math.min(r, viewR) / viewR;
            wedge.setColor(Ui.withAlpha(colorFor(r, viewR), r >= viewR ? 90 : 200));
            oval.set(cx - pr, cy - pr, cx + pr, cy + pr);
            cv.drawArc(oval, (float) (-90.0 + rel), (float) step - 0.4f, true, wedge);
        }

        // кольца дальности
        txt.setTextAlign(Paint.Align.LEFT);
        for (int m = 1; m <= (int) viewR; m++) {
            float rr = rv * m / viewR;
            oval.set(cx - rr, cy - rr, cx + rr, cy + rr);
            cv.drawArc(oval, -180f, 180f, false, ring);
            cv.drawText(m + " м", cx + 4f * d, cy - rr + 12f * d, txt);
        }
        // лучи -90, -45, 0, 45, 90
        for (int a = -90; a <= 90; a += 45) {
            double rad = Math.toRadians(a);
            cv.drawLine(cx, cy, cx + (float) (rv * Math.sin(rad)), cy - (float) (rv * Math.cos(rad)), ring);
        }
        // направление телефона
        cv.drawLine(cx, cy, cx, cy - rv, fwd);

        // подсказка: куда свободнее
        double fd = freeDir;
        if (!Double.isNaN(fd)) {
            double rad = Math.toRadians(fd);
            float len = rv * 0.92f;
            float ex = cx + (float) (len * Math.sin(rad));
            float ey = cy - (float) (len * Math.cos(rad));
            cv.drawLine(cx, cy, ex, ey, arrow);
            double back = rad + Math.PI;
            float hx = 14f * d;
            cv.drawLine(ex, ey, ex + (float) (hx * Math.sin(back + 0.45)), ey - (float) (hx * Math.cos(back + 0.45)), arrow);
            cv.drawLine(ex, ey, ex + (float) (hx * Math.sin(back - 0.45)), ey - (float) (hx * Math.cos(back - 0.45)), arrow);
        }
        cv.drawCircle(cx, cy, 5f * d, dot);
        txt.setTextAlign(Paint.Align.CENTER);
        cv.drawText(String.format(Locale.US, "влево 90°"), cx - rv + 30f * d, cy + 12f * d, txt);
        cv.drawText("вправо 90°", cx + rv - 30f * d, cy + 12f * d, txt);
    }
}
