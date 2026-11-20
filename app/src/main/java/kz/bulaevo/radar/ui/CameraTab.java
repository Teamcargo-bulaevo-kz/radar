package kz.bulaevo.radar.ui;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import kz.bulaevo.radar.PermissionHost;
import kz.bulaevo.radar.SonarHub;
import kz.bulaevo.radar.camera.IrPulser;
import kz.bulaevo.radar.camera.NightCamera;
import kz.bulaevo.radar.dsp.IrLockIn;
import kz.bulaevo.radar.dsp.NightEnhancer;

/**
 * Вкладка "Камера": ночное видение из слабого света (накопление кадров, длинная выдержка, растяжка яркости),
 * контуры, подсветка движения в кадре, врезка радара и снимок в галерею.
 * Помните: камера телефона видит только свет. В полной темноте картинки не будет — там работает сонар.
 */
public final class CameraTab implements Tab, NightCamera.Callback, SensorEventListener {
    private static final String[] MODE_NAMES = {"Авто", "Ночь", "Ночь+", "ИК"};
    private static final String[] PAL_NAMES = {"Серая", "Ночное зелёное", "Тепловая"};
    private static final float[] ACC_ALPHA = {1f, 0.25f, 0.12f, 0.06f};
    private static final String[] ACC_NAMES = {"×1", "×4", "×8", "×16"};

    private final Context c;
    private final SonarHub hub;
    private final PermissionHost host;
    private final NightCamera cam;
    private final FrameLayout root;
    private final NightView view;
    private final RadarMapView mini;
    private final Button modeBtn;
    private final Button palBtn;
    private final Button accBtn;
    private final Button edgeBtn;
    private final Button motBtn;
    private final Button radarBtn;
    private boolean front;
    private final Button irBtn;
    private final IrPulser ir;
    private volatile boolean irOn;
    private IrLockIn lock;
    private volatile int irCycles;
    private volatile int irVerdict;
    private volatile float irT;
    private volatile float irDiff;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final SensorManager sm;

