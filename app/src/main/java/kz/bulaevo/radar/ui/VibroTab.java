package kz.bulaevo.radar.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

import kz.bulaevo.radar.PermissionHost;
import kz.bulaevo.radar.camera.NightCamera;
import kz.bulaevo.radar.dsp.DepthMath;
import kz.bulaevo.radar.dsp.FlickerAnalyzer;
import kz.bulaevo.radar.dsp.MotionMagnifier;
import kz.bulaevo.radar.dsp.VibrationAnalyzer;

/**
 * Вкладка "Вибро": то, что глазу не видно.
 * Мерцание — частота и глубина мигания ламп и экранов (по полосам на кадре камеры).
 * Движение — усиление микродвижений и микроизменений цвета в кадре (дыхание, пульс на коже, дрожание).
 * Датчик — спектр вибрации по акселерометру: положите телефон на работающий прибор.
 */
public final class VibroTab implements Tab, NightCamera.Callback, SensorEventListener {
    private static final int OFF = -1;
    private static final int FLICKER = 0;
    private static final int MOTION = 1;
    private static final int ACCEL = 2;
    private static final int DISP_H = 240;
    private static final String[] BAND_NAMES = {"Дыхание, пульс", "Дрожание"};
    private static final double[][] BANDS = {{0.4, 3.0}, {3.0, 14.0}};
    private static final float[] GAINS = {15f, 40f, 100f};

    private final Context c;
    private final PermissionHost host;
    private final NightCamera cam;
    private final SensorManager sm;
    private final LinearLayout root;
    private final NightView view;
    private final TextView big;
    private final TextView sub;
    private final PlotView plot;
    private final Button[] modeBtns = new Button[3];
    private final Button bandBtn;
    private final Button gainBtn;
    private final LinearLayout optRow;
    private final TextView hint;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final VibrationAnalyzer vib = new VibrationAnalyzer();

    private int mode = OFF;
    private boolean shown;
    private boolean accelOn;
    private int band;
    private int gain = 1;
    private volatile String camError;
    private volatile String camInfo = "";

    // поток камеры
    private FlickerAnalyzer flicker;
    private MotionMagnifier mag;
    private byte[] small;
    private byte[] magOut;
    private int[] px;
    private long lastFrameMs;
    private double fps = 30;
    private volatile double flHz = Double.NaN;
    private volatile double flMod;
    private volatile boolean flGuess;
    private volatile float magAct;
    private final float[] flPlot = new float[200];
    private volatile float flPlotMaxHz = 1000f;

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

