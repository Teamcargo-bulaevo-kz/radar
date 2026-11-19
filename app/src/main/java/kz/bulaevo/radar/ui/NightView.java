package kz.bulaevo.radar.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;

/** Показывает обработанный ночной кадр, повёрнутый под портретный экран, и строки подсказок поверх. */
public class NightView extends TickView {
    private final Object lock = new Object();
    private Bitmap bmp;
    private int fw;
    private int fh;
    private int sensorRotation = 90;
    private boolean mirror;
    private String[] hud = new String[0];

    private final Paint img = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint box = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix mat = new Matrix();

    public NightView(Context c) {
        super(c);
        float d = c.getResources().getDisplayMetrics().density;
        txt.setColor(0xFFE8FFF2);
        txt.setTextSize(12f * d);
        box.setColor(0x99000000);
        setBackgroundColor(0xFF000000);
    }

    public void setSensorRotation(int deg, boolean mirror) {
        sensorRotation = ((deg % 360) + 360) % 360;
        this.mirror = mirror;
    }

    public void setHud(String... lines) {
        hud = lines;
    }

    public void setFrame(int[] px, int w, int h) {
        synchronized (lock) {
            if (bmp == null || bmp.getWidth() != w || bmp.getHeight() != h) {
                bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            }
            bmp.setPixels(px, 0, w, 0, 0, w, h);
            fw = w;
            fh = h;
        }
    }

    /** Копия последнего кадра (для сохранения снимка). */
    public Bitmap snapshot() {
        synchronized (lock) {
            if (bmp == null) {
                return null;
            }
            Matrix m = new Matrix();
            m.postRotate(sensorRotation, fw / 2f, fh / 2f);
            boolean swap = sensorRotation == 90 || sensorRotation == 270;
            int ow = swap ? fh : fw;
            int oh = swap ? fw : fh;
            m.reset();
            m.postTranslate(-fw / 2f, -fh / 2f);
            m.postRotate(sensorRotation);
            m.postTranslate(ow / 2f, oh / 2f);
            Bitmap out = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(out);
            c.drawBitmap(bmp, m, img);
            return out;
        }
    }

    @Override
    protected void onDraw(Canvas cv) {
        final float vw = getWidth();
        final float vh = getHeight();
        synchronized (lock) {
            if (bmp != null) {
                boolean swap = sensorRotation == 90 || sensorRotation == 270;
                float rw = swap ? fh : fw;
                float rh = swap ? fw : fh;
                float s = Math.min(vw / rw, vh / rh);
                mat.reset();
                mat.postTranslate(-fw / 2f, -fh / 2f);
                mat.postRotate(sensorRotation);
                if (mirror) {
                    mat.postScale(-1f, 1f);
                }
                mat.postScale(s, s);
                mat.postTranslate(vw / 2f, vh / 2f);
                cv.drawBitmap(bmp, mat, img);
            }
        }
        final float d = getResources().getDisplayMetrics().density;
        String[] lines = hud;
        float y = 6f * d;
        for (String s : lines) {
            if (s == null || s.isEmpty()) {
                continue;
            }
            float tw = txt.measureText(s);
            cv.drawRect(4f * d, y, 4f * d + tw + 8f * d, y + 17f * d, box);
            cv.drawText(s, 8f * d, y + 13f * d, txt);
            y += 19f * d;
        }
    }
}
