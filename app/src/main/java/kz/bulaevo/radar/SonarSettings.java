package kz.bulaevo.radar;

import android.content.Context;
import android.content.SharedPreferences;

import kz.bulaevo.radar.dsp.Bands;

/** Общие настройки сонара (запоминаются между запусками). */
public final class SonarSettings {
    /** Выбранный диапазон частот (индекс в {@link Bands}). */
    public int band = 0;
    /** Выбранная дальность (индекс в {@link Bands#RANGE_N}). */
    public int range = 1;
    /** Порог обнаружения пиков, дБ над шумом. */
    public float detectDb = 12f;
    /** С какого уровня эхо начинает светиться на карте, дБ. */
    public float lowDb = 10f;
    /** Порог сигнализации движения, дБ. */
    public float alarmDb = 22f;
    /** Громкость излучения (доля от максимума цифровой шкалы). */
    public float txAmp = 0.9f;
    /** Звуковой парктроник (гудки по расстоянию). */
    public boolean beep = false;
    /** Пользователь уже сам выбирал, включать ли гудки. */
    public boolean beepChosen = false;
    /** Вибро-парктроник. */
    public boolean vibro = false;
    /** С какого расстояния гудеть, м. */
    public float beepMaxM = 3f;

    /** Диапазон яркости карты от порога вверх, дБ. */
    public static final float SPAN_DB = 28f;

    private static final String PREFS = "radar";

    public void load(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        band = clamp(p.getInt("band", band), 0, Bands.NAMES.length - 1);
        range = clamp(p.getInt("range", range), 0, Bands.RANGE_N.length - 1);
        detectDb = p.getFloat("detectDb", detectDb);
        lowDb = p.getFloat("lowDb", lowDb);
        alarmDb = p.getFloat("alarmDb", alarmDb);
        beepChosen = p.contains("beep");
        beep = p.getBoolean("beep", beep);
        vibro = p.getBoolean("vibro", vibro);
        beepMaxM = p.getFloat("beepMaxM", beepMaxM);
    }

    public void save(Context c) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt("band", band)
                .putInt("range", range)
                .putFloat("detectDb", detectDb)
                .putFloat("lowDb", lowDb)
                .putFloat("alarmDb", alarmDb)
                .putBoolean("beep", beep)
                .putBoolean("vibro", vibro)
                .putFloat("beepMaxM", beepMaxM)
                .apply();
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
