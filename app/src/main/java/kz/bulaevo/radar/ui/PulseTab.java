package kz.bulaevo.radar.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

import kz.bulaevo.radar.PermissionHost;
import kz.bulaevo.radar.SonarHub;
import kz.bulaevo.radar.audio.AudioUtil;
import kz.bulaevo.radar.audio.HeartEngine;
import kz.bulaevo.radar.camera.NightCamera;
import kz.bulaevo.radar.dsp.DepthMath;
import kz.bulaevo.radar.dsp.PulseEstimator;

/**
 * Вкладка "Пульс": три способа без дополнительного железа.
 * Лицо — фронтальная камера ловит еле заметное покраснение кожи при каждом ударе сердца.
 * Палец — палец закрывает заднюю камеру и вспышку, кровь в пальце меняет пропускание света.
 * Сердце — микрофон прижат к груди и слышит тоны сердца (фонокардиограмма), можно слушать в наушниках.
 * Не медицинский прибор.
 */
public final class PulseTab implements Tab, NightCamera.Callback, NightCamera.RoiListener {
    private static final int OFF = -1;
    private static final int FACE = 0;
    private static final int FINGER = 1;
    private static final int HEART = 2;
    private static final int PREV_H = 240;

    private final Context c;
    private final SonarHub hub;
    private final PermissionHost host;
    private final NightCamera cam;
    private final HeartEngine heart;
    private final PulseEstimator camPulse = new PulseEstimator(10.0);
    private final LinearLayout root;
    private final NightView preview;
    private final TextView big;
    private final TextView sub;
    private final PlotView plot;
    private final TextView hint;
    private final Button[] modeBtns = new Button[3];
    private final Button listenBtn;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final float[] plotBuf = new float[1200];

