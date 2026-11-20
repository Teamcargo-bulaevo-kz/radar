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
import kz.bulaevo.radar.ai.ObjectDetector;
import kz.bulaevo.radar.dsp.Guide;
import kz.bulaevo.radar.camera.NightCamera;
import kz.bulaevo.radar.dsp.DepthMath;

/**
 * Вкладка "Глубина": нейросеть по кадру камеры оценивает, что ближе, что дальше (карта глубины цветом),
 * а сонар даёт масштаб в метрах. Работает и в ИК-режиме камеры (с ИК-фонариком — в темноте).
 */
public final class DepthTab implements Tab, NightCamera.Callback {
    private static final String[] MODE_NAMES = {"Авто", "Ночь", "Ночь+", "ИК"};
    private static final String[] VIEW_NAMES = {"Смесь", "Глубина", "Контуры", "Рельеф", "Камера"};
    private static final int VIEW_CONTOURS = 2;
    private static final int VIEW_RELIEF = 3;
    private static final int VIEW_CAMERA = 4;
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
    // ИИ-гид: распознавание предметов + глубина + сонар + голос
    private final ObjectDetector det = new ObjectDetector();
    private Button guideBtn;
    private volatile boolean guideOn;
    private volatile boolean detDownloading;
    private volatile java.util.List<Guide.Item> items = new java.util.ArrayList<>();
    private byte[] detRgb;
    private android.speech.tts.TextToSpeech tts;
    private volatile boolean ttsReady;
    private long lastSpeakMs;
    private String lastSpoken = "";
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
    private float[] edge;
    private float[] shade;
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
            if (guideOn) {
                l3 = guideLine();
                speakGuide();
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
        guideBtn = Ui.button(ctx, "ИИ-гид", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setGuide(!guideOn);
            }
        });
        panel.addView(r);
        LinearLayout r2 = Ui.row(ctx);
        r2.addView(sonarBtn, Ui.cell(ctx));
        r2.addView(guideBtn, Ui.cell(ctx));
        panel.addView(r2);
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
        if (tts != null) {
            tts.stop();
        }
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
        if (!model.loaded() && !DepthModel.downloaded(c) && !downloading) {
            startDownload();
        }
        if ("depth_ai".equals(act) && !guideOn) {
            setGuide(true);
        }
    }

    // ---- ИИ-гид ----

    private void setGuide(boolean on) {
        guideOn = on;
        Ui.setOn(guideBtn, on);
        if (!on) {
            items = new java.util.ArrayList<>();
            view.setBoxes(new float[0], new String[0]);
            if (tts != null) {
                tts.stop();
            }
            return;
        }
        android.widget.Toast.makeText(c, hub.st.quiet
                ? "ИИ-гид в тихом режиме: молча, вибрация — когда предмет близко прямо по курсу"
                : "ИИ-гид: называет вслух, что впереди и как далеко. Для метров включите «Сонар»",
                android.widget.Toast.LENGTH_LONG).show();
        if (!hub.st.quiet && tts == null) {
            tts = new android.speech.tts.TextToSpeech(c, new android.speech.tts.TextToSpeech.OnInitListener() {
                @Override
                public void onInit(int st) {
                    if (st == android.speech.tts.TextToSpeech.SUCCESS && tts != null) {
                        int r = tts.setLanguage(new Locale("ru", "RU"));
                        ttsReady = r != android.speech.tts.TextToSpeech.LANG_MISSING_DATA
                                && r != android.speech.tts.TextToSpeech.LANG_NOT_SUPPORTED;
                    }
                }
            });
        }
        if (det.loaded()) {
            return;
        }
        if (ObjectDetector.downloaded(c)) {
            loadDetector();
            return;
        }
        if (detDownloading) {
            return;
        }
        detDownloading = true;
        status = "Скачивание нейросети предметов…";
        new Thread(new Runnable() {
            @Override
            public void run() {
                String err = ObjectDetector.download(c, null);
                detDownloading = false;
                if (err != null) {
                    error = "не скачалась нейросеть предметов (" + err + ")";
                    return;
                }
                h.post(new Runnable() {
                    @Override
                    public void run() {
                        loadDetector();
                    }
                });
            }
        }, "objects-download").start();
    }

    private void loadDetector() {
        Handler w = wh;
        if (w == null) {
            return;
        }
        w.post(new Runnable() {
            @Override
            public void run() {
                String err = det.load(c);
                if (err != null) {
                    error = err;
                } else {
                    detRgb = new byte[det.inW * det.inH * 3];
                }
            }
        });
    }

    private String guideLine() {
        if (detDownloading) {
            return "ИИ-гид: скачивается нейросеть предметов…";
        }
        if (!det.loaded()) {
            return "ИИ-гид: загрузка…";
        }
        java.util.List<Guide.Item> it = items;
        if (it.isEmpty()) {
            return "ИИ-гид: знакомых предметов в кадре нет";
        }
        StringBuilder sb = new StringBuilder("ИИ: ");
        for (int i = 0; i < Math.min(3, it.size()); i++) {
            Guide.Item g = it.get(i);
            sb.append(i > 0 ? " · " : "").append(g.name).append(' ').append(Float.isNaN(g.meters)
                    ? g.sideWord() : String.format(Locale.US, "%.1f м %s", g.meters, g.sideWord()));
        }
        return sb.toString();
    }

    /** Раз в несколько секунд — главное вслух (или вибрацией в тихом режиме). */
    private void speakGuide() {
        java.util.List<Guide.Item> it = items;
        long now = System.currentTimeMillis();
        if (it.isEmpty() || now - lastSpeakMs < 3500) {
            return;
        }
        Guide.Item top = it.get(0);
        if (hub.st.quiet) {
            boolean close = top.side == 0 && (Float.isNaN(top.meters) ? top.near > 0.8f : top.meters < 1.2f);
            if (close) {
                Haptics.pulse(c, 80);
                lastSpeakMs = now;
            }
            return;
        }
        String phrase = top.phrase();
        // то же самое не повторяем чаще, чем раз в 10 секунд
        if (phrase.equals(lastSpoken) && now - lastSpeakMs < 10000) {
            return;
        }
        if (tts != null && ttsReady) {
            tts.speak(phrase, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "guide");
            lastSpoken = phrase;
            lastSpeakMs = now;
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
        if (guideOn && det.loaded() && detRgb != null) {
            runGuide(nr);
        }
        render(pending, pw, ph, prot, true);
    }

    /** Распознать предметы на том же кадре и объединить с глубиной и сонаром (поток нейросети). */
    private void runGuide(float sonar) {
        DepthMath.sampleUpright(pending, pw, ph, prot, detRgb, det.inW, det.inH, 3);
        java.util.List<ObjectDetector.Det> ds = det.detect(detRgb, 0.5f);
        int n = Math.min(ds.size(), 8);
        float[] boxes = new float[n * 4];
        String[] names = new String[n];
        for (int i = 0; i < n; i++) {
            ObjectDetector.Det d = ds.get(i);
            boxes[i * 4] = d.l;
            boxes[i * 4 + 1] = d.t;
            boxes[i * 4 + 2] = d.r;
            boxes[i * 4 + 3] = d.b;
            names[i] = d.name;
        }
        java.util.List<Guide.Item> res = Guide.describe(boxes, names, n, depth, model.outW, model.outH, lo, hi, sonar);
        items = res;
        boolean mirror = cam.isFront();
        float[] vb = new float[res.size() * 4];
        String[] vl = new String[res.size()];
        for (int i = 0; i < res.size(); i++) {
            Guide.Item g = res.get(i);
            vb[i * 4] = mirror ? 1 - g.r : g.l;
            vb[i * 4 + 1] = g.t;
            vb[i * 4 + 2] = mirror ? 1 - g.l : g.r;
            vb[i * 4 + 3] = g.b;
            vl[i] = Float.isNaN(g.meters) ? g.name : String.format(Locale.US, "%s %.1f м", g.name, g.meters);
        }
        view.setBoxes(vb, vl);
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
        boolean showDepth = withDepth && viewMode != VIEW_CAMERA && hi > lo;
        if (showDepth && (viewMode == VIEW_CONTOURS || viewMode == VIEW_RELIEF)) {
            int n = model.outW * model.outH;
            if (edge == null || edge.length != n) {
                edge = new float[n];
                shade = new float[n];
            }
            DepthMath.structure(depth, model.outW, model.outH, lo, hi, edge, shade);
        }
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
                int mi = my * model.outW + mx;
                float t = (depth[mi] - lo) / (hi - lo);
                int col = DepthMath.color(t);
                if (viewMode == VIEW_CONTOURS) {
                    // тёмная картинка + тонкие контуры камеры + яркие границы предметов цветом расстояния
                    float ce = camEdge(gray, dw, dh, vx, vy);
                    float e = edge[mi];
                    int base = (int) (g * 0.25f + ce * 120f);
                    int r = Math.min(255, (int) (base * (1 - e) + ((col >> 16) & 0xFF) * e));
                    int gg = Math.min(255, (int) (base * (1 - e) + ((col >> 8) & 0xFF) * e));
                    int b = Math.min(255, (int) (base * (1 - e) + (col & 0xFF) * e));
                    px[i] = 0xFF000000 | (r << 16) | (gg << 8) | b;
                    continue;
                }
                if (viewMode == VIEW_RELIEF) {
                    int v = (int) (shade[mi] * 230f + g * 0.1f);
                    v = Math.min(255, v);
                    px[i] = 0xFF000000 | (v << 16) | (v << 8) | Math.min(255, v + 12);
                    continue;
                }
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

    /** Сила контура на картинке камеры (оператор Собеля), 0..1. */
    private static float camEdge(byte[] g, int w, int h, int x, int y) {
        if (x < 1 || y < 1 || x >= w - 1 || y >= h - 1) {
            return 0f;
        }
        int a = g[(y - 1) * w + x - 1] & 0xFF;
        int b = g[(y - 1) * w + x] & 0xFF;
        int c = g[(y - 1) * w + x + 1] & 0xFF;
        int d = g[y * w + x - 1] & 0xFF;
        int f = g[y * w + x + 1] & 0xFF;
        int p = g[(y + 1) * w + x - 1] & 0xFF;
        int q = g[(y + 1) * w + x] & 0xFF;
        int r = g[(y + 1) * w + x + 1] & 0xFF;
        int gx = (c + 2 * f + r) - (a + 2 * d + p);
        int gy = (p + 2 * q + r) - (a + 2 * b + c);
        return Math.min(1f, (Math.abs(gx) + Math.abs(gy)) / 400f);
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
