package kz.bulaevo.radar.ui;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import kz.bulaevo.radar.PermissionHost;
import kz.bulaevo.radar.SonarHub;
import kz.bulaevo.radar.ai.DepthModel;
import kz.bulaevo.radar.camera.NightCamera;
import kz.bulaevo.radar.dsp.DepthMath;

/**
 * Вкладка "Глубина": нейросеть по кадру камеры оценивает, что ближе, что дальше (карта глубины цветом),
 * а сонар даёт масштаб в метрах. Работает и в ИК-режиме камеры (с ИК-фонариком — в темноте).
 */
public final class DepthTab implements Tab, NightCamera.Callback {
    private static final String[] MODE_NAMES = {"Авто", "Ночь", "Ночь+", "ИК"};
    private static final String[] VIEW_NAMES = {"Смесь", "Глубина", "Камера"};
    /** Размер картинки на экране (по высоте); ширина — по пропорциям кадра. */
    private static final int DISP_H = 320;

    private final Context c;
    private final SonarHub hub;
    private final PermissionHost host;
    private final NightCamera cam;
    private final NightView view;
    private final FrameLayout root;
    private final Button dlBtn;
    private final Button modeBtn;
    private final Button viewBtn;
    private final Button sonarBtn;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final DepthModel model = new DepthModel();
    private final AtomicBoolean busy = new AtomicBoolean(false);

    private HandlerThread worker;
    private Handler wh;
    private int mode;
    private int viewMode;
    private boolean sonarOn;
    private boolean front;
    private boolean shown;
    private volatile boolean downloading;
    private volatile String status = "";
    private volatile String error;
    private volatile String modeInfo = "";
    private volatile float msPerRun;
    private volatile float centerRel = Float.NaN;
    private volatile float centerMeters = Float.NaN;

