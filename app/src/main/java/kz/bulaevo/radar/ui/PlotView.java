package kz.bulaevo.radar.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;

import java.util.Locale;

/** Универсальный график: кривая по равномерным точкам, сетка, подписи осей, пик-холд, маркеры, порог. */
public class PlotView extends TickView {
    private final Object lock = new Object();
    private float[] y = new float[0];
    private int count;
    private float[] hold = new float[0];
    private int holdCount;
    private float dataX0 = 0f;
    private float dataX1 = 1f;
    private float xMin = 0f;
    private float xMax = 1f;
    private float yMin = 0f;
    private float yMax = 1f;
    private float xStep = 1f;
    private float yStep = 10f;
    private String xFmt = "%.0f";
    private String yFmt = "%.0f";
    private float thr = Float.NaN;
    private float[] mkX = new float[8];
    private String[] mkText = new String[8];
    private int mkCount;
    private String title = "";

    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint holdP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mark = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thrP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    public PlotView(Context c) {
        super(c);
        float d = c.getResources().getDisplayMetrics().density;
        grid.setColor(0x33FFFFFF);
        grid.setStrokeWidth(1f);
        text.setColor(Ui.DIM);
        text.setTextSize(10.5f * d);
        line.setColor(Ui.ACC);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(1.6f * d);
        fill.setColor(Ui.withAlpha(Ui.ACC, 60));
        fill.setStyle(Paint.Style.FILL);
        holdP.setColor(Ui.WARN);
        holdP.setStyle(Paint.Style.STROKE);
        holdP.setStrokeWidth(1.2f * d);
        mark.setColor(0xFFFFFFFF);
        mark.setStrokeWidth(1.2f * d);
        mark.setTextSize(11.5f * d);
        thrP.setColor(Ui.BAD);
        thrP.setStyle(Paint.Style.STROKE);
        thrP.setStrokeWidth(1f * d);
        thrP.setPathEffect(new DashPathEffect(new float[]{8f * d, 6f * d}, 0f));
        setBackgroundColor(0xFF020A07);
    }

    public void setLineColor(int color) {
        line.setColor(color);
        fill.setColor(Ui.withAlpha(color, 60));
    }

    public void setTitle(String t) {
        title = t == null ? "" : t;
    }

    /** Видимая область по осям. */
    public void setRange(float xMin, float xMax, float yMin, float yMax) {
        this.xMin = xMin;
        this.xMax = xMax;
        this.yMin = yMin;
        this.yMax = yMax;
    }

    /** Шаги сетки и форматы подписей осей. */
    public void setGrid(float xStep, String xFmt, float yStep, String yFmt) {
        this.xStep = xStep;
        this.xFmt = xFmt;
        this.yStep = yStep;
        this.yFmt = yFmt;
    }

    /** Какому отрезку оси X соответствуют загружаемые точки (по умолчанию — видимому). */
    public void setDataExtent(float x0, float x1) {
        dataX0 = x0;
        dataX1 = x1;
    }

    public void setData(float[] src, int n) {
        synchronized (lock) {
            if (y.length < n) {
                y = new float[n];
            }
            System.arraycopy(src, 0, y, 0, n);
            count = n;
        }
    }

    public void setHold(float[] src, int n) {
        synchronized (lock) {
            if (hold.length < n) {
                hold = new float[n];
            }
            System.arraycopy(src, 0, hold, 0, n);
            holdCount = n;
        }
    }

    public void clearHold() {
        synchronized (lock) {
            holdCount = 0;
        }
    }

    public void setThreshold(float v) {
        thr = v;
    }

    public void setMarkers(float[] xs, String[] labels, int n) {
        synchronized (lock) {
            mkCount = Math.min(n, mkX.length);
            for (int i = 0; i < mkCount; i++) {
                mkX[i] = xs[i];
                mkText[i] = labels[i];
            }
        }
    }

    private float px(float x, float l, float w) {
        return l + (x - xMin) / (xMax - xMin) * w;
    }

    private float py(float v, float t, float h) {
        float c = Math.max(yMin, Math.min(yMax, v));
        return t + (1f - (c - yMin) / (yMax - yMin)) * h;
    }

    private void curve(Canvas cv, float[] a, int n, float l, float t, float w, float h, Paint p, boolean closeFill) {
        if (n < 2) {
            return;
        }
        path.reset();
        boolean started = false;
        float lastX = l;
        float step = (dataX1 - dataX0) / (n - 1);
        for (int i = 0; i < n; i++) {
            float x = dataX0 + step * i;
            if (x < xMin || x > xMax) {
                continue;
            }
            float X = px(x, l, w);
            float Y = py(a[i], t, h);
            if (!started) {
                path.moveTo(X, Y);
                started = true;
            } else {
                path.lineTo(X, Y);
            }
            lastX = X;
        }
        if (!started) {
            return;
        }
        if (closeFill) {
            Path f = new Path(path);
            f.lineTo(lastX, t + h);
            f.lineTo(l, t + h);
            f.close();
            cv.drawPath(f, fill);
        }
        cv.drawPath(path, p);
    }

    @Override
    protected void onDraw(Canvas cv) {
        final float d = getResources().getDisplayMetrics().density;
        final float l = 38f * d;
        final float r = 6f * d;
        final float t = (title.isEmpty() ? 6f : 18f) * d;
        final float b = 18f * d;
        final float w = getWidth() - l - r;
        final float h = getHeight() - t - b;
        if (w <= 10 || h <= 10 || xMax <= xMin || yMax <= yMin) {
            return;
        }
        // сетка по Y
        text.setTextAlign(Paint.Align.RIGHT);
        float startY = (float) Math.ceil(yMin / yStep) * yStep;
        for (float v = startY; v <= yMax + 1e-3f; v += yStep) {
            float Y = py(v, t, h);
            cv.drawLine(l, Y, l + w, Y, grid);
            cv.drawText(String.format(Locale.US, yFmt, v), l - 4f * d, Y + 4f * d, text);
        }
        // сетка по X
        text.setTextAlign(Paint.Align.CENTER);
        float startX = (float) Math.ceil(xMin / xStep) * xStep;
        for (float v = startX; v <= xMax + 1e-3f; v += xStep) {
            float X = px(v, l, w);
            cv.drawLine(X, t, X, t + h, grid);
            cv.drawText(String.format(Locale.US, xFmt, v), X, t + h + 13f * d, text);
        }
        if (!title.isEmpty()) {
            text.setTextAlign(Paint.Align.LEFT);
            cv.drawText(title, l, 12f * d, text);
        }
        synchronized (lock) {
            curve(cv, y, count, l, t, w, h, line, true);
            if (holdCount > 1) {
                curve(cv, hold, holdCount, l, t, w, h, holdP, false);
            }
            if (!Float.isNaN(thr)) {
                float Y = py(thr, t, h);
                cv.drawLine(l, Y, l + w, Y, thrP);
            }
            mark.setTextAlign(Paint.Align.CENTER);
            for (int i = 0; i < mkCount; i++) {
                if (mkX[i] < xMin || mkX[i] > xMax) {
                    continue;
                }
                float X = px(mkX[i], l, w);
                cv.drawLine(X, t, X, t + h, mark);
                cv.drawText(mkText[i], X, t + 12f * d + (i % 2) * 13f * d, mark);
            }
        }
    }
}
