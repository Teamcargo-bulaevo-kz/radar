package kz.bulaevo.radar.ui;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import kz.bulaevo.radar.SonarHub;
import kz.bulaevo.radar.audio.AudioUtil;
import kz.bulaevo.radar.dsp.Bands;

/** Общая панель управления сонаром: старт/стоп, диапазон частот, дальность, калибровка фона, статус. */
public final class SonarPanel {
    private final Context ctx;
    private final SonarHub hub;
    private final LinearLayout root;
    private final Button start;
    private final Button band;
    private final Button range;
    private final Button leak;
    private final TextView status;
    private final Handler h = new Handler(Looper.getMainLooper());
    private boolean shown;

    private final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            if (!shown) {
                return;
            }
            refresh();
            h.postDelayed(this, 350);
        }
    };

    public SonarPanel(Context c, SonarHub hub) {
        this.ctx = c;
        this.hub = hub;
        root = Ui.col(c);
        root.setPadding(Ui.dpi(c, 6), Ui.dpi(c, 4), Ui.dpi(c, 6), Ui.dpi(c, 4));
        root.setBackgroundColor(Ui.PANEL);

        LinearLayout r1 = Ui.row(c);
        start = Ui.button(c, "Старт", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SonarPanel.this.hub.toggle();
                h.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        refresh();
                    }
                }, 300);
            }
        });
        band = Ui.button(c, "", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SonarPanel.this.hub.st.band = (SonarPanel.this.hub.st.band + 1) % Bands.NAMES.length;
                SonarPanel.this.hub.applySettings();
                refresh();
            }
        });
        range = Ui.button(c, "", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SonarPanel.this.hub.st.range = (SonarPanel.this.hub.st.range + 1) % Bands.RANGE_N.length;
                SonarPanel.this.hub.applySettings();
                refresh();
            }
        });
        r1.addView(start, Ui.cell(c));
        r1.addView(band, Ui.lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.6f));
        r1.addView(range, Ui.cell(c));

        LinearLayout r2 = Ui.row(c);
        leak = Ui.button(c, "Обнулить фон", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (SonarPanel.this.hub.hasLeak()) {
                    SonarPanel.this.hub.clearLeak();
                } else {
                    SonarPanel.this.hub.calibrateLeak();
                }
                refresh();
            }
        });
        Button vol = Ui.button(c, "Громкость макс.", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AudioUtil.setMusicVolumeMax(ctx);
                refresh();
            }
        });
        r2.addView(leak, Ui.cell(c));
        r2.addView(vol, Ui.cell(c));

        status = Ui.label(c, "", 12f, Ui.DIM);
        status.setPadding(Ui.dpi(c, 4), Ui.dpi(c, 2), Ui.dpi(c, 4), 0);

        root.addView(r1);
        root.addView(r2);
        root.addView(status);
    }

    public View view() {
        return root;
    }

    /** Добавить ещё один элемент (например, ползунок) под кнопками. */
    public void addExtra(View v) {
        root.addView(v, 2);
    }

    public void onShow() {
        shown = true;
        h.removeCallbacks(refresher);
        refresh();
        h.postDelayed(refresher, 350);
    }

    public void onHide() {
        shown = false;
        h.removeCallbacks(refresher);
    }

    private void refresh() {
        start.setText(hub.isRunning() ? "Стоп" : "Старт");
        Ui.setOn(start, hub.isRunning());
        band.setText(Bands.NAMES[hub.st.band]);
        range.setText("до " + Bands.RANGE_NAMES[hub.st.range]);
        if (hub.isCalibrating()) {
            leak.setText("Калибровка…");
        } else if (hub.hasLeak()) {
            leak.setText("Сбросить фон");
        } else {
            leak.setText("Обнулить фон");
        }
        Ui.setOn(leak, hub.hasLeak());
        StringBuilder sb = new StringBuilder(hub.statusLine());
        if (Bands.AUDIBLE[hub.st.band]) {
            sb.append("\nДиапазон слышимый: будет слышен свист.");
        }
        sb.append("\nГромкость медиа: ").append(AudioUtil.musicVolumePercent(ctx)).append("%");
        if (AudioUtil.headphonesConnected(ctx)) {
            sb.append(" · подключены наушники!");
        }
        status.setText(sb.toString());
    }
}
