package kz.bulaevo.radar.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import java.util.Locale;

import kz.bulaevo.radar.dsp.RoomMap;

/** Экран радара: круглая карта эха вокруг телефона, кольца дальности, луч развёртки, контур комнаты. */
public class RadarMapView extends TickView {
    private static final int BMP = 320;

    private RoomMap map;
    private volatile double azimuth;
    private volatile boolean live;
    private volatile float nearest = Float.NaN;
    private boolean mini;
    private volatile double viewR = 0;
    private boolean[] blocked = new boolean[0];
    private final Paint lblFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lblStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();

    private Bitmap bmp;
    private int[] pix;
    private int[] lut;
    private int lutAngles;
    private int lutRanges;
    private int lastVersion = -1;
    private long lastBuild;
    private int lastOutlineVersion = -1;

    private final int[] pal = new int[256];
    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint img = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sweep = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint target = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();

    public RadarMapView(Context c) {
        super(c);
        tickMs = 80;
        float d = c.getResources().getDisplayMetrics().density;
        bg.setColor(0xFF03140E);
        bg.setStyle(Paint.Style.FILL);
        ring.setColor(0x4433FF99);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(1f * d);
        txt.setColor(0xAA7FE8B0);
        txt.setTextSize(10.5f * d);
        outline.setColor(0xFF4DE8FF);
        outline.setStyle(Paint.Style.STROKE);
        outline.setStrokeWidth(2f * d);
        outline.setStrokeJoin(Paint.Join.ROUND);
        sweep.setColor(0xFFB8FFD6);
        sweep.setStrokeWidth(2f * d);
        target.setColor(0xFFFFB020);
        target.setStyle(Paint.Style.STROKE);
        target.setStrokeWidth(2f * d);
        dot.setColor(0xFFE8FFF2);
        dot.setStyle(Paint.Style.FILL);
        lblFill.setColor(0xFFFFFFFF);
        lblFill.setTextSize(12f * d);
        lblFill.setFakeBoldText(true);
        lblFill.setTextAlign(Paint.Align.CENTER);
        lblStroke.setColor(0xFF000000);
        lblStroke.setTextSize(12f * d);
        lblStroke.setFakeBoldText(true);
        lblStroke.setTextAlign(Paint.Align.CENTER);
        lblStroke.setStyle(Paint.Style.STROKE);
        lblStroke.setStrokeWidth(3f * d);
        setBackgroundColor(0xFF020A07);
        for (int i = 0; i < 256; i++) {
            float v = i / 255f;
            int r;
            int g;
            int b;
            int a;
            if (v < 0.5f) {
                float t = v / 0.5f;
                r = Math.round(10 + t * 40);
                g = Math.round(110 + t * 145);
                b = Math.round(60 + t * 40);
            } else if (v < 0.8f) {
                float t = (v - 0.5f) / 0.3f;
                r = Math.round(50 + t * 205);
                g = 255;
                b = Math.round(100 - t * 80);
            } else {
                float t = (v - 0.8f) / 0.2f;
                r = 255;
                g = Math.round(255 - t * 170);
                b = Math.round(20 + t * 20);
            }
            a = v < 0.05f ? 0 : Math.round(90 + 165 * Math.min(1f, v * 1.4f));
            pal[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }
    }

    public void setMap(RoomMap m) {
        map = m;
        lastVersion = -1;
    }

    /** Масштаб экрана, м (0 — вся карта): ближние предметы видны крупнее. */
    public void setViewRange(double meters) {
        viewR = meters;
    }

    public double viewRange() {
        return viewR;
    }

    public void setMini(boolean mini) {
        this.mini = mini;
    }

    public void setAzimuth(double deg, boolean live) {
        azimuth = deg;
        this.live = live;
    }

    public void setNearest(float meters) {
        nearest = meters;
    }

    private void ensureLut() {
        RoomMap m = map;
        if (m == null) {
            return;
        }
        if (lut != null && lutAngles == m.angleBins && lutRanges == m.rangeBins) {
            return;
        }
        lutAngles = m.angleBins;
        lutRanges = m.rangeBins;
        lut = new int[BMP * BMP];
        final double half = BMP / 2.0;
        for (int y = 0; y < BMP; y++) {
            for (int x = 0; x < BMP; x++) {
                double dx = x + 0.5 - half;
                double dy = y + 0.5 - half;
                double r = Math.sqrt(dx * dx + dy * dy) / half;
                int idx = -1;
                if (r < 1.0) {
                    double ang = Math.toDegrees(Math.atan2(dx, -dy));
                    if (ang < 0) {
                        ang += 360.0;
                    }
                    int a = (int) (ang / 360.0 * lutAngles) % lutAngles;
                    int k = Math.min(lutRanges - 1, (int) (r * lutRanges));
                    idx = a * lutRanges + k;
                }
                lut[y * BMP + x] = idx;
            }
        }
        pix = new int[BMP * BMP];
        if (bmp == null) {
            bmp = Bitmap.createBitmap(BMP, BMP, Bitmap.Config.ARGB_8888);
        }
    }

    private void rebuildBitmap() {
        RoomMap m = map;
        if (m == null) {
            return;
        }
        ensureLut();
        final float[] cells = m.cells;
        synchronized (m) {
            for (int i = 0; i < pix.length; i++) {
                int idx = lut[i];
                if (idx < 0) {
                    pix[i] = 0;
                } else {
                    float v = cells[idx];
                    pix[i] = pal[Math.max(0, Math.min(255, (int) (v * 255f)))];
                }
            }
            m.computeOutline(0.38f, 0.3);
        }
        bmp.setPixels(pix, 0, BMP, 0, 0, BMP, BMP);
    }

    @Override
    protected void onDraw(Canvas cv) {
        final float d = getResources().getDisplayMetrics().density;
        final float w = getWidth();
        final float h = getHeight();
        final float margin = (mini ? 6f : 38f) * d;
        final float cx = w / 2f;
        final float cy = h / 2f;
        final float rv = Math.min(w, h) / 2f - margin;
        if (rv < 20f) {
            return;
        }
        final RoomMap m = map;
        final double mapR = m == null ? 7.0 : m.maxRange();
        final double vr = viewR;
        final double maxR = (vr > 0 && vr < mapR) ? vr : mapR;

        cv.drawCircle(cx, cy, rv, bg);

        if (m != null) {
            long now = System.currentTimeMillis();
            if (m.version() != lastVersion && now - lastBuild > 90) {
                rebuildBitmap();
                lastVersion = m.version();
                lastBuild = now;
            }
            if (bmp != null) {
                final float sc = (float) (mapR / maxR);
                rect.set(cx - rv * sc, cy - rv * sc, cx + rv * sc, cy + rv * sc);
                if (sc > 1.001f) {
                    cv.save();
                    clip.reset();
                    clip.addCircle(cx, cy, rv, Path.Direction.CW);
                    cv.clipPath(clip);
                    cv.drawBitmap(bmp, null, rect, img);
                    cv.restore();
                } else {
                    cv.drawBitmap(bmp, null, rect, img);
                }
            }
        }

        // кольца дальности
        double step = maxR <= 4 ? 0.5 : (maxR <= 8 ? 1.0 : 2.0);
        txt.setTextAlign(Paint.Align.LEFT);
        for (double r = step; r <= maxR + 1e-6; r += step) {
            float rr = (float) (rv * r / maxR);
            cv.drawCircle(cx, cy, rr, ring);
            if (!mini) {
                String s = (r == Math.floor(r)) ? String.format(Locale.US, "%.0f м", r) : String.format(Locale.US, "%.1f м", r);
                float tx = cx + rr * 0.7071f + 2f * d;
                float ty = cy - rr * 0.7071f + 10f * d;
                cv.drawText(s, tx, ty, txt);
            }
        }
        // лучи через 45°
        for (int a = 0; a < 360; a += 45) {
            double rad = Math.toRadians(a);
            cv.drawLine(cx, cy, cx + (float) (rv * Math.sin(rad)), cy - (float) (rv * Math.cos(rad)), ring);
        }
        if (!mini) {
            txt.setTextAlign(Paint.Align.CENTER);
            cv.drawText("0°", cx, cy - rv - 3f * d, txt);
            cv.drawText("180°", cx, cy + rv + 12f * d, txt);
            txt.setTextAlign(Paint.Align.LEFT);
            cv.drawText("90°", cx + rv + 2f * d, cy + 4f * d, txt);
            txt.setTextAlign(Paint.Align.RIGHT);
            cv.drawText("270°", cx - rv - 2f * d, cy + 4f * d, txt);
        }

        // контур комнаты
        if (m != null) {
            path.reset();
            boolean pen = false;
            float prevR = 0f;
            final int n = m.angleBins;
            for (int a = 0; a <= n; a++) {
                int ai = a % n;
                float r = m.outlineR[ai];
                if (Float.isNaN(r)) {
                    pen = false;
                    continue;
                }
                double rad = Math.toRadians((ai + 0.5) / n * 360.0);
                float x = cx + (float) (rv * r / maxR * Math.sin(rad));
                float y = cy - (float) (rv * r / maxR * Math.cos(rad));
                if (!pen || Math.abs(r - prevR) > Math.max(0.6, maxR * 0.08)) {
                    path.moveTo(x, y);
                    pen = true;
                } else {
                    path.lineTo(x, y);
                }
                prevR = r;
            }
            cv.drawPath(path, outline);
        }

        if (m != null && !mini) {
            drawLabels(cv, m, cx, cy, rv, maxR, d);
        }

        // луч развёртки и цель
        if (live) {
            double rad = Math.toRadians(azimuth);
            float sx = (float) Math.sin(rad);
            float sy = -(float) Math.cos(rad);
            cv.drawLine(cx, cy, cx + sx * rv, cy + sy * rv, sweep);
            float nr = nearest;
            if (!Float.isNaN(nr) && nr <= maxR) {
                float rr = (float) (rv * nr / maxR);
                cv.drawCircle(cx + sx * rr, cy + sy * rr, 7f * d, target);
            }
        }
        cv.drawCircle(cx, cy, 3.5f * d, dot);
    }

    /** Подписи расстояний у самых близких объектов контура (не более шести, не ближе 22° друг к другу). */
    private void drawLabels(Canvas cv, RoomMap m, float cx, float cy, float rv, double maxR, float d) {
        final int n = m.angleBins;
        if (blocked.length != n) {
            blocked = new boolean[n];
        }
        java.util.Arrays.fill(blocked, false);
        for (int k = 0; k < 6; k++) {
            int best = -1;
            float bestR = Float.MAX_VALUE;
            for (int a = 0; a < n; a++) {
                float r = m.outlineR[a];
                if (blocked[a] || Float.isNaN(r) || r > maxR) {
                    continue;
                }
                if (r < bestR) {
                    bestR = r;
                    best = a;
                }
            }
            if (best < 0) {
                break;
            }
            for (int j = -11; j <= 11; j++) {
                blocked[((best + j) % n + n) % n] = true;
            }
            double rad = Math.toRadians((best + 0.5) / n * 360.0);
            float sx = (float) Math.sin(rad);
            float sy = -(float) Math.cos(rad);
            float rr = (float) (rv * bestR / maxR);
            float px = cx + sx * rr;
            float py = cy + sy * rr;
            cv.drawCircle(px, py, 3.5f * d, dot);
            String s = bestR >= 1f ? String.format(Locale.US, "%.1f м", bestR) : String.format(Locale.US, "%.2f м", bestR);
            float tx = px + sx * 20f * d;
            float ty = py + sy * 14f * d + 4f * d;
            cv.drawText(s, tx, ty, lblStroke);
            cv.drawText(s, tx, ty, lblFill);
        }
    }
}
