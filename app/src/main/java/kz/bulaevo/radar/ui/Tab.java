package kz.bulaevo.radar.ui;

import android.view.View;

/** Вкладка приложения. */
public interface Tab {
    /** Короткое название на панели вкладок. */
    String title();

    View view();

    /** Вкладка стала видимой. */
    void onShow();

    /** Вкладка скрыта (или приложение свёрнуто): остановить всё, что тратит батарею. */
    void onHide();
}