    private int mode = OFF;
    private boolean shown;
    private boolean listen;
    private volatile String camError;
    private volatile String placement = "";
    private byte[] gray;
    private int[] px;
    private long startMs;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!shown) {
                return;
            }
            tick();
            h.postDelayed(this, 300);
        }
    };

    public PulseTab(Context ctx, SonarHub hub, PermissionHost host) {
        this.c = ctx;
        this.hub = hub;
        this.host = host;
        this.cam = new NightCamera(ctx);
        this.heart = new HeartEngine(ctx);

        root = Ui.col(ctx);
        root.setBackgroundColor(Ui.BG);

        preview = new NightView(ctx);
        preview.setSensorRotation(0, false);
        root.addView(preview, Ui.lp(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        big = Ui.label(ctx, "—", 46f, Ui.ACC);
        big.setTypeface(Typeface.DEFAULT_BOLD);
        big.setGravity(Gravity.CENTER);
        root.addView(big);
        sub = Ui.label(ctx, "", 12f, Ui.DIM);
        sub.setGravity(Gravity.CENTER);
        root.addView(sub);

        plot = new PlotView(ctx);
        plot.setTitle("Сигнал пульса");
        plot.setLineColor(0xFFFF6A6A);
        root.addView(plot, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dpi(ctx, 130)));

        LinearLayout r = Ui.row(ctx);
        String[] names = {"Лицо", "Палец", "Сердце (звук)"};
        for (int i = 0; i < 3; i++) {
            final int m = i;
            modeBtns[i] = Ui.button(ctx, names[i], new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    setMode(mode == m ? OFF : m);
                }
            });
            r.addView(modeBtns[i], Ui.cell(ctx));
        }
        listenBtn = Ui.button(ctx, "🎧", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                listen = !listen;
                Ui.setOn(listenBtn, listen);
                heart.setListen(listen);
                if (listen && !AudioUtil.wiredHeadphones(c)) {
                    android.widget.Toast.makeText(c, "Подключите проводные наушники: в динамик звук не выводится, "
                            + "иначе засвистит", android.widget.Toast.LENGTH_LONG).show();
                }
            }
        });
        r.addView(listenBtn, Ui.lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.5f));
        r.setPadding(Ui.dpi(ctx, 4), 0, Ui.dpi(ctx, 4), 0);
        root.addView(r);

        hint = Ui.label(ctx, HELP, 11f, Ui.DIM);
        hint.setPadding(Ui.dpi(ctx, 8), Ui.dpi(ctx, 4), Ui.dpi(ctx, 8), Ui.dpi(ctx, 6));
        root.addView(hint);
    }

    private static final String HELP = "Выберите способ. «Лицо» — фронтальная камера, лицо в центре, ровный свет, "
            + "не двигайтесь 15 с. «Палец» — подушечкой пальца закройте заднюю камеру и вспышку. «Сердце» — прижмите "
            + "низ телефона (микрофон) к груди слева, тишина вокруг; 🎧 — слушать в проводных наушниках. "
            + "Это не медицинский прибор и не ЭКГ.";

    @Override
    public String title() {
        return "Пульс";
    }

    @Override
    public View view() {
        return root;
    }

    @Override
    public void onShow() {
        shown = true;
        h.removeCallbacks(ticker);
        h.post(ticker);
    }

    @Override
    public void onHide() {
        shown = false;
        h.removeCallbacks(ticker);
        setMode(OFF);
    }

    /** Для автопроверки: включить способ без нажатия. */
    public void debugAction(String act) {
        if ("pulse_face".equals(act)) {
            setMode(FACE);
        } else if ("pulse_finger".equals(act)) {
            setMode(FINGER);
        } else if ("pulse_heart".equals(act)) {
            setMode(HEART);
        }
    }

    private void setMode(final int m) {
        cam.close();
        heart.stop();
        mode = m;
        for (int i = 0; i < 3; i++) {
            Ui.setOn(modeBtns[i], i == m);
        }
        synchronized (camPulse) {
            camPulse.reset();
        }
        camError = null;
        placement = "";
        startMs = System.currentTimeMillis();
        preview.setVisibility(m == FACE || m == FINGER ? View.VISIBLE : View.INVISIBLE);
        if (m == FACE || m == FINGER) {
            host.requireCamera(new Runnable() {
                @Override
                public void run() {
                    if (mode != m || !shown) {
                        return;
                    }
                    cam.setRoiFraction(m == FACE ? 0.3f : 0.6f);
                    cam.open(PulseTab.this, 0, m == FACE);
                    cam.setTorch(m == FINGER);
                }
            });
        } else if (m == HEART) {
            host.requireAudio(new Runnable() {
                @Override
                public void run() {
                    if (mode != m || !shown) {
                        return;
                    }
                    hub.stop();
                    heart.setListen(listen);
                    heart.start();
                }
            });
        }
    }

    private void tick() {
        float sec = (System.currentTimeMillis() - startMs) / 1000f;
        if (mode == OFF) {
            big.setText("—");
            big.setTextColor(Ui.DIM);
            sub.setText("Выберите способ измерения внизу");
            return;
        }
        PulseEstimator pe;
        String err;
        int n;
        if (mode == HEART) {
            synchronized (heart.dsp) {
                heart.dsp.pulse.update();
                n = heart.dsp.viewOrdered(plotBuf);
                pe = copyResult(heart.dsp.pulse);
            }
            err = heart.error();
            float mx = 1e-6f;
            for (int i = 0; i < n; i++) {
                mx = Math.max(mx, Math.abs(plotBuf[i]));
            }
            plot.setTitle("Звук сердца, 25–150 Гц (последние 5 с)");
            plot.setRange(0f, 1f, -mx * 1.1f, mx * 1.1f);
            plot.setGrid(0.2f, "", mx, "");
        } else {
            synchronized (camPulse) {
                camPulse.update();
                n = Math.min(camPulse.waveN, plotBuf.length);
                System.arraycopy(camPulse.wave, 0, plotBuf, 0, n);
                pe = copyResult(camPulse);
            }
            err = camError;
            plot.setTitle(mode == FACE ? "Цвет кожи лица (усилено)" : "Свет через палец");
            plot.setRange(0f, 1f, -3f, 3f);
            plot.setGrid(0.2f, "", 1f, "");
        }
        if (n > 2) {
            plot.setDataExtent(0f, 1f);
            plot.setData(plotBuf, n);
        }
        if (err != null) {
            big.setText("—");
            big.setTextColor(Ui.BAD);
            sub.setText("Ошибка: " + err);
            return;
        }
        boolean ok = !Double.isNaN(pe.bpm) && pe.quality >= 0.45 && sec > 6;
        if (ok) {
            big.setText(String.format(Locale.US, "%.0f уд/мин", pe.bpm));
            big.setTextColor(pe.quality > 0.7 ? Ui.ACC : Ui.WARN);
        } else {
            big.setText(sec < 6 ? "…" : "?");
            big.setTextColor(Ui.DIM);
        }
        String q = String.format(Locale.US, "сигнал %.0f%%", pe.quality * 100);
        if (mode == HEART) {
            sub.setText(String.format(Locale.US, "%s · %s · %.0f с", heart.source(), q, sec)
                    + (ok ? "" : " · прижмите микрофон плотнее, тишина"));
        } else {
            sub.setText(String.format(Locale.US, "%s · %.0f с", q, sec) + (placement.isEmpty() ? "" : " · " + placement));
        }
    }

    private static PulseEstimator copyResult(PulseEstimator src) {
        PulseEstimator r = new PulseEstimator(1);
        r.bpm = src.bpm;
        r.quality = src.quality;
        return r;
    }

    // ---- камера (поток камеры) ----

    @Override
    public void onRoi(float y, float u, float v, long timeMs) {
        float du = u - 128;
        float dv = v - 128;
        float red = y + 1.402f * dv;
        float green = y - 0.344f * du - 0.714f * dv;
        double sample;
        if (mode == FINGER) {
            // палец со вспышкой: сквозь него проходит в основном красный
            placement = red < 60 || red < green * 1.4f ? "закройте пальцем камеру И вспышку" : "";
            sample = -y;
        } else {
            placement = y < 40 ? "мало света на лице" : (y > 235 ? "пересвет — отвернитесь от лампы" : "");
            // кровь поглощает зелёный сильнее всего: при ударе зелёного меньше
            sample = -green;
        }
        synchronized (camPulse) {
            camPulse.add(timeMs, sample);
        }
    }

    @Override
    public void onFrame(byte[] y, int w, int hgt) {
        int rot = cam.sensorOrientation();
        int uw = DepthMath.uprightW(w, hgt, rot);
        int uh = DepthMath.uprightH(w, hgt, rot);
        int dw = Math.max(1, Math.round(PREV_H * uw / (float) uh));
        if (gray == null || gray.length != dw * PREV_H) {
            gray = new byte[dw * PREV_H];
            px = new int[dw * PREV_H];
        }
        DepthMath.sampleUpright(y, w, hgt, rot, gray, dw, PREV_H, 1);
        boolean mirror = cam.isFront();
        for (int vy = 0; vy < PREV_H; vy++) {
            for (int vx = 0; vx < dw; vx++) {
                int g = gray[vy * dw + (mirror ? dw - 1 - vx : vx)] & 0xFF;
                px[vy * dw + vx] = 0xFF000000 | (g << 16) | (g << 8) | g;
            }
        }
        // рамка: где считается сигнал
        float f = mode == FACE ? 0.3f : 0.6f;
        int x0 = (int) (dw * (1 - f) / 2);
        int x1 = (int) (dw * (1 + f) / 2);
        int y0 = (int) (PREV_H * (1 - f) / 2);
        int y1 = (int) (PREV_H * (1 + f) / 2);
        for (int x = x0; x < x1; x++) {
            px[y0 * dw + x] = 0xFFFF5A5A;
            px[(y1 - 1) * dw + x] = 0xFFFF5A5A;
        }
        for (int yy = y0; yy < y1; yy++) {
            px[yy * dw + x0] = 0xFFFF5A5A;
            px[yy * dw + x1 - 1] = 0xFFFF5A5A;
        }
        preview.setFrame(px, dw, PREV_H);
    }

    @Override
    public void onInfo(String text) {
        // не используется
    }

    @Override
    public void onError(String message) {
        camError = message;
    }
}
