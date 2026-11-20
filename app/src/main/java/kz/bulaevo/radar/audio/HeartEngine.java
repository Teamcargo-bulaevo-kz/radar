package kz.bulaevo.radar.audio;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Process;

import kz.bulaevo.radar.dsp.HeartSound;

/**
 * Запись звука сердца микрофоном (телефон прижат к груди) и, по желанию, прослушивание в проводных наушниках
 * — как электронный стетоскоп. В динамик звук не выводится: он попадёт обратно в микрофон и засвистит.
 */
public final class HeartEngine {
    private static final int OUT_FS = HeartSound.FS * 4; // 8 кГц: самая низкая частота, которую принимают все телефоны

    private final Context ctx;
    public final HeartSound dsp = new HeartSound();
    private volatile boolean running;
    private volatile boolean listen;
    private volatile double gain = 30;
    private Thread thread;
    private volatile String error;
    private volatile String source = "";

    public HeartEngine(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    public void setListen(boolean on) {
        listen = on;
    }

    public void setGain(double g) {
        gain = g;
    }

    public String error() {
        return error;
    }

    public String source() {
        return source;
    }

    public boolean isRunning() {
        return running;
    }

    public synchronized void start() {
        stop();
        error = null;
        running = true;
        synchronized (dsp) {
            dsp.reset();
        }
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "heart");
        thread.start();
    }

    private void loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        AudioUtil.Rec rec = null;
        AudioTrack out = null;
        try {
            rec = AudioUtil.openRecorder(ctx, 4800);
            source = rec.source;
            rec.rec.startRecording();
            short[] blk = new short[2400];
            short[] up = new short[blk.length / HeartSound.DEC * 4 + 16];
            short prev = 0;
            while (running) {
                int got = 0;
                while (got < blk.length && running) {
                    int r = rec.rec.read(blk, got, blk.length - got);
                    if (r < 0) {
                        throw new Exception("ошибка записи звука (" + r + ")");
                    }
                    got += r;
                }
                synchronized (dsp) {
                    dsp.process(blk, got, gain);
                }
                boolean wantOut = listen && AudioUtil.wiredHeadphones(ctx);
                if (wantOut && out == null) {
                    out = openTrack();
                } else if (!wantOut && out != null) {
                    out.release();
                    out = null;
                }
                if (out != null) {
                    int n = 0;
                    for (int i = 0; i < dsp.listenN && n + 4 <= up.length; i++) {
                        short s = dsp.listen[i];
                        for (int k = 1; k <= 4; k++) {
                            up[n++] = (short) (prev + (s - prev) * k / 4);
                        }
                        prev = s;
                    }
                    out.write(up, 0, n);
                }
            }
        } catch (Throwable t) {
            error = t.getMessage() == null ? t.toString() : t.getMessage();
        } finally {
            running = false;
            if (rec != null) {
                rec.release();
            }
            if (out != null) {
                out.release();
            }
        }
    }

    private static AudioTrack openTrack() {
        int min = AudioTrack.getMinBufferSize(OUT_FS, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioTrack t = new AudioTrack(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build(),
                new AudioFormat.Builder().setSampleRate(OUT_FS).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
                Math.max(min, OUT_FS / 2), AudioTrack.MODE_STREAM, 0);
        t.play();
        return t;
    }

    public synchronized void stop() {
        running = false;
        if (thread != null) {
            try {
                thread.join(800);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            thread = null;
        }
    }
}
