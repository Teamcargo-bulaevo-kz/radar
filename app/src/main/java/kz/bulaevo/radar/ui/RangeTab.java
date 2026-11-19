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

import kz.bulaevo.radar.SonarHub;
import kz.bulaevo.radar.dsp.Bands;
import kz.bulaevo.radar.dsp.SonarFrame;
import kz.bulaevo.radar.dsp.SonarProcessor;

/** Вкладка "Дальномер": расстояние до ближайшего предмета, профиль эха, режим парктроника. */
public final class RangeTab implements Tab, SonarHub.FrameListener {
    private final Context c;
    private final SonarHub hub;
    private final LinearLayout root;
    private final TextView big;
    private final TextView peaks;
    private final PlotView plot;
    private final SonarPanel panel;
    private final Button parking;
    private final Handler h = new Handler(Looper.getMainLooper());
    private boolean shown;
    private boolean vibroOn;
    private long lastPulse;

    private final float[] mkX = new float[SonarFrame.MAX_PEAKS];
    private final String[] mkT = new String[SonarFrame.MAX_PEAKS];
    private volatile float shown1 = Float.NaN;
    private volatile int peakCount;
    private volatile String peakText = "";
    private final float[] ring = new float[5];
    private int ringN;
    private int missed;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!shown) {
                return;
            }
            double maxR = Bands.RANGE_M[hub.st.range];
            plot.setRange(0f, (float) maxR, -5f, 60f);
            plot.setGrid(maxR <= 4 ? 0.5f : (maxR <= 8 ? 1f : 2f), "%.1f м", 10f, "%.0f");
            plot.setThreshold(hub.st.detectDb);
            float d = shown1;
            if (hub.isRunning() && !Float.isNaN(d)) {
                big.setText(String.format(Locale.US, "%.2f м", d));
                big.setTextColor(d < 0.5f ? Ui.BAD : (d < 1.5f ? Ui.WARN : Ui.ACC));
            } else {
                big.setText(hub.isRunning() ? "— —" : "выкл");
                big.setTextColor(Ui.DIM);
            }
            peaks.setText(peakText);
            if (vibroOn && hub.isRunning() && !Float.isNaN(d) && d < 1.5f) {
                long interval = (long) Math.max(90, Math.min(700, d * 420));
                long now = System.currentTimeMillis();
                if (now - lastPulse >= interval) {
                    lastPulse = now;
                    Haptics.pulse(c, 35);
                }
            }
            h.postDelayed(this, 80);
        }
    };

    public RangeTab(Context ctx, SonarHub hub) {
        this.c = ctx;
        this.hub = hub;
        root = Ui.col(ctx);
        root.setBackgroundColor(Ui.BG);

        big = Ui.label(ctx, "выкл", 54f, Ui.DIM);
        big.setTypeface(Typeface.DEFAULT_BOLD);
        big.setGravity(Gravity.CENTER);
        big.setPadding(0, Ui.dpi(ctx, 8), 0, Ui.dpi(ctx, 2));
        root.addView(big);

        TextView sub = Ui.label(ctx, "до ближайшего предмета перед нижним краем телефона", 11.5f, Ui.DIM);
        sub.setGravity(Gravity.CENTER);
        root.addView(sub);

        plot = new PlotView(ctx);
        plot.setTitle("Эхо по дальности (дБ над шумом)");
        root.addView(plot, Ui.lp(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        peaks = Ui.label(ctx, "", 12f, Ui.FG);
        peaks.setPadding(Ui.dpi(ctx, 8), Ui.dpi(ctx, 2), Ui.dpi(ctx, 8), Ui.dpi(ctx, 2));
        root.addView(peaks);

        LinearLayout row = Ui.row(ctx);
        parking = Ui.button(ctx, "Парктроник (вибро): выкл", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                vibroOn = !vibroOn;
                parking.setText("Парктроник (вибро): " + (vibroOn ? "вкл" : "выкл"));
                Ui.setOn(parking, vibroOn);
            }
        });
        row.addView(parking, Ui.cell(ctx));
        row.setPadding(Ui.dpi(ctx, 6), 0, Ui.dpi(ctx, 6), 0);
        root.addView(row);

        LinearLayout seek = Ui.labeledSeek(ctx, "Порог обнаружения", 6, 30, (int) hub.st.detectDb, "дБ",
                new Ui.IntCb() {
                    @Override
                    public void on(int value) {
                        RangeTab.this.hub.st.detectDb = value;
                        RangeTab.this.hub.applySettings();
                    }
                });
        seek.setPadding(Ui.dpi(ctx, 8), 0, Ui.dpi(ctx, 8), 0);
        root.addView(seek);

        panel = new SonarPanel(ctx, hub);
        root.addView(panel.view());
    }

    @Override
    public String title() {
        return "Дальн.";
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
    }

    @Override
    public void onFrame(SonarFrame f, double azimuth) {
        if (f.warming) {
            return;
        }
        float xEnd = f.rangeOf(f.bins - 1);
        plot.setDataExtent(f.rangeOf(0), xEnd);
        plot.setData(f.snrDb, f.bins);

        int n = Math.min(f.peakCount, mkX.length);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            mkX[i] = f.peakR[i];
            mkT[i] = String.format(Locale.US, "%.2f", f.peakR[i]);
            if (i < 4) {
                sb.append(String.format(Locale.US, "%.2f м (%.0f дБ)  ", f.peakR[i], f.peakDb[i]));
            }
        }
        plot.setMarkers(mkX, mkT, n);
        peakText = n == 0 ? "Эха выше порога нет" : "Эхо: " + sb.toString().trim();

        // сглаживание показаний: медиана из последних 5 кадров, пропуски до 6 кадров подряд терпим
        if (!Float.isNaN(f.nearestM)) {
            missed = 0;
            ring[ringN % ring.length] = f.nearestM;
            ringN++;
            int cnt = Math.min(ringN, ring.length);
            float[] tmp = new float[cnt];
            System.arraycopy(ring, 0, tmp, 0, cnt);
            java.util.Arrays.sort(tmp);
            shown1 = tmp[cnt / 2];
        } else if (++missed > 6) {
            shown1 = Float.NaN;
            ringN = 0;
        }
    }
}
