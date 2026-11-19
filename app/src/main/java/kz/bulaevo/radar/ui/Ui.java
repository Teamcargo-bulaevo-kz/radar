package kz.bulaevo.radar.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/** Цвета и фабрики простых элементов интерфейса (всё рисуется кодом, без XML-разметки). */
public final class Ui {
    private Ui() {
    }

    public static final int BG = 0xFF04100B;
    public static final int PANEL = 0xFF0B1D16;
    public static final int FG = 0xFFD6FBE6;
    public static final int ACC = 0xFF3DFFA0;
    public static final int DIM = 0xFF74A88C;
    public static final int WARN = 0xFFFFC247;
    public static final int BAD = 0xFFFF5A5A;
    public static final int BTN = 0xFF14382A;
    public static final int BTN_ON = 0xFF1F7A52;

    public interface IntCb {
        void on(int value);
    }

    public static float dp(Context c, float v) {
        return v * c.getResources().getDisplayMetrics().density;
    }

    public static int dpi(Context c, float v) {
        return Math.round(dp(c, v));
    }

    public static GradientDrawable round(Context c, int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    public static TextView label(Context c, CharSequence text, float sizeSp, int color) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(sizeSp);
        t.setTextColor(color);
        return t;
    }

    public static Button button(Context c, String text, View.OnClickListener l) {
        Button b = new Button(c);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(12.5f);
        b.setTextColor(FG);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dpi(c, 8), dpi(c, 8), dpi(c, 8), dpi(c, 8));
        b.setBackground(round(c, BTN, 8));
        b.setStateListAnimator(null);
        b.setOnClickListener(l);
        return b;
    }

    public static void setOn(Button b, boolean on) {
        b.setBackground(round(b.getContext(), on ? BTN_ON : BTN, 8));
    }

    public static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    public static LinearLayout col(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout.LayoutParams lp(int w, int h, float weight) {
        return new LinearLayout.LayoutParams(w, h, weight);
    }

    /** Кнопка-равная-доля в ряду, с небольшими отступами. */
    public static LinearLayout.LayoutParams cell(Context c) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        int m = dpi(c, 2);
        p.setMargins(m, m, m, m);
        return p;
    }

    /** Подписанный ползунок: "Название: значение ед.", значения от min до max. */
    public static LinearLayout labeledSeek(Context c, final String title, final int min, int max, int value,
                                           final String unit, final IntCb cb) {
        LinearLayout box = col(c);
        final TextView tv = label(c, title + ": " + value + " " + unit, 12f, DIM);
        SeekBar sb = new SeekBar(c);
        sb.setMax(max - min);
        sb.setProgress(value - min);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int v = progress + min;
                tv.setText(title + ": " + v + " " + unit);
                if (fromUser) {
                    cb.on(v);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                // не используется
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                // не используется
            }
        });
        box.addView(tv);
        box.addView(sb);
        return box;
    }

    public static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }
}