    public VibroTab(Context ctx, PermissionHost host) {
        this.c = ctx;
        this.host = host;
        this.cam = new NightCamera(ctx);
        this.sm = (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);

        root = Ui.col(ctx);
        root.setBackgroundColor(Ui.BG);
        view = new NightView(ctx);
        view.setSensorRotation(0, false);
        root.addView(view, Ui.lp(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        big = Ui.label(ctx, "—", 34f, Ui.ACC);
        big.setTypeface(Typeface.DEFAULT_BOLD);
        big.setGravity(Gravity.CENTER);
        root.addView(big);
        sub = Ui.label(ctx, "", 12f, Ui.DIM);
        sub.setGravity(Gravity.CENTER);
        root.addView(sub);

        plot = new PlotView(ctx);
        plot.setLineColor(0xFF6AB8FF);
        root.addView(plot, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dpi(ctx, 120)));

        optRow = Ui.row(ctx);
        bandBtn = Ui.button(ctx, "Полоса: " + BAND_NAMES[band], new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                band = (band + 1) % BAND_NAMES.length;
                bandBtn.setText("Полоса: " + BAND_NAMES[band]);
                applyMag();
            }
        });
        gainBtn = Ui.button(ctx, "Усиление ×" + (int) GAINS[gain], new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                gain = (gain + 1) % GAINS.length;
                gainBtn.setText("Усиление ×" + (int) GAINS[gain]);
                applyMag();
            }
        });
        optRow.addView(bandBtn, Ui.cell(ctx));
        optRow.addView(gainBtn, Ui.cell(ctx));
        optRow.setVisibility(View.GONE);
        root.addView(optRow);

        LinearLayout r = Ui.row(ctx);
        String[] names = {"Мерцание ламп", "Микродвижения", "Вибрация"};
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
        root.addView(r);

        hint = Ui.label(ctx, HELP_OFF, 11f, Ui.DIM);
        hint.setPadding(Ui.dpi(ctx, 8), Ui.dpi(ctx, 4), Ui.dpi(ctx, 8), Ui.dpi(ctx, 6));
        root.addView(hint);
    }

    private static final String HELP_OFF = "«Мерцание ламп» — наведите камеру на освещённую лампой стену или на лампу "
            + "(не вплотную). «Микродвижения» — телефон неподвижно (опереть!), в кадре человек или предмет. "
            + "«Вибрация» — положите телефон на стиральную машину, мотор, трубу.";
    private static final String HELP_FLICKER = "Мерцание глазом не видно, но оно утомляет глаза. До 3% — хорошо, "
            + "3–10% — заметно камерам, больше 10% — сильное. 100 Гц — от сети, 300+ Гц — ШИМ-регулятор яркости.";
    private static final String HELP_MOTION = "Неподвижное остаётся как есть, а колебания усиливаются: видно дыхание "
            + "спящего, покраснение кожи от пульса, дрожание. Телефон должен стоять неподвижно.";
    private static final String HELP_ACCEL = "Телефон лежит на приборе. Норма для небольших машин (по скорости вибрации): "
            + "до 0.7 мм/с — хорошо, до 1.8 — допустимо, до 4.5 — повышенная, больше — сильная.";

    @Override
    public String title() {
        return "Вибро";
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

    public void debugAction(String act) {
        if ("vibro_flicker".equals(act)) {
            setMode(FLICKER);
        } else if ("vibro_motion".equals(act)) {
            setMode(MOTION);
        } else if ("vibro_accel".equals(act)) {
            setMode(ACCEL);
        }
    }

    private void setMode(final int m) {
        cam.close();
        if (accelOn) {
            sm.unregisterListener(this);
            accelOn = false;
        }
        mode = m;
        for (int i = 0; i < 3; i++) {
            Ui.setOn(modeBtns[i], i == m);
        }
        camError = null;
        flicker = null;
        mag = null;
        flHz = Double.NaN;
        flMod = 0;
        vib.reset();
        optRow.setVisibility(m == MOTION ? View.VISIBLE : View.GONE);
        view.setVisibility(m == FLICKER || m == MOTION ? View.VISIBLE : View.INVISIBLE);
        hint.setText(m == FLICKER ? HELP_FLICKER : (m == MOTION ? HELP_MOTION : (m == ACCEL ? HELP_ACCEL : HELP_OFF)));
        if (m == FLICKER || m == MOTION) {
            host.requireCamera(new Runnable() {
                @Override
                public void run() {
                    if (mode != m || !shown) {
                        return;
                    }
                    cam.open(VibroTab.this, 0, false);
                    cam.setShortExposure(m == FLICKER);
                    if (m == MOTION) {
                        // автоэкспозиция не должна «дышать» — это усилилось бы в первую очередь;
                        // замораживаем, когда камера уже подобрала яркость
                        h.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                if (mode == MOTION && shown) {
                                    cam.setAeLock(true);
                                    MotionMagnifier mm = mag;
                                    if (mm != null) {
                                        mm.reset();
                                    }
                                }
                            }
                        }, 1500);
                    }
                }
            });
        } else if (m == ACCEL) {
            Sensor s = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            if (s != null) {
                accelOn = sm.registerListener(this, s, SensorManager.SENSOR_DELAY_FASTEST);
            }
        }
    }

    private void applyMag() {
        MotionMagnifier mm = mag;
        if (mm != null) {
            mm.fLow = BANDS[band][0];
            mm.fHigh = BANDS[band][1];
            mm.alpha = GAINS[gain];
            mm.reset();
        }
    }

    private void tick() {
        if (camError != null) {
            big.setText("—");
            sub.setText("Ошибка: " + camError);
            return;
        }
        switch (mode) {
            case FLICKER: {
                double hz = flHz;
                double mod = flMod * 100;
                if (Double.isNaN(hz)) {
                    big.setText(mod < 0.6 ? "мерцания нет" : String.format(Locale.US, "~%.1f%%", mod));
                    big.setTextColor(Ui.ACC);
                    sub.setText("Не видно полос: свет ровный или лампа не попадает в кадр · " + camInfo);
                } else {
                    big.setText(String.format(Locale.US, "%s%.0f Гц · %.1f%%", flGuess ? "≈" : "", hz, mod));
                    big.setTextColor(mod > 10 ? Ui.BAD : (mod > 3 ? Ui.WARN : Ui.ACC));
                    String kind = hz < 140 ? "от сети 50 Гц (обычный драйвер)" : (hz < 300 ? "двойная частота сети / ШИМ"
                            : "ШИМ-регулятор яркости");
                    sub.setText(kind + (flGuess ? " · частота примерно: камера не сообщает время чтения кадра" : ""));
                }
                plot.setTitle("Мерцание по частотам, % (до " + Math.round(flPlotMaxHz) + " Гц)");
                float mx = 0.5f;
                for (float v : flPlot) {
                    mx = Math.max(mx, v);
                }
                plot.setRange(0f, flPlotMaxHz, 0f, mx * 1.15f);
                plot.setGrid(flPlotMaxHz / 5f, "%.0f", Math.max(0.5f, Math.round(mx / 3f)), "%.0f");
                plot.setDataExtent(0f, flPlotMaxHz);
                plot.setData(flPlot, flPlot.length);
                break;
            }
            case MOTION:
                big.setText(String.format(Locale.US, "колебания %.2f", magAct));
                big.setTextColor(Ui.ACC);
                sub.setText(String.format(Locale.US, "%.0f кадр/с · полоса %.1f–%.1f Гц · ×%d", fps,
                        BANDS[band][0], Math.min(BANDS[band][1], fps * 0.45), (int) GAINS[gain]));
                plot.setTitle("");
                break;
            case ACCEL:
                if (!accelOn) {
                    big.setText("—");
                    sub.setText("В телефоне нет акселерометра");
                    break;
                }
                if (vib.update()) {
                    big.setText(String.format(Locale.US, "%.1f Гц · %.2f мм/с", vib.peakHz, vib.velocityMmS));
                    big.setTextColor(vib.velocityMmS > 4.5 ? Ui.BAD : (vib.velocityMmS > 1.8 ? Ui.WARN : Ui.ACC));
                    sub.setText(String.format(Locale.US, "ускорение: всего %.3f м/с², на главной частоте %.3f м/с² · "
                            + "датчик %.0f Гц", vib.rms, vib.peakAmp, vib.fs));
                    int n = vib.spec.length;
                    float mx = 0.001f;
                    for (int i = 2; i < n; i++) {
                        mx = Math.max(mx, vib.spec[i]);
                    }
                    float fmax = (float) (vib.fs / 2);
                    plot.setTitle("Спектр вибрации, м/с² (до " + Math.round(fmax) + " Гц)");
                    plot.setRange(0f, fmax, 0f, mx * 1.15f);
                    plot.setGrid(Math.max(5f, Math.round(fmax / 50f) * 10f), "%.0f", mx / 2f, "%.3f");
                    plot.setDataExtent(0f, fmax);
                    plot.setData(vib.spec, n);
                }
                break;
            default:
                big.setText("—");
                big.setTextColor(Ui.DIM);
                sub.setText("Выберите режим внизу");
                break;
        }
    }

    // ---- камера (поток камеры) ----

    @Override
    public void onFrame(byte[] y, int w, int hgt) {
        long now = SystemClock.elapsedRealtime();
        if (lastFrameMs > 0 && now > lastFrameMs) {
            fps = fps * 0.9 + 0.1 * (1000.0 / (now - lastFrameMs));
        }
        lastFrameMs = now;
        int rot = cam.sensorOrientation();
        int uw = DepthMath.uprightW(w, hgt, rot);
        int uh = DepthMath.uprightH(w, hgt, rot);
        int dw = Math.max(1, Math.round(DISP_H * uw / (float) uh));
        if (small == null || small.length != dw * DISP_H) {
            small = new byte[dw * DISP_H];
            magOut = new byte[dw * DISP_H];
            px = new int[dw * DISP_H];
            mag = null;
        }
        DepthMath.sampleUpright(y, w, hgt, rot, small, dw, DISP_H, 1);
        byte[] show = small;
        if (mode == FLICKER) {
            if (flicker == null) {
                flicker = new FlickerAnalyzer(hgt);
            }
            long skew = cam.rollingShutterSkewNs();
            flGuess = skew <= 0;
            double rowNs = (skew > 0 ? skew : 20_000_000L) / (double) hgt;
            flicker.addFrame(y, w, rowNs);
            flHz = flicker.hz;
            flMod = flicker.modulation;
            // спектр на равной сетке в герцах для графика
            double toHz = 1.0 / (hgt * rowNs * 1e-9);
            double maxHz = flicker.cycles[flicker.cycles.length - 1] * toHz;
            flPlotMaxHz = (float) maxHz;
            int j = 0;
            for (int i = 0; i < flPlot.length; i++) {
                double hz = maxHz * i / (flPlot.length - 1);
                while (j < flicker.cycles.length - 1 && flicker.cycles[j + 1] * toHz < hz) {
                    j++;
                }
                flPlot[i] = (float) (flicker.spec[j] * 100);
            }
        } else if (mode == MOTION) {
            if (mag == null) {
                mag = new MotionMagnifier(dw, DISP_H);
                applyMag();
            }
            mag.process(small, fps, magOut);
            magAct = mag.activity;
            show = magOut;
        }
        for (int i = 0; i < show.length; i++) {
            int g = show[i] & 0xFF;
            px[i] = 0xFF000000 | (g << 16) | (g << 8) | g;
        }
        view.setFrame(px, dw, DISP_H);
    }

    @Override
    public void onInfo(String text) {
        camInfo = text;
    }

    @Override
    public void onError(String message) {
        camError = message;
    }

    // ---- акселерометр ----

    @Override
    public void onSensorChanged(SensorEvent e) {
        float x = e.values[0];
        float y = e.values[1];
        float z = e.values[2];
        vib.add(e.timestamp, (float) Math.sqrt(x * x + y * y + z * z));
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // не используется
    }
}
