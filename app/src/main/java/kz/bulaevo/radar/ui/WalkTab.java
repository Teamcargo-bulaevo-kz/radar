package kz.bulaevo.radar.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

import kz.bulaevo.radar.SonarHub;
import kz.bulaevo.radar.dsp.Bands;

/** Вкладка «Ходьба»: препятствия впереди, подсказка «куда свободнее», гудки и вибрация. */
public final class WalkTab implements Tab {
    private static final float WARN_M = 2.5f;

    private final Context c;
    private final SonarHub hub;
    private final LinearLayout root;
    private final TextView big;
    private final TextView advice;
    private final WalkView view;
    private final SonarPanel panel;
    private final BeepControls beepControls;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final float[] out = new float[1];
    private boolean shown;
    private long lastPulse;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!shown) {
                return;
            }
            update();
            h.postDelayed(this, 100);
        }
    };

    public WalkTab(Context ctx, SonarHub hub) {
        this.c = ctx;
        this.hub = hub;
        root = Ui.col(ctx);
        root.setBackgroundColor(Ui.BG);

        big = Ui.label(ctx, "выкл", 46f, Ui.DIM);
        big.setTypeface(Typeface.DEFAULT_BOLD);
        big.setGravity(Gravity.CENTER);
        big.setPadding(0, Ui.dpi(ctx, 6), 0, 0);
        root.addView(big);

        advice = Ui.label(ctx, "", 14f, Ui.FG);
        advice.setGravity(Gravity.CENTER);
        advice.setMinLines(2);
        advice.setPadding(Ui.dpi(ctx, 8), 0, Ui.dpi(ctx, 8), Ui.dpi(ctx, 2));
        root.addView(advice);

        view = new WalkView(ctx, hub);
        root.addView(view, Ui.lp(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView hint = Ui.label(ctx,
                "Держите телефон перед собой нижним краем (там динамик) вперёд и водите им влево-вправо, как фонарём, "
                        + "в такт шагам: веер покажет, где препятствия. Гудки чаще и выше — препятствие ближе. "
                        + "Узкие предметы (столб, ветка) видны хуже стен: на улице уверенно примерно до 2–4 м.",
                11f, Ui.DIM);
        hint.setPadding(Ui.dpi(ctx, 8), Ui.dpi(ctx, 2), Ui.dpi(ctx, 8), Ui.dpi(ctx, 2));
        root.addView(hint);

        beepControls = new BeepControls(ctx, hub);
        root.addView(beepControls.view());

        panel = new SonarPanel(ctx, hub);
        root.addView(panel.view());
    }

    private void update() {
        final long now = System.currentTimeMillis();
        final double heading = hub.orient.azimuth();
        final float maxR = (float) Bands.RANGE_M[hub.st.range];
        float f = hub.sectors.nearestInCone(heading, 12.0, now);
        double free = Double.NaN;
        String text;

        if (!hub.isRunning()) {
            big.setText("выкл");
            big.setTextColor(Ui.DIM);
            text = "Нажмите «Старт»";
        } else if (Float.isNaN(f)) {
            big.setText("— —");
            big.setTextColor(Ui.DIM);
            text = "Пока нет данных. Водите телефоном влево-вправо, как фонарём.";
        } else if (f >= maxR - 0.05f) {
            big.setText("свободно");
            big.setTextColor(Ui.ACC);
            text = String.format(Locale.US, "Впереди чисто минимум на %.0f м", maxR);
        } else {
            big.setText(String.format(Locale.US, "%.1f м", f));
            big.setTextColor(f < 1.0f ? Ui.BAD : (f < WARN_M ? Ui.WARN : Ui.ACC));
            if (f < WARN_M) {
                free = hub.sectors.clearestDirection(heading, WalkView.VIEW_LIMIT_DEG, 15.0, now, out);
                if (Double.isNaN(free)) {
                    text = "Препятствие впереди. Поводите телефоном вправо-влево, чтобы найти обход.";
                } else if (Math.abs(free) < 12.0) {
                    free = Double.NaN;
                    if (sidesKnown(heading, now)) {
                        text = "Препятствие впереди, а в стороны не свободнее. Остановитесь.";
                    } else {
                        text = "Препятствие впереди. Поводите телефоном вправо-влево, чтобы найти обход.";
                    }
                } else {
                    text = String.format(Locale.US, "Обход %s на %.0f° (там свободно %.1f м)",
                            free > 0 ? "правее" : "левее", Math.abs(free), out[0]);
                }
            } else {
                text = "Путь свободен";
            }
        }
        advice.setText(text);
        view.setFreeDirection(free);

        // вибрация по ближайшему эху луча
        float d = hub.smoothNearest();
        if (hub.st.vibro && hub.isRunning() && !Float.isNaN(d) && d < 1.5f) {
            long interval = (long) Math.max(90, Math.min(700, d * 420));
            if (now - lastPulse >= interval) {
                lastPulse = now;
                Haptics.pulse(c, 35);
            }
        }
    }

    /** Измерялись ли направления в стороне от курса (иначе «в стороны не свободнее» — просто нет данных). */
    private boolean sidesKnown(double heading, long now) {
        for (int a = 25; a <= 70; a += 15) {
            if (!Float.isNaN(hub.sectors.rangeAt(heading + a, now)) || !Float.isNaN(hub.sectors.rangeAt(heading - a, now))) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String title() {
        return "Ходьба";
    }

    @Override
    public View view() {
        return root;
    }

    @Override
    public void onShow() {
        shown = true;
        hub.setTrackOrientation(true);
        if (!hub.st.beepChosen) {
            hub.setBeep(true); // для ходьбы гудки нужны по умолчанию
        }
        beepControls.refresh();
        panel.onShow();
        h.removeCallbacks(ticker);
        h.post(ticker);
    }

    @Override
    public void onHide() {
        shown = false;
        h.removeCallbacks(ticker);
        panel.onHide();
        hub.setTrackOrientation(false);
    }
}
