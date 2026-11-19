package kz.bulaevo.radar.ui;

import android.content.Context;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;

import kz.bulaevo.radar.SonarHub;

/** Кнопки парктроника: гудки, вибрация и расстояние, с которого они начинаются. Общие для вкладок. */
public final class BeepControls {
    private final SonarHub hub;
    private final LinearLayout root;
    private final Button beep;
    private final Button vibro;

    public BeepControls(Context c, SonarHub hubRef) {
        this.hub = hubRef;
        root = Ui.col(c);

        LinearLayout row = Ui.row(c);
        beep = Ui.button(c, "", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hub.setBeep(!hub.st.beep);
                refresh();
            }
        });
        vibro = Ui.button(c, "", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hub.st.vibro = !hub.st.vibro;
                hub.st.save(hub.ctx);
                refresh();
            }
        });
        row.addView(beep, Ui.cell(c));
        row.addView(vibro, Ui.cell(c));
        row.setPadding(Ui.dpi(c, 4), 0, Ui.dpi(c, 4), 0);
        root.addView(row);

        LinearLayout seek = Ui.labeledSeek(c, "Гудеть с расстояния", 1, 7, Math.max(1, Math.min(7, Math.round(hub.st.beepMaxM))),
                "м", new Ui.IntCb() {
                    @Override
                    public void on(int value) {
                        hub.setBeepMax(value);
                    }
                });
        seek.setPadding(Ui.dpi(c, 8), 0, Ui.dpi(c, 8), 0);
        root.addView(seek);
        refresh();
    }

    public View view() {
        return root;
    }

    public void refresh() {
        beep.setText("Гудки: " + (hub.st.beep ? "вкл" : "выкл"));
        Ui.setOn(beep, hub.st.beep);
        vibro.setText("Вибро: " + (hub.st.vibro ? "вкл" : "выкл"));
        Ui.setOn(vibro, hub.st.vibro);
    }
}
