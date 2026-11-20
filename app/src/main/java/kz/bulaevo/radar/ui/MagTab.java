package kz.bulaevo.radar.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

import kz.bulaevo.radar.dsp.MagScan;

/**
 * Вкладка "Металл": магнитометр телефона как детектор металла и проводки.
 * Поднесите телефон к предмету: поле искажается, значение меняется, частота писка растёт.
 */
public final class MagTab implements Tab, SensorEventListener {
    private static final int HIST = 240;

    private final Context c;
    private final SensorManager sm;
    private final LinearLayout root;
    private final TextView big;
    private final TextView sub;
    private final PlotView plot;
    private final Button beepBtn;
    private final Button rowBtn;
    private final HeatmapView heat;
    private final MagScan scan = new MagScan();
    private final Handler h = new Handler(Looper.getMainLooper());
    private final float[] hist = new float[HIST];
    private final float[] ordered = new float[HIST];
    private int histN;
    private int histPos;
    private volatile float current;
    private volatile float smooth;
    private float baseline = Float.NaN;
    private boolean shown;
    private boolean beepOn;
    private long lastBeep;
    private ToneGenerator tone;
    private boolean registered;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!shown) {
                return;
            }
            float v = smooth;
            if (Float.isNaN(baseline) && v > 0) {
                baseline = v;
            }
            float delta = Float.isNaN(baseline) ? 0f : v - baseline;
            big.setText(String.format(Locale.US, "%+.1f µT", delta));
            big.setTextColor(Math.abs(delta) > 8f ? Ui.BAD : (Math.abs(delta) > 2.5f ? Ui.WARN : Ui.ACC));
            sub.setText(String.format(Locale.US, "Полное поле: %.1f µT · ноль: %.1f µT (поле Земли ~25–65 µT)", v,
                    Float.isNaN(baseline) ? 0f : baseline));
            // график: значения по порядку от старых к новым
            int n = Math.min(histN, HIST);
            for (int i = 0; i < n; i++) {
                ordered[i] = hist[(histPos - n + i + HIST * 2) % HIST];
            }
            if (n > 2) {
                float mn = 1e9f;
                float mx = -1e9f;
                for (int i = 0; i < n; i++) {
                    mn = Math.min(mn, ordered[i]);
                    mx = Math.max(mx, ordered[i]);
                }
                float mid = (mn + mx) / 2f;
                float half = Math.max(5f, (mx - mn) / 2f * 1.2f);
                plot.setRange(0f, 1f, mid - half, mid + half);
                plot.setGrid(0.5f, "", Math.max(1f, Math.round(half / 2f)), "%.0f");
                plot.setDataExtent(0f, 1f);
                plot.setData(ordered, n);
            }
            if (beepOn && Math.abs(delta) > 2f) {
                long interval = (long) Math.max(70, Math.min(700, 700 / (Math.abs(delta) / 2f)));
                long now = System.currentTimeMillis();
                if (now - lastBeep >= interval) {
                    lastBeep = now;
                    try {
                        if (tone == null) {
                            tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 70);
                        }
                        tone.startTone(ToneGenerator.TONE_PROP_BEEP, 40);
                    } catch (Throwable ignored) {
                        // нет звука
                    }
                }
            }
            h.postDelayed(this, 60);
        }
    };

    public MagTab(Context ctx) {
        this.c = ctx;
        sm = (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
        root = Ui.col(ctx);
        root.setBackgroundColor(Ui.BG);

        big = Ui.label(ctx, "—", 54f, Ui.ACC);
        big.setTypeface(Typeface.DEFAULT_BOLD);
        big.setGravity(Gravity.CENTER);
        big.setPadding(0, Ui.dpi(ctx, 8), 0, 0);
        root.addView(big);

        sub = Ui.label(ctx, "", 12f, Ui.DIM);
        sub.setGravity(Gravity.CENTER);
        root.addView(sub);

        plot = new PlotView(ctx);
        plot.setTitle("Магнитное поле за последние секунды (µT)");
        plot.setLineColor(0xFF6AB8FF);
        root.addView(plot, Ui.lp(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        heat = new HeatmapView(ctx);
        heat.setData(new float[0][0], "Карта стены: нажмите «▶ Строка» и ведите телефон");
        root.addView(heat, Ui.lp(LinearLayout.LayoutParams.MATCH_PARENT, 0, 0.8f));

        LinearLayout row = Ui.row(ctx);
        row.addView(Ui.button(ctx, "Обнулить здесь", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                baseline = smooth;
            }
        }), Ui.cell(ctx));
        beepBtn = Ui.button(ctx, "Писк: выкл", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                beepOn = !beepOn;
                beepBtn.setText("Писк: " + (beepOn ? "вкл" : "выкл"));
                Ui.setOn(beepBtn, beepOn);
            }
        });
        row.addView(beepBtn, Ui.cell(ctx));
        row.setPadding(Ui.dpi(ctx, 6), Ui.dpi(ctx, 4), Ui.dpi(ctx, 6), 0);
        root.addView(row);

        LinearLayout row2 = Ui.row(ctx);
        rowBtn = Ui.button(ctx, "▶ Строка", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleRow();
            }
        });
        row2.addView(rowBtn, Ui.cell(ctx));
        row2.addView(Ui.button(ctx, "Очистить карту", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                scan.clear();
                rowBtn.setText("▶ Строка");
                Ui.setOn(rowBtn, false);
                heat.setData(new float[0][0], "Карта очищена");
            }
        }), Ui.cell(ctx));
        row2.setPadding(Ui.dpi(ctx, 6), 0, Ui.dpi(ctx, 6), 0);
        root.addView(row2);

        TextView hint = Ui.label(ctx,
                "Нажмите «Обнулить» вдали от металла, затем водите телефоном вдоль стены или предмета. "
                        + "Скачок значения — арматура, трубы, проводка под током, магниты, техника рядом. "
                        + "Карта стены: приложите телефон к левому краю, «▶ Строка», ровно ведите вправо за 3–5 с, "
                        + "«■» — конец строки; следующую строку — на ладонь ниже. Видно на 2–5 см вглубь.",
                11f, Ui.DIM);
        hint.setPadding(Ui.dpi(ctx, 8), Ui.dpi(ctx, 4), Ui.dpi(ctx, 8), Ui.dpi(ctx, 8));
        root.addView(hint);
    }

    private void toggleRow() {
        if (!scan.isRecording()) {
            scan.startRow();
            rowBtn.setText("■ Конец строки");
            Ui.setOn(rowBtn, true);
        } else {
            boolean saved = scan.endRow();
            rowBtn.setText("▶ Строка");
            Ui.setOn(rowBtn, false);
            if (!saved) {
                android.widget.Toast.makeText(c, "Строка слишком короткая — ведите телефон 3–5 секунд",
                        android.widget.Toast.LENGTH_SHORT).show();
            }
            heat.setData(scan.anomalies(), "");
        }
    }

    @Override
    public String title() {
        return "Металл";
    }

    @Override
    public View view() {
        return root;
    }

    @Override
    public void onShow() {
        shown = true;
        Sensor s = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        if (s == null) {
            sub.setText("В этом телефоне нет магнитного датчика");
        } else if (!registered) {
            registered = sm.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME);
        }
        baseline = Float.NaN;
        h.removeCallbacks(ticker);
        h.post(ticker);
    }

    @Override
    public void onHide() {
        shown = false;
        h.removeCallbacks(ticker);
        if (scan.isRecording()) {
            toggleRow();
        }
        if (registered) {
            sm.unregisterListener(this);
            registered = false;
        }
        if (tone != null) {
            tone.release();
            tone = null;
        }
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        float x = e.values[0];
        float y = e.values[1];
        float z = e.values[2];
        float m = (float) Math.sqrt(x * x + y * y + z * z);
        current = m;
        scan.add(m);
        smooth = smooth == 0f ? m : smooth + 0.35f * (m - smooth);
        hist[histPos] = smooth;
        histPos = (histPos + 1) % HIST;
        histN++;
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // не используется
    }
}
