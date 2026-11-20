package kz.bulaevo.radar;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import kz.bulaevo.radar.audio.AudioUtil;
import kz.bulaevo.radar.audio.Beeper;
import kz.bulaevo.radar.audio.SonarEngine;
import kz.bulaevo.radar.dsp.Bands;
import kz.bulaevo.radar.dsp.RoomMap;
import kz.bulaevo.radar.dsp.SectorMemory;
import kz.bulaevo.radar.dsp.SonarFrame;
import kz.bulaevo.radar.dsp.SonarProcessor;
import kz.bulaevo.radar.sensors.OrientationTracker;

/**
 * Центральный узел сонара: хранит движок, настройки, карту и датчик ориентации,
 * запускает/останавливает сонар и раздаёт кадры экранам.
 */
public final class SonarHub implements SonarEngine.Listener {
    /** Слушатель кадров; вызывается из потока записи звука. */
    public interface FrameListener {
        void onFrame(SonarFrame f, double azimuth);
    }

    public final Context ctx;
    public final SonarEngine engine;
    public final SonarSettings st = new SonarSettings();
    public final OrientationTracker orient;
    public final RoomMap map = new RoomMap(180, 120);
    /** Короткая память эха по направлениям для режима «Ходьба». */
    public final SectorMemory sectors = new SectorMemory();
    public final Beeper beeper = new Beeper();
    public final Handler ui = new Handler(Looper.getMainLooper());
    public PermissionHost host;

    private final List<FrameListener> listeners = new CopyOnWriteArrayList<>();
    private volatile boolean mapEnabled;
    private volatile boolean trackOrientation;

    private volatile float lastQuality;
    private volatile float lastNearest = Float.NaN;
    private volatile boolean lastClipped;
    private volatile boolean lastWarming = true;
    private volatile long weakSince;
    private volatile long frames;

    private final float[] nearRing = new float[3];
    private int nearN;
    private int nearMissed;
    private volatile float smoothNearest = Float.NaN;
    private boolean startedWithBeep;

    public SonarHub(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.engine = new SonarEngine(this.ctx);
        this.orient = new OrientationTracker(this.ctx);
        st.load(this.ctx);
        map.setMaxRange(Bands.RANGE_M[st.range]);
        engine.addListener(this);
    }

    public void addFrameListener(FrameListener l) {
        if (!listeners.contains(l)) {
            listeners.add(l);
        }
    }

    public void removeFrameListener(FrameListener l) {
        listeners.remove(l);
    }

    public boolean isRunning() {
        return engine.isRunning();
    }

    /** Включить заполнение карты (экран карты) и слежение за поворотом. */
    public void setMapping(boolean on) {
        mapEnabled = on;
        if (on) {
            orient.start();
        } else if (!trackOrientation) {
            orient.stop();
        }
    }

    public void setTrackOrientation(boolean on) {
        trackOrientation = on;
        if (on) {
            orient.start();
        } else if (!mapEnabled) {
            orient.stop();
        }
    }

    public void toggle() {
        if (isRunning()) {
            stop();
        } else {
            start();
        }
    }

    /** Запуск с проверкой разрешения. */
    public void start() {
        if (host == null) {
            doStart();
            return;
        }
        host.requireAudio(new Runnable() {
            @Override
            public void run() {
                doStart();
            }
        });
    }

    private void doStart() {
        if (engine.isRunning()) {
            return;
        }
        map.setMaxRange(Bands.RANGE_M[st.range]);
        lastWarming = true;
        weakSince = 0;
        frames = 0;
        startEngine();
    }

    /** Диапазон, на котором реально работает сонар: в тихом режиме слышимый заменяется ультразвуком 16–20 кГц. */
    public int effectiveBand() {
        return st.quiet && Bands.AUDIBLE[st.band] ? 2 : st.band;
    }

    /** Звучат ли гудки парктроника (в тихом режиме — нет). */
    private boolean beepActive() {
        return st.beep && !st.quiet;
    }

    /** Вибро-парктроник: включён сам или заменяет гудки в тихом режиме. */
    public boolean vibroOn() {
        return st.vibro || (st.quiet && st.beep);
    }

    /** Тихий режим: сонар перезапускается в ультразвуке, гудки заменяются вибрацией. */
    public void setQuiet(boolean on) {
        st.quiet = on;
        st.save(ctx);
        if (engine.isRunning() && (engine.band() != effectiveBand() || startedWithBeep != beepActive())) {
            startEngine();
        } else {
            syncBeeper();
        }
    }

    private void startEngine() {
        startedWithBeep = beepActive();
        nearN = 0;
        nearMissed = 0;
        smoothNearest = Float.NaN;
        sectors.reset();
        engine.start(effectiveBand(), st.range, beepActive() ? st.txAmp * Beeper.SONAR_SCALE : st.txAmp);
        SonarProcessor p = engine.processor();
        if (p != null) {
            p.detectDb = st.detectDb;
        }
        syncBeeper();
    }

