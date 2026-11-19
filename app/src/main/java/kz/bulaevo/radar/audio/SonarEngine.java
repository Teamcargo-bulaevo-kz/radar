package kz.bulaevo.radar.audio;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Process;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import kz.bulaevo.radar.dsp.Bands;
import kz.bulaevo.radar.dsp.ChirpDesign;
import kz.bulaevo.radar.dsp.SonarFrame;
import kz.bulaevo.radar.dsp.SonarProcessor;

/**
 * Сонар: динамик непрерывно играет чирп, микрофон слушает, каждое окно из n отсчётов
 * превращается в профиль эха (см. {@link SonarProcessor}). Результаты уходят слушателям
 * из потока записи — слушатель обязан быстро скопировать нужное.
 */
public final class SonarEngine {
    public interface Listener {
        void onFrame(SonarFrame f);
    }

    private final Context ctx;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    private volatile boolean running;
    private Thread txThread;
    private Thread rxThread;
    private AudioTrack track;
    private AudioUtil.Rec rec;
    private SonarProcessor proc;
    private int band;
    private int rangeIdx;
    private volatile String sourceName = "";
    private volatile String error;

    public SonarEngine(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    public void addListener(Listener l) {
        if (!listeners.contains(l)) {
            listeners.add(l);
        }
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    public boolean isRunning() {
        return running;
    }

    public String lastError() {
        return error;
    }

    public String sourceName() {
        return sourceName;
    }

    public SonarProcessor processor() {
        return proc;
    }

    public int band() {
        return band;
    }

    public int rangeIdx() {
        return rangeIdx;
    }

    /** Запустить. Возвращает false и заполняет lastError(), если не вышло. */
    public synchronized boolean start(int bandIdx, int rangeIndex, float txAmp) {
        stop();
        error = null;
        band = Math.max(0, Math.min(Bands.NAMES.length - 1, bandIdx));
        rangeIdx = Math.max(0, Math.min(Bands.RANGE_N.length - 1, rangeIndex));
        final int n = Bands.RANGE_N[rangeIdx];
        try {
            ChirpDesign cd = new ChirpDesign(Bands.FS, n, Bands.FC[band], Bands.BW[band]);
            proc = new SonarProcessor(Bands.FS, n, cd);

            final short[] frame = new short[n];
            double amp = Math.max(0.05, Math.min(0.98, txAmp)) * 32767.0;
            for (int i = 0; i < n; i++) {
                frame[i] = (short) Math.round(cd.samples[i] * amp);
            }

            rec = AudioUtil.openRecorder(ctx, n);
            sourceName = rec.source;

            int minOut = AudioTrack.getMinBufferSize(AudioUtil.FS, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            int bufBytes = Math.max(minOut, n * 2 * 3);
            AudioTrack.Builder b = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(AudioUtil.FS)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(bufBytes)
                    .setTransferMode(AudioTrack.MODE_STREAM);
            if (Build.VERSION.SDK_INT >= 26) {
                b.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY);
            }
            track = b.build();
            if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                throw new Exception("Не удалось открыть динамик");
            }

            running = true;
            rec.rec.startRecording();
            track.play();

            final AudioTrack tr = track;
            txThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
                    try {
                        while (running) {
                            int w = tr.write(frame, 0, frame.length);
                            if (w < 0) {
                                error = "Ошибка вывода звука (" + w + ")";
                                break;
                            }
                        }
                    } catch (Throwable t) {
                        error = "Вывод звука: " + t.getMessage();
                    }
                }
            }, "sonar-tx");
            txThread.start();

            final AudioUtil.Rec rc = rec;
            final SonarProcessor sp = proc;
            final int skip = (int) Math.ceil(0.5 * Bands.FS / n);
            rxThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
                    short[] win = new short[n];
                    int filled = 0;
                    int windows = 0;
                    try {
                        while (running) {
                            int r = rc.rec.read(win, filled, n - filled);
                            if (r < 0) {
                                error = "Ошибка записи звука (" + r + ")";
                                break;
                            }
                            filled += r;
                            if (filled >= n) {
                                filled = 0;
                                if (windows++ < skip) {
                                    continue; // даём динамику выйти на режим
                                }
                                SonarFrame f = sp.process(win);
                                for (Listener l : listeners) {
                                    try {
                                        l.onFrame(f);
                                    } catch (Throwable ignored) {
                                        // слушатель не должен ронять обработку
                                    }
                                }
                            }
                        }
                    } catch (Throwable t) {
                        error = "Запись звука: " + t.getMessage();
                    }
                }
            }, "sonar-rx");
            rxThread.start();
            return true;
        } catch (Throwable t) {
            error = t.getMessage() == null ? t.toString() : t.getMessage();
            running = false;
            cleanup();
            return false;
        }
    }

    public synchronized void stop() {
        running = false;
        joinQuietly(txThread);
        joinQuietly(rxThread);
        txThread = null;
        rxThread = null;
        cleanup();
    }

    private void cleanup() {
        if (track != null) {
            try {
                track.pause();
                track.flush();
            } catch (Throwable ignored) {
                // ничего
            }
            try {
                track.stop();
            } catch (Throwable ignored) {
                // ничего
            }
            try {
                track.release();
            } catch (Throwable ignored) {
                // ничего
            }
            track = null;
        }
        if (rec != null) {
            rec.release();
            rec = null;
        }
    }

    private static void joinQuietly(Thread t) {
        if (t == null) {
            return;
        }
        try {
            t.join(800);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
