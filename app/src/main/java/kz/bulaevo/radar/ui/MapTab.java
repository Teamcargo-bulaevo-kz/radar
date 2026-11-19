package kz.bulaevo.radar.ui;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

import kz.bulaevo.radar.SonarHub;

/** Вкладка "Карта": радар с накоплением эха при вращении телефона, очертания комнаты. */
public final class MapTab implements Tab {
    private final Context c;
    private final SonarHub hub;
    private final LinearLayout root;
    private final RadarMapView radar;
    private final TextView info;
    private final SonarPanel panel;
    private android.widget.Button zoomBtn;
    private static final double[] ZOOMS = {0, 1.0, 2.0, 3.5};
    private int zoomIdx;
    private final Handler h = new Handler(Looper.getMainLooper());
    private boolean shown;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!shown) {
                return;
            }
            boolean live = hub.orient.isLive();
            radar.setAzimuth(hub.orient.azimuth(), hub.isRunning());
            radar.setNearest(hub.nearest());
            StringBuilder sb = new StringBuilder();
            if (hub.isRunning()) {
                float n = hub.nearest();
                sb.append(Float.isNaN(n) ? "Ближайшее эхо: нет" : String.format(Locale.US, "Ближайшее эхо: %.2f м", n));
            } else {
                sb.append("Нажмите «Старт»");
            }
            if (live) {
                sb.append(String.format(Locale.US, "  ·  азимут %.0f°", hub.orient.azimuth()));
            } else {
                sb.append("  ·  датчик поворота: ").append(hub.orient.kind());
            }
            info.setText(sb.toString());
            h.postDelayed(this, 120);
        }
    };

    public MapTab(Context ctx, SonarHub hub) {
        this.c = ctx;
        this.hub = hub;
        root = Ui.col(ctx);
        root.setBackgroundColor(Ui.BG);

        radar = new RadarMapView(ctx);
        radar.setMap(hub.map);
        root.addView(radar, Ui.lp(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        info = Ui.label(ctx, "", 13f, Ui.FG);
        info.setPadding(Ui.dpi(ctx, 8), Ui.dpi(ctx, 2), Ui.dpi(ctx, 8), 0);
        root.addView(info);

        TextView hint = Ui.label(ctx,
                "Держите телефон ровно, нижним краем (там динамик) вперёд, как пульт. "
                        + "Медленно повернитесь вокруг себя на месте: эхо запоминается на карте там, куда смотрел луч. "
                        + "Голубая линия — очертания: ближайшее эхо по каждому направлению.",
                11f, Ui.DIM);
        hint.setPadding(Ui.dpi(ctx, 8), Ui.dpi(ctx, 2), Ui.dpi(ctx, 8), Ui.dpi(ctx, 2));
        root.addView(hint);

        LinearLayout btns = Ui.row(ctx);
        btns.addView(Ui.button(ctx, "Очистить карту", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                MapTab.this.hub.map.reset();
            }
        }), Ui.cell(ctx));
        btns.addView(Ui.button(ctx, "Север = сюда", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                MapTab.this.hub.orient.resetZero();
                MapTab.this.hub.map.reset();
            }
        }), Ui.cell(ctx));
        zoomBtn = Ui.button(ctx, "Масштаб: вся", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                nextZoom();
            }
        });
        btns.addView(zoomBtn, Ui.cell(ctx));
        btns.setPadding(Ui.dpi(ctx, 6), 0, Ui.dpi(ctx, 6), 0);
        root.addView(btns);

        LinearLayout seek = Ui.labeledSeek(ctx, "Чувствительность: порог свечения", 3, 30, (int) hub.st.lowDb, "дБ",
                new Ui.IntCb() {
                    @Override
                    public void on(int value) {
                        MapTab.this.hub.st.lowDb = value;
                        MapTab.this.hub.st.save(c);
                    }
                });
        seek.setPadding(Ui.dpi(ctx, 8), 0, Ui.dpi(ctx, 8), 0);
        root.addView(seek);

        panel = new SonarPanel(ctx, hub);
        root.addView(panel.view());
    }

    /** Масштаб: вся карта → 1 м → 2 м → 3.5 м (только меньше текущей дальности). */
    private void nextZoom() {
        double mapR = hub.map.maxRange();
        for (int i = 1; i <= ZOOMS.length; i++) {
            int k = (zoomIdx + i) % ZOOMS.length;
            if (k == 0 || ZOOMS[k] < mapR - 1e-6) {
                zoomIdx = k;
                break;
            }
        }
        double z = ZOOMS[zoomIdx];
        radar.setViewRange(z);
        zoomBtn.setText(z == 0 ? "Масштаб: вся" : String.format(Locale.US, "Масштаб: %s м", z == Math.floor(z) ? String.valueOf((int) z) : String.valueOf(z)));
    }

    @Override
    public String title() {
        return "Карта";
    }

    @Override
    public View view() {
        return root;
    }

    @Override
    public void onShow() {
        shown = true;
        hub.setMapping(true);
        panel.onShow();
        h.removeCallbacks(ticker);
        h.post(ticker);
    }

    @Override
    public void onHide() {
        shown = false;
        h.removeCallbacks(ticker);
        panel.onHide();
        hub.setMapping(false);
    }
}