    /** Включить/выключить гудки в соответствии с настройками и состоянием сонара. */
    private void syncBeeper() {
        beeper.setMaxDistance(st.beepMaxM);
        if (engine.isRunning() && beepActive()) {
            beeper.start();
        } else {
            beeper.stop();
        }
    }

    /** Включить звуковой парктроник (сонар перезапускается: чуть меняется сила излучения). */
    public void setBeep(boolean on) {
        st.beep = on;
        st.save(ctx);
        if (engine.isRunning() && startedWithBeep != beepActive()) {
            startEngine();
        } else {
            syncBeeper();
        }
    }

    public void setBeepMax(float meters) {
        st.beepMaxM = meters;
        st.save(ctx);
        beeper.setMaxDistance(meters);
    }

    /** Ближайшее эхо, сглаженное по трём кадрам, с удержанием при коротких пропусках. */
    public float smoothNearest() {
        return smoothNearest;
    }

    public void stop() {
        beeper.stop();
        engine.stop();
    }

    /** Перезапуск с новыми настройками (смена диапазона/дальности). */
    public void applySettings() {
        st.save(ctx);
        map.setMaxRange(Bands.RANGE_M[st.range]);
        SonarProcessor p = engine.processor();
        if (p != null) {
            p.detectDb = st.detectDb;
        }
        if (engine.isRunning() && (engine.band() != effectiveBand() || engine.rangeIdx() != st.range)) {
            startEngine();
        }
    }

    /** Запомнить "утечку" корпуса: направьте телефон в пустое пространство. */
    public void calibrateLeak() {
        SonarProcessor p = engine.processor();
        if (p != null) {
            p.beginLeakCalibration(25);
        }
    }

    public void clearLeak() {
        SonarProcessor p = engine.processor();
        if (p != null) {
            p.clearLeak();
        }
    }

    public boolean hasLeak() {
        SonarProcessor p = engine.processor();
        return p != null && p.hasLeak();
    }

    public boolean isCalibrating() {
        SonarProcessor p = engine.processor();
        return p != null && p.isCalibrating();
    }

    public float quality() {
        return lastQuality;
    }

    public float nearest() {
        return lastNearest;
    }

    public boolean clipped() {
        return lastClipped;
    }

    public boolean warming() {
        return lastWarming;
    }

    public long frames() {
        return frames;
    }

    /** Короткое описание состояния для строки статуса. */
    public String statusLine() {
        String err = engine.lastError();
        if (err != null && !engine.isRunning()) {
            return "Ошибка: " + err;
        }
        if (!engine.isRunning()) {
            return "Сонар выключен";
        }
        if (err != null) {
            return "Ошибка: " + err;
        }
        if (lastWarming) {
            return "Запуск… (" + engine.sourceName() + ")";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Сигнал ").append(Math.round(lastQuality)).append(" дБ · ").append(engine.sourceName());
        if (lastClipped) {
            sb.append(" · ПЕРЕГРУЗКА: убавьте громкость");
        } else if (lastQuality < 18f && weakSince != 0 && System.currentTimeMillis() - weakSince > 2500) {
            if (AudioUtil.headphonesConnected(ctx)) {
                sb.append(" · отключите наушники");
            } else {
                sb.append(" · слабый сигнал: громкость на максимум / другой диапазон");
            }
        }
        return sb.toString();
    }

    @Override
    public void onFrame(SonarFrame f) {
        lastQuality = f.quality;
        lastNearest = f.nearestM;
        lastClipped = f.clipped;
        lastWarming = f.warming;
        frames++;
        if (f.quality < 18f) {
            if (weakSince == 0) {
                weakSince = System.currentTimeMillis();
            }
        } else {
            weakSince = 0;
        }
        double az = orient.azimuth();
        if (!f.warming) {
            if (mapEnabled) {
                map.update(az, f, st.lowDb, st.lowDb + SonarSettings.SPAN_DB, 10.0);
            }
            if (mapEnabled || trackOrientation) {
                sectors.update(az, f.nearestM, Bands.RANGE_M[st.range], System.currentTimeMillis(), 12.0);
            }
            if (!Float.isNaN(f.nearestM)) {
                nearMissed = 0;
                nearRing[nearN % nearRing.length] = f.nearestM;
                nearN++;
                int cnt = Math.min(nearN, nearRing.length);
                float[] tmp = new float[cnt];
                System.arraycopy(nearRing, 0, tmp, 0, cnt);
                java.util.Arrays.sort(tmp);
                smoothNearest = tmp[cnt / 2];
            } else if (++nearMissed > 5) {
                smoothNearest = Float.NaN;
                nearN = 0;
            }
            beeper.setDistance(smoothNearest);
        }
        for (FrameListener l : listeners) {
            try {
                l.onFrame(f, az);
            } catch (Throwable ignored) {
                // не даём экрану уронить обработку
            }
        }
    }
}