    // кадр для нейросети (копия, пока нейросеть занята — новые кадры пропускаются)
    private byte[] pending;
    private int pw;
    private int ph;
    private int prot;
    private byte[] rgb;
    private float[] depth;
    private float[] scratch;
    private byte[] gray;
    private int[] px;
    private float lo = Float.NaN;
    private float hi = Float.NaN;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!shown) {
                return;
            }
            String l1;
            if (error != null) {
                l1 = "Ошибка: " + error;
            } else if (model.loaded()) {
                l1 = String.format(Locale.US, "Нейросеть: %.0f мс на кадр · камера: %s", msPerRun, modeInfo);
            } else {
                l1 = status;
            }
            String l2 = "";
            String l3 = "";
            if (model.loaded()) {
                if (!Float.isNaN(centerMeters)) {
                    l2 = String.format(Locale.US, "Перекрестие: ≈ %.1f м (масштаб по сонару)", centerMeters);
                } else if (!Float.isNaN(centerRel)) {
                    l2 = String.format(Locale.US, "Перекрестие: близость %.0f%% (красное — близко, синее — далеко)",
                            centerRel * 100f);
                }
                if (sonarOn) {
                    float nr = hub.nearest();
                    l3 = hub.isRunning()
                            ? (Float.isNaN(nr) ? "Сонар: эха нет — метры не считаются"
                            : String.format(Locale.US, "Сонар: ближайшее %.2f м", nr))
                            : "Сонар: запуск…";
                } else {
                    l3 = "Метры: включите «Сонар»";
                }
            }
            view.setHud(l1, l2, l3);
            dlBtn.setVisibility(model.loaded() || downloading ? View.GONE : View.VISIBLE);
            h.postDelayed(this, 250);
        }
    };

    public DepthTab(Context ctx, SonarHub hub, PermissionHost host) {
        this.c = ctx;
        this.hub = hub;
        this.host = host;
        this.cam = new NightCamera(ctx);

        root = new FrameLayout(ctx);
        root.setBackgroundColor(0xFF000000);
        view = new NightView(ctx);
        view.setSensorRotation(0, false);
        root.addView(view, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout panel = Ui.col(ctx);
        panel.setBackgroundColor(0xB0000000);
        panel.setPadding(Ui.dpi(ctx, 4), Ui.dpi(ctx, 4), Ui.dpi(ctx, 4), Ui.dpi(ctx, 4));
        dlBtn = Ui.button(ctx, "Скачать нейросеть глубины (один раз, нужен интернет)", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startDownload();
            }
        });
        panel.addView(dlBtn);
        LinearLayout r = Ui.row(ctx);
        modeBtn = Ui.button(ctx, "Режим: " + MODE_NAMES[mode], new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mode = (mode + 1) % MODE_NAMES.length;
                modeBtn.setText("Режим: " + MODE_NAMES[mode]);
                cam.setMode(mode);
            }
        });
        viewBtn = Ui.button(ctx, "Вид: " + VIEW_NAMES[viewMode], new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                viewMode = (viewMode + 1) % VIEW_NAMES.length;
                viewBtn.setText("Вид: " + VIEW_NAMES[viewMode]);
            }
        });
        sonarBtn = Ui.button(ctx, "Сонар", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setSonar(!sonarOn);
            }
        });
        Button flipBtn = Ui.button(ctx, "⇄", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                front = !front;
                Ui.setOn((Button) v, front);
                lo = Float.NaN;
                hi = Float.NaN;
                if (shown) {
                    cam.open(DepthTab.this, mode, front);
                }
                android.widget.Toast.makeText(c, front ? "Фронтальная камера" : "Задняя камера",
                        android.widget.Toast.LENGTH_SHORT).show();
            }
        });
        flipBtn.setTextSize(18f);
        r.addView(flipBtn, Ui.lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.5f));
        r.addView(modeBtn, Ui.cell(ctx));
        r.addView(viewBtn, Ui.cell(ctx));
        r.addView(sonarBtn, Ui.cell(ctx));
        panel.addView(r);
        root.addView(panel, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
    }

    @Override
    public String title() {
        return "Глуб.";
    }

    @Override
    public View view() {
        return root;
    }

    @Override
    public void onShow() {
        shown = true;
        error = null;
        worker = new HandlerThread("depth");
        worker.start();
        wh = new Handler(worker.getLooper());
        if (!model.loaded()) {
            if (DepthModel.downloaded(c)) {
                loadModel();
            } else if (!downloading) {
                status = "Нейросети ещё нет: нажмите «Скачать» внизу (около 20–60 МБ, лучше по Wi-Fi)";
            }
        }
        host.requireCamera(new Runnable() {
            @Override
            public void run() {
                if (shown) {
                    cam.open(DepthTab.this, mode, front);
                }
            }
        });
        h.removeCallbacks(ticker);
        h.post(ticker);
    }

    @Override
    public void onHide() {
        shown = false;
        h.removeCallbacks(ticker);
        cam.close();
        if (sonarOn) {
            setSonar(false);
        }
        if (worker != null) {
            final HandlerThread t = worker;
            wh.post(new Runnable() {
                @Override
                public void run() {
                    // модель остаётся в памяти: при возврате на вкладку не грузить заново
                    t.quitSafely();
                }
            });
            worker = null;
            wh = null;
        }
    }

    /** Для автопроверки: скачать модель без нажатия. */
    public void debugAction(String act) {
        if ("depth".equals(act) && !model.loaded() && !DepthModel.downloaded(c)) {
            startDownload();
        }
    }

    private void setSonar(boolean on) {
        sonarOn = on;
        Ui.setOn(sonarBtn, on);
        if (on) {
            android.widget.Toast.makeText(c, "Метры будут точнее, если нижний край телефона (динамик) "
                    + "смотрит туда же, куда и камера: держите телефон наклонённым к препятствию.",
                    android.widget.Toast.LENGTH_LONG).show();
            hub.start();
        } else {
            hub.stop();
        }
    }

    // ---- модель ----

    private void startDownload() {
        if (downloading) {
            return;
        }
        downloading = true;
        error = null;
        status = "Скачивание нейросети…";
        new Thread(new Runnable() {
            @Override
            public void run() {
                String err = DepthModel.download(c, new DepthModel.Progress() {
                    @Override
                    public void on(long done, long total) {
                        status = total > 0
                                ? String.format(Locale.US, "Скачивание нейросети: %.0f из %.0f МБ", done / 1e6, total / 1e6)
                                : String.format(Locale.US, "Скачивание нейросети: %.0f МБ", done / 1e6);
                    }
                });
                downloading = false;
                if (err != null) {
                    error = "не скачалась нейросеть (" + err + "). Проверьте интернет и нажмите ещё раз.";
                    return;
                }
                h.post(new Runnable() {
                    @Override
                    public void run() {
                        if (shown) {
                            loadModel();
                        }
                    }
                });
            }
        }, "depth-download").start();
    }

    private void loadModel() {
        status = "Загрузка нейросети…";
        Handler w = wh;
        if (w == null) {
            return;
        }
        w.post(new Runnable() {
            @Override
            public void run() {
                String err = model.load(c, Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors())));
                if (err != null) {
                    error = err;
                    return;
                }
                rgb = new byte[model.inW * model.inH * 3];
                depth = new float[model.outW * model.outH];
                scratch = new float[depth.length];
                status = "Нейросеть готова";
            }
        });
    }

    // ---- кадры (поток камеры) ----

    @Override
    public void onFrame(byte[] y, int w, int hgt) {
        int rot = cam.sensorOrientation();
        Handler w2 = wh;
        if (model.loaded() && w2 != null) {
            if (!busy.compareAndSet(false, true)) {
                return; // нейросеть ещё считает прошлый кадр
            }
            if (pending == null || pending.length != y.length) {
                pending = new byte[y.length];
            }
            System.arraycopy(y, 0, pending, 0, y.length);
            pw = w;
            ph = hgt;
            prot = rot;
            w2.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        infer();
                    } catch (Throwable t) {
                        error = "нейросеть: " + t.getMessage();
                    } finally {
                        busy.set(false);
                    }
                }
            });
        } else {
            // нейросети пока нет — просто показываем камеру
            render(y, w, hgt, rot, false);
        }
    }

    private void infer() {
        long t0 = SystemClock.elapsedRealtime();
        DepthMath.sampleUpright(pending, pw, ph, prot, rgb, model.inW, model.inH, 3);
        model.run(rgb, depth);
        float ms = SystemClock.elapsedRealtime() - t0;
        msPerRun = msPerRun == 0 ? ms : msPerRun * 0.8f + ms * 0.2f;

        int n = model.outW * model.outH;
        float p2 = DepthMath.percentile(depth, n, 0.02f, scratch);
        float p98 = DepthMath.percentile(depth, n, 0.98f, scratch);
        // плавно, чтобы цвета не мигали от кадра к кадру
        lo = Float.isNaN(lo) ? p2 : lo * 0.7f + p2 * 0.3f;
        hi = Float.isNaN(hi) ? p98 : hi * 0.7f + p98 * 0.3f;

        float dc = DepthMath.windowMean(depth, model.outW, model.outH, 0.5f, 0.5f, 2);
        centerRel = hi > lo ? Math.max(0f, Math.min(1f, (dc - lo) / (hi - lo))) : Float.NaN;
        float nr = sonarOn && hub.isRunning() ? hub.nearest() : Float.NaN;
        // обратная глубина модели сдвинута на неизвестную постоянную; считаем от минимума кадра
        float base = Math.min(lo, p2);
        centerMeters = DepthMath.metersFromSonar(nr, p98 - base + 1e-3f, dc - base + 1e-3f);
        render(pending, pw, ph, prot, true);
    }

    /** Нарисовать вертикальный кадр: серый из камеры, сверху — раскраска глубины. Вызывается из фоновых потоков. */
    private synchronized void render(byte[] y, int w, int hgt, int rot, boolean withDepth) {
        int uw = DepthMath.uprightW(w, hgt, rot);
        int uh = DepthMath.uprightH(w, hgt, rot);
        int dh = DISP_H;
        int dw = Math.max(1, Math.round(DISP_H * uw / (float) uh));
        if (gray == null || gray.length != dw * dh) {
            gray = new byte[dw * dh];
            px = new int[dw * dh];
        }
        DepthMath.sampleUpright(y, w, hgt, rot, gray, dw, dh, 1);
        boolean showDepth = withDepth && viewMode != 2 && hi > lo;
        float mix = viewMode == 1 ? 1f : 0.65f;
        for (int vy = 0; vy < dh; vy++) {
            int my = showDepth ? Math.min(model.outH - 1, vy * model.outH / dh) : 0;
            for (int vx = 0; vx < dw; vx++) {
                int i = vy * dw + vx;
                int g = gray[i] & 0xFF;
                if (!showDepth) {
                    px[i] = 0xFF000000 | (g << 16) | (g << 8) | g;
                    continue;
                }
                int mx = Math.min(model.outW - 1, vx * model.outW / dw);
                float t = (depth[my * model.outW + mx] - lo) / (hi - lo);
                int col = DepthMath.color(t);
                int r = (int) (((col >> 16) & 0xFF) * mix + g * (1 - mix));
                int gg = (int) (((col >> 8) & 0xFF) * mix + g * (1 - mix));
                int b = (int) ((col & 0xFF) * mix + g * (1 - mix));
                px[i] = 0xFF000000 | (r << 16) | (gg << 8) | b;
            }
        }
        if (cam.isFront()) {
            // фронтальная камера — как в зеркале, привычнее для селфи
            for (int vy = 0; vy < dh; vy++) {
                int a = vy * dw;
                int b = a + dw - 1;
                while (a < b) {
                    int t = px[a];
                    px[a++] = px[b];
                    px[b--] = t;
                }
            }
        }
        // перекрестие в центре
        int cx = dw / 2;
        int cy = dh / 2;
        for (int k = -8; k <= 8; k++) {
            if (Math.abs(k) < 3) {
                continue;
            }
            px[cy * dw + cx + k] = 0xFFFFFFFF;
            px[(cy + k) * dw + cx] = 0xFFFFFFFF;
        }
        view.setFrame(px, dw, dh);
    }

    @Override
    public void onInfo(String text) {
        modeInfo = text;
    }

    @Override
    public void onError(String message) {
        error = message;
    }
}
