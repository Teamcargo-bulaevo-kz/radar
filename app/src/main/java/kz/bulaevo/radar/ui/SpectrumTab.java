package kz.bulaevo.radar.ui;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

import kz.bulaevo.radar.PermissionHost;
import kz.bulaevo.radar.SonarHub;
import kz.bulaevo.radar.audio.AudioUtil;
import kz.bulaevo.radar.audio.SpectrumEngine;

/** Вкладка "Спектр": частотный анализатор микрофона (0–24 кГц) со спектрограммой и пик-холдом. */
public final class SpectrumTab implements Tab, SpectrumEngine.Listener {
    private static final float[][] ZOOMS = {{0f, 24000f}, {0f, 6000f}, {15000f, 24000f}, {0f, 1000f}};
    private static final String[] ZOOM_NAMES = {"0–24 кГц", "0–6 кГц", "УЗ 15–24 кГц", "0–1 кГц"};

    private final Context c;
    private final SonarHub hub;
    private final PermissionHost host;
    private final SpectrumEngine engine;
    private final LinearLayout root;
    private final PlotView plot;
    private final WaterfallView water;
    private final TextView readout;
    private final Button startBtn;
    private final Button zoomBtn;
    private final Button holdBtn;
    private final Handler h = new Handler(Looper.getMainLooper());

    private boolean shown;
    private int zoom;
    private volatile boolean holdOn = true;
    private final float[] hold = new float[SpectrumEngine.N / 2];
    private boolean holdInit;
    private float[] tmp = new float[SpectrumEngine.N / 2];
    private volatile double peakHz;
    private volatile double peakDb = -120;
    private volatile double levelDb = -120;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!shown) {
                return;
            }
            startBtn.setText(engine.isRunning() ? "Стоп" : "Старт");
            Ui.setOn(startBtn, engine.isRunning());
            String err = engine.lastError();
            if (err != null && !engine.isRunning()) {
                readout.setText("Ошибка: " + err);
            } else if (!engine.isRunning()) {
                readout.setText("Остановлено");
            } else {
                readout.setText(String.format(Locale.US,
                        "Пик: %.0f Гц (%.1f дБFS)  ·  общий уровень %.1f дБFS  ·  %s",
                        peakHz, peakDb, levelDb, engine.source()));
            }
            h.postDelayed(this, 150);
        }
    };

    public SpectrumTab(Context ctx, SonarHub hub, PermissionHost host) {
        this.c = ctx;
        this.hub = hub;
        this.host = host;
        this.engine = new SpectrumEngine(ctx);
        engine.setListener(this);

        root = Ui.col(ctx);
        root.setBackgroundColor(Ui.BG);

        plot = new PlotView(ctx);
        plot.setTitle("Спектр (дБFS), жёлтая линия — пик-холд");
        plot.setDataExtent(0f, 24000f);
        plot.setRange(0f, 24000f, -120f, 0f);
        plot.setGrid(4000f, "%.0f", 20f, "%.0f");
        root.addView(plot, Ui.lp(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        water = new WaterfallView(ctx, 240, 160);
        water.setFlip(true);
        water.setScale(-110f, -30f);
        root.addView(water, Ui.lp(LinearLayout.LayoutParams.MATCH_PARENT, 0, 0.9f));

        readout = Ui.label(ctx, "", 12.5f, Ui.FG);
        readout.setPadding(Ui.dpi(ctx, 8), Ui.dpi(ctx, 3), Ui.dpi(ctx, 8), Ui.dpi(ctx, 3));
        root.addView(readout);

        LinearLayout row = Ui.row(ctx);
        startBtn = Ui.button(ctx, "Старт", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (engine.isRunning()) {
                    engine.stop();
                } else {
                    startEngine();
                }
            }
        });
        zoomBtn = Ui.button(ctx, "Диапазон: " + ZOOM_NAMES[0], new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                zoom = (zoom + 1) % ZOOMS.length;
                applyZoom();
            }
        });
        holdBtn = Ui.button(ctx, "Пик-холд: вкл", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                holdOn = !holdOn;
                holdBtn.setText("Пик-холд: " + (holdOn ? "вкл" : "выкл"));
                Ui.setOn(holdBtn, holdOn);
                holdInit = false;
                plot.clearHold();
            }
        });
        Ui.setOn(holdBtn, true);
        row.addView(startBtn, Ui.cell(ctx));
        row.addView(zoomBtn, Ui.lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.5f));
        row.addView(holdBtn, Ui.cell(ctx));
        row.setPadding(Ui.dpi(ctx, 6), 0, Ui.dpi(ctx, 6), Ui.dpi(ctx, 4));
        root.addView(row);

        TextView hint = Ui.label(ctx,
                "Показывает все звуки вокруг, включая ультразвук до 24 кГц (предел микрофона телефона). "
                        + "Включите «УЗ 15–24 кГц»: отпугиватели грызунов, датчики движения, сигнализации и сонары видны здесь.",
                11f, Ui.DIM);
        hint.setPadding(Ui.dpi(ctx, 8), 0, Ui.dpi(ctx, 8), Ui.dpi(ctx, 6));
        root.addView(hint);
        applyZoom();
    }

    private void applyZoom() {
        float[] z = ZOOMS[zoom];
        plot.setRange(z[0], z[1], -120f, 0f);
        float span = z[1] - z[0];
        plot.setGrid(span > 12000 ? 4000f : (span > 5000 ? 1000f : (span > 1500 ? 2000f : 200f)), "%.0f", 20f, "%.0f");
        zoomBtn.setText("Диапазон: " + ZOOM_NAMES[zoom]);
        water.setCaptions(String.format(Locale.US, "%.0f кГц", z[1] / 1000f), String.format(Locale.US, "%.0f кГц", z[0] / 1000f));
        water.clear();
    }

    private void startEngine() {
        host.requireAudio(new Runnable() {
            @Override
            public void run() {
                hub.stop();
                engine.start();
            }
        });
    }

    @Override
    public String title() {
        return "Спектр";
    }

    @Override
    public View view() {
        return root;
    }

    @Override
    public void onShow() {
        shown = true;
        hub.stop(); // микрофон нужен анализатору
        h.removeCallbacks(ticker);
        h.post(ticker);
        if (!engine.isRunning()) {
            startEngine();
        }
    }

    @Override
    public void onHide() {
        shown = false;
        h.removeCallbacks(ticker);
        engine.stop();
    }

    @Override
    public void onSpectrum(float[] db, double binHz, double pHz, double pDb, double lvl) {
        peakHz = pHz;
        peakDb = pDb;
        levelDb = lvl;
        final int n = db.length;
        plot.setData(db, n);
        if (holdOn) {
            if (!holdInit) {
                System.arraycopy(db, 0, hold, 0, n);
                holdInit = true;
            } else {
                for (int i = 0; i < n; i++) {
                    float v = hold[i] - 0.25f;
                    hold[i] = db[i] > v ? db[i] : v;
                }
            }
            plot.setHold(hold, n);
        }
        // для водопада берём только видимый диапазон частот
        float[] z = ZOOMS[zoom];
        int i0 = Math.max(0, (int) (z[0] / binHz));
        int i1 = Math.min(n, (int) (z[1] / binHz));
        if (i1 - i0 > 1) {
            if (tmp.length < i1 - i0) {
                tmp = new float[i1 - i0];
            }
            System.arraycopy(db, i0, tmp, 0, i1 - i0);
            water.push(tmp, i1 - i0);
        }
    }
}