    private NightEnhancer enh;
    private int[] pixels;
    private int mode = 1;
    private int pal = 1;
    private int acc = 1;
    private boolean edges;
    private boolean motion;
    private boolean radarOn;
    private boolean shown;
    private boolean registeredLight;
    private volatile float lux = -1f;
    private volatile String modeInfo = "";
    private volatile String error;
    private volatile float luma;
    private volatile float motionFrac;
    private volatile long frames;
    private long fpsT0;
    private long fpsF0;
    private volatile float fps;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!shown) {
                return;
            }
            long now = System.currentTimeMillis();
            if (now - fpsT0 >= 1000) {
                fps = (frames - fpsF0) * 1000f / (now - fpsT0);
                fpsF0 = frames;
                fpsT0 = now;
            }
            String l1;
            if (error != null) {
                l1 = "Ошибка: " + error;
            } else {
                l1 = "Режим: " + modeInfo + " · " + String.format(Locale.US, "%.0f", fps) + " кадр/с";
            }
            String l2 = String.format(Locale.US, "Яркость кадра %.0f/255 · накопление %s%s", luma, ACC_NAMES[acc],
                    lux >= 0 ? String.format(Locale.US, " · свет %.1f лк", lux) : "");
            String l3 = motion ? String.format(Locale.US, "Движение в кадре: %.0f%%", motionFrac * 100f) : "";
            if (irOn) {
                l3 = irStatus();
            }
            String l4 = "";
            if (radarOn) {
                float nr = hub.nearest();
                l4 = hub.isRunning()
                        ? (Float.isNaN(nr) ? "Сонар: эха нет" : String.format(Locale.US, "Сонар: ближайшее %.2f м", nr))
                        : "Сонар: запуск…";
                mini.setAzimuth(hub.orient.azimuth(), hub.isRunning());
                mini.setNearest(nr);
            }
            view.setHud(l1, l2, l3, l4);
            h.postDelayed(this, 250);
        }
    };

    public CameraTab(Context ctx, SonarHub hub, PermissionHost host) {
        this.c = ctx;
        this.hub = hub;
        this.host = host;
        this.cam = new NightCamera(ctx);
        this.ir = new IrPulser(ctx);
        this.sm = (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);

        root = new FrameLayout(ctx);
        root.setBackgroundColor(0xFF000000);
        view = new NightView(ctx);
        root.addView(view, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        mini = new RadarMapView(ctx);
        mini.setMap(hub.map);
        mini.setMini(true);
        mini.setVisibility(View.GONE);
        int ms = Ui.dpi(ctx, 150);
        FrameLayout.LayoutParams mp = new FrameLayout.LayoutParams(ms, ms, Gravity.TOP | Gravity.END);
        mp.setMargins(0, Ui.dpi(ctx, 6), Ui.dpi(ctx, 6), 0);
        root.addView(mini, mp);

        LinearLayout panel = Ui.col(ctx);
        panel.setBackgroundColor(0xB0000000);
        panel.setPadding(Ui.dpi(ctx, 4), Ui.dpi(ctx, 4), Ui.dpi(ctx, 4), Ui.dpi(ctx, 4));
        LinearLayout r1 = Ui.row(ctx);
        LinearLayout r2 = Ui.row(ctx);

        modeBtn = Ui.button(ctx, "Режим: " + MODE_NAMES[mode], new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mode = (mode + 1) % MODE_NAMES.length;
                modeBtn.setText("Режим: " + MODE_NAMES[mode]);
                cam.setMode(mode);
                if (mode == 3) {
                    // ИК: цвета нет, нужна максимальная чувствительность — серая палитра и сильное накопление
                    pal = 0;
                    acc = 2;
                    palBtn.setText("Цвет: " + PAL_NAMES[pal]);
                    accBtn.setText("Накопление " + ACC_NAMES[acc]);
                    if (enh != null) {
                        enh.palette = pal;
                        enh.alpha = ACC_ALPHA[acc];
                    }
                    Toast.makeText(c, "Режим ИК: посветите ИК-фонариком (850 нм). Глазу почти не видно, камере — видно.",
                            Toast.LENGTH_LONG).show();
                }
                if (enh != null) {
                    enh.reset();
                }
            }
        });
        palBtn = Ui.button(ctx, "Цвет: " + PAL_NAMES[pal], new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pal = (pal + 1) % PAL_NAMES.length;
                palBtn.setText("Цвет: " + PAL_NAMES[pal]);
                if (enh != null) {
                    enh.palette = pal;
                }
            }
        });
        accBtn = Ui.button(ctx, "Накопление " + ACC_NAMES[acc], new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                acc = (acc + 1) % ACC_NAMES.length;
                accBtn.setText("Накопление " + ACC_NAMES[acc]);
                if (enh != null) {
                    enh.alpha = ACC_ALPHA[acc];
                }
            }
        });
        edgeBtn = Ui.button(ctx, "Контуры", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                edges = !edges;
                Ui.setOn(edgeBtn, edges);
                if (enh != null) {
                    enh.edges = edges;
                }
            }
        });
        motBtn = Ui.button(ctx, "Движ.", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                motion = !motion;
                Ui.setOn(motBtn, motion);
                if (enh != null) {
                    enh.motionMask = motion;
                }
            }
        });
        radarBtn = Ui.button(ctx, "Радар", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setRadar(!radarOn);
            }
        });
        irBtn = Ui.button(ctx, "ИК", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setIr(!irOn);
            }
        });
        Button snap = Ui.button(ctx, "Снимок", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                CameraTab.this.host.requireStorage(new Runnable() {
                    @Override
                    public void run() {
                        saveSnapshot();
                    }
                });
            }
        });
        final Button flipBtn = Ui.button(ctx, "⇄", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                front = !front;
                Ui.setOn((Button) v, front);
                enh = null; // другая камера — другой поворот и размер кадра
                if (lock != null) {
                    lock.reset();
                }
                if (shown) {
                    cam.open(CameraTab.this, mode, front);
                    if (irOn) {
                        cam.setAeLock(true);
                    }
                }
                Toast.makeText(c, front ? "Фронтальная камера" : "Задняя камера", Toast.LENGTH_SHORT).show();
            }
        });
        r1.addView(flipBtn, Ui.lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.5f));
        r1.addView(modeBtn, Ui.lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.1f));
        r1.addView(palBtn, Ui.lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.5f));
        r1.addView(accBtn, Ui.lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.3f));
        r2.addView(edgeBtn, Ui.cell(ctx));
        r2.addView(motBtn, Ui.cell(ctx));
        r2.addView(radarBtn, Ui.cell(ctx));
        r2.addView(irBtn, Ui.cell(ctx));
        r2.addView(snap, Ui.cell(ctx));
        panel.addView(r1);
        panel.addView(r2);
        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        root.addView(panel, pp);
    }

    private void setRadar(boolean on) {
        radarOn = on;
        Ui.setOn(radarBtn, on);
        mini.setVisibility(on ? View.VISIBLE : View.GONE);
        hub.setMapping(on);
        if (on) {
            hub.start();
        } else {
            hub.stop();
        }
    }

    // ---- ИК-тест: ИК-порт мигает, кадры «со светом» и «без» сравниваются ----

    private void setIr(boolean on) {
        if (on && !ir.available()) {
            Toast.makeText(c, "В этом телефоне нет ИК-порта", Toast.LENGTH_LONG).show();
            return;
        }
        irOn = on;
        Ui.setOn(irBtn, on);
        if (on) {
            irCycles = 0;
            irVerdict = 0;
            irT = 0f;
            irDiff = 0f;
            if (lock != null) {
                lock.reset();
            }
            cam.setAeLock(true);
            ir.start();
            Toast.makeText(c, "ИК-тест. Направьте камеру на светлую стену в 5–15 см и наклоните телефон примерно "
                    + "на 45°, верхним краем (там ИК-окошко) к стене. Держите неподвижно 20 секунд, лучше в полутьме. "
                    + "Куда попал ИК-свет — подсветится красным.", Toast.LENGTH_LONG).show();
        } else {
            ir.stop();
            cam.setAeLock(false);
        }
    }

    private String irStatus() {
        String err = ir.error();
        if (err != null) {
            return "ИК: " + err;
        }
        if (irVerdict > 0) {
            return String.format(Locale.US, "✔ Камера ВИДИТ ИК: +%.1f ярк., сигнал/шум %.0f (цикл %d)", irDiff, irT, irCycles);
        }
        if (irVerdict < 0) {
            return String.format(Locale.US, "✘ ИК не найден (цикл %d, сигнал/шум %.1f): камера не видит ИК "
                    + "или свет не попал в кадр — измените наклон", irCycles, irT);
        }
        return String.format(Locale.US, "ИК-тест: цикл %d из 8, сигнал/шум %.1f…", irCycles, irT);
    }

    /** В потоке камеры: учесть кадр в ИК-тесте и подсветить красным блоки, где найден ИК-свет. */
    private void irFrame(byte[] y, int w, int hgt) {
        if (lock == null || lock.width() != w || lock.height() != hgt) {
            lock = new IrLockIn(w, hgt);
        }
        long now = SystemClock.elapsedRealtime();
        lock.addFrame(y, ir.phaseAt(now), ir.cycleAt(now));
        irCycles = lock.cycles();
        irVerdict = lock.verdict();
        irT = lock.maxT;
        irDiff = lock.maxDiff;
        int bs = IrLockIn.BLOCK;
        for (int by = 0; by < lock.gh; by++) {
            for (int bx = 0; bx < lock.gw; bx++) {
                float tv = lock.t[by * lock.gw + bx];
                if (tv < 3f) {
                    continue;
                }
                float a = Math.min(1f, (tv - 3f) / 6f) * 0.75f;
                for (int yy = 0; yy < bs; yy++) {
                    int row = (by * bs + yy) * w + bx * bs;
                    for (int xx = 0; xx < bs; xx++) {
                        int p = pixels[row + xx];
                        int r = (int) (((p >> 16) & 0xFF) * (1 - a) + 255 * a);
                        int g = (int) (((p >> 8) & 0xFF) * (1 - a));
                        int b = (int) ((p & 0xFF) * (1 - a));
                        pixels[row + xx] = 0xFF000000 | (r << 16) | (g << 8) | b;
                    }
                }
            }
        }
    }

    @Override
    public String title() {
        return "Камера";
    }

    @Override
    public View view() {
        return root;
    }

    @Override
    public void onShow() {
        shown = true;
        error = null;
        fpsT0 = System.currentTimeMillis();
        fpsF0 = frames;
        host.requireCamera(new Runnable() {
            @Override
            public void run() {
                if (shown) {
                    cam.open(CameraTab.this, mode, front);
                }
            }
        });
        Sensor ls = sm.getDefaultSensor(Sensor.TYPE_LIGHT);
        if (ls != null && !registeredLight) {
            registeredLight = sm.registerListener(this, ls, SensorManager.SENSOR_DELAY_NORMAL);
        }
        h.removeCallbacks(ticker);
        h.post(ticker);
    }

    @Override
    public void onHide() {
        shown = false;
        h.removeCallbacks(ticker);
        if (irOn) {
            setIr(false);
        }
        cam.close();
        if (registeredLight) {
            sm.unregisterListener(this);
            registeredLight = false;
        }
        if (radarOn) {
            setRadar(false);
        }
    }

    // ---- кадры камеры (поток камеры) ----

    @Override
    public void onFrame(byte[] y, int w, int hgt) {
        if (enh == null || enh.w != w || enh.h != hgt) {
            enh = new NightEnhancer(w, hgt);
            enh.palette = pal;
            enh.alpha = ACC_ALPHA[acc];
            enh.edges = edges;
            enh.motionMask = motion;
            pixels = new int[w * hgt];
            view.setSensorRotation(cam.sensorOrientation(), cam.isFront());
        }
        enh.process(y, pixels);
        if (irOn) {
            irFrame(y, w, hgt);
        }
        view.setFrame(pixels, w, hgt);
        luma = enh.meanLuma;
        motionFrac = enh.motionFraction;
        frames++;
    }

    @Override
    public void onInfo(String text) {
        modeInfo = text;
    }

    @Override
    public void onError(String message) {
        error = message;
    }

    // ---- датчик света ----

    @Override
    public void onSensorChanged(SensorEvent e) {
        lux = e.values[0];
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // не используется
    }

    // ---- снимок ----

    private void saveSnapshot() {
        final Bitmap b = view.snapshot();
        if (b == null) {
            Toast.makeText(c, "Кадра ещё нет", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            String name = "Radar_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".jpg";
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Images.Media.DISPLAY_NAME, name);
                cv.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                cv.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Radar");
                cv.put(MediaStore.Images.Media.IS_PENDING, 1);
                Uri uri = c.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) {
                    throw new Exception("не удалось создать файл");
                }
                OutputStream os = c.getContentResolver().openOutputStream(uri);
                if (os == null) {
                    throw new Exception("не удалось открыть файл");
                }
                try {
                    b.compress(Bitmap.CompressFormat.JPEG, 92, os);
                } finally {
                    os.close();
                }
                ContentValues done = new ContentValues();
                done.put(MediaStore.Images.Media.IS_PENDING, 0);
                c.getContentResolver().update(uri, done, null, null);
            } else {
                MediaStore.Images.Media.insertImage(c.getContentResolver(), b, name, "Радар: ночной снимок");
            }
            Toast.makeText(c, "Снимок сохранён в Галерею (Pictures/Radar)", Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            Toast.makeText(c, "Не удалось сохранить: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
