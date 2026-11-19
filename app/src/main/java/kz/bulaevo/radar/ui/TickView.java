package kz.bulaevo.radar.ui;

import android.content.Context;
import android.view.View;

/** Вид, который сам перерисовывается ~15 раз в секунду, пока виден на экране. */
public abstract class TickView extends View {
    protected long tickMs = 66;
    private boolean attached;
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!attached) {
                return;
            }
            invalidate();
            postDelayed(this, tickMs);
        }
    };

    public TickView(Context c) {
        super(c);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        removeCallbacks(ticker);
        post(ticker);
    }

    @Override
    protected void onDetachedFromWindow() {
        attached = false;
        removeCallbacks(ticker);
        super.onDetachedFromWindow();
    }
}
