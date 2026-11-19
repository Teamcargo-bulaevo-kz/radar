package kz.bulaevo.radar.ui;

import android.content.Context;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import kz.bulaevo.radar.SonarHub;
import kz.bulaevo.radar.dsp.Bands;
import kz.bulaevo.radar.dsp.SonarFrame;

/**
 * Вкладка "Движение": водопад "дальность × время", на котором неподвижные предметы исчезают,
 * а движущиеся (человек, дверь, питомец) светятся. Плюс охранная сигнализация.
 */
public final class MotionTab implements Tab, SonarHub.FrameListener {
    private final Context c;
    private final SonarHub hub;
    private final LinearLayout root;
    private final WaterfallView water;
    private final TextView state;
    private final TextView log;
    private final Button viewBtn;
    private final Button armBtn;
    private final SonarPanel panel;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss", Locale.US);

    private boolean shown;
    private volatile boolean showMotion = true;
    private volatile boolean armed;
    private long armAt;
    private volatile float score;
    private volatile float scoreRange = Float.NaN;
    private int consecutive;
    private long lastEvent;
    private int events;
    private final StringBuilder logText = new StringBuilder();
    private ToneGenerator tone;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!shown) {
                return;
            }
            double maxR = Bands.RANGE_M[hub.st.range];
            water.setCaptions("0 м", String.format(Locale.US, "%.0f м", maxR));
            long now = System.currentTimeMillis();
            if (armBtn != null) {
                if (armed && now < armAt) {
                    armBtn.setText("Охрана: через " + ((armAt - now) / 1000 + 1) + " с");
                } else {
                    armBtn.setText(armed ? "Охрана: ВКЛ" : "Охрана: выкл");
                }
                Ui.setOn(armBtn, armed);
            }
            StringBuilder sb = new StringBuilder();
            if (!hub.isRunning()) {
                sb.append("Нажмите «Старт». Замрите — фон запомнится за пару секунд.");
            } else {
                float s = score;
                sb.append(String.format(Locale.US, "Движение: %.0f дБ", s));
                if (!Float.isNaN(scoreRange) && s > 12f) {
                    sb.append(String.format(Locale.US, " на %.1f м", scoreRange));
                }
                sb.append("  ·  порог тревоги ").append(Math.round(hub.st.alarmDb)).append(" дБ");
            }
            state.setText(sb.toString());
            state.setTextColor(armed && score >= hub.st.alarmDb ? Ui.BAD : Ui.FG);
            h.postDelayed(this, 120);
        }
    };

    public MotionTab(Context ctx, SonarHub hub) {
        this.c = ctx;
        this.hub = hub;
        root = Ui.col(ctx);
        root.setBackgroundColor(Ui.BG);

        water = new WaterfallView(ctx, 240, 180);
        water.setScale(4f, 40f);
        root.addView(water, Ui.lp(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        state = Ui.label(ctx, "", 13f, Ui.FG);
        state.setPadding(Ui.dpi(ctx, 8), Ui.dpi(ctx, 3), Ui.dpi(ctx, 8), 0);
        root.addView(state);

        LinearLayout row = Ui.row(ctx);
        viewBtn = Ui.button(ctx, "Вид: движение", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMotion = !showMotion;
                viewBtn.setText(showMotion ? "Вид: движение" : "Вид: эхо");
                water.setScale(showMotion ? 4f : 0f, showMotion ? 40f : 50f);
                water.clear();
            }
        });
        armBtn = Ui.button(ctx, "Охрана: выкл", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                armed = !armed;
                if (armed) {
                    armAt = System.currentTimeMillis() + 10000;
                    consecutive = 0;
                    if (!MotionTab.this.hub.isRunning()) {
                        MotionTab.this.hub.start();
                    }
                }
            }
        });
        row.addView(viewBtn, Ui.cell(ctx));
        row.addView(armBtn, Ui.cell(ctx));
        row.addView(Ui.button(ctx, "Очистить", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                water.clear();
                logText.setLength(0);
                events = 0;
                log.setText("");
                if (MotionTab.this.hub.engine.processor() != null) {
                    MotionTab.this.hub.engine.processor().resetBackground();
                }
            }
        }), Ui.cell(ctx));
        row.setPadding(Ui.dpi(ctx, 6), 0, Ui.dpi(ctx, 6), 0);
        root.addView(row);

        LinearLayout seek = Ui.labeledSeek(ctx, "Порог тревоги (чем выше, тем менее чувствительно)", 12, 40,
                (int) hub.st.alarmDb, "дБ", new Ui.IntCb() {
                    @Override
                    public void on(int value) {
                        MotionTab.this.hub.st.alarmDb = value;
                        MotionTab.this.hub.st.save(c);
                    }
                });
        seek.setPadding(Ui.dpi(ctx, 8), 0, Ui.dpi(ctx, 8), 0);
        root.addView(seek);

        log = Ui.label(ctx, "", 11.5f, Ui.WARN);
        log.setPadding(Ui.dpi(ctx, 8), Ui.dpi(ctx, 2), Ui.dpi(ctx, 8), Ui.dpi(ctx, 2));
        root.addView(log);

        panel = new SonarPanel(ctx, hub);
        root.addView(panel.view());
    }

    @Override
    public String title() {
        return "Движ.";
    }

    @Override
    public View view() {
        return root;
    }

    @Override
    public void onShow() {
        shown = true;
        hub.addFrameListener(this);
        panel.onShow();
        h.removeCallbacks(ticker);
        h.post(ticker);
    }

    @Override
    public void onHide() {
        shown = false;
        hub.removeFrameListener(this);
        h.removeCallbacks(ticker);
        panel.onHide();
        armed = false;
        if (tone != null) {
            tone.release();
            tone = null;
        }
    }

    @Override
    public void onFrame(SonarFrame f, double azimuth) {
        if (f.warming) {
            return;
        }
        score = f.motionScore;
        scoreRange = f.motionRangeM;
        water.push(showMotion ? f.motionDb : f.snrDb, f.bins);

        long now = System.currentTimeMillis();
        if (armed && now >= armAt && f.motionScore >= hub.st.alarmDb) {
            consecutive++;
        } else {
            consecutive = 0;
        }
        if (consecutive >= 3 && now - lastEvent > 3000) {
            lastEvent = now;
            consecutive = 0;
            final String line = String.format(Locale.US, "%s — движение на %.1f м (%.0f дБ)",
                    fmt.format(new Date(now)), f.motionRangeM, f.motionScore);
            h.post(new Runnable() {
                @Override
                public void run() {
                    alarm(line);
                }
            });
        }
    }

    private void alarm(String line) {
        events++;
        logText.insert(0, line + "\n");
        // оставляем 6 последних строк
        int cut = 0;
        int lines = 0;
        for (int i = 0; i < logText.length(); i++) {
            if (logText.charAt(i) == '\n' && ++lines == 6) {
                cut = i + 1;
                break;
            }
        }
        if (cut > 0) {
            logText.setLength(cut);
        }
        log.setText("Тревог: " + events + "\n" + logText);
        Haptics.pulse(c, 500);
        try {
            if (tone == null) {
                tone = new ToneGenerator(AudioManager.STREAM_ALARM, 90);
            }
            tone.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 600);
        } catch (Throwable ignored) {
            // нет сигнала — остаётся вибрация и запись в журнале
        }
    }
}
