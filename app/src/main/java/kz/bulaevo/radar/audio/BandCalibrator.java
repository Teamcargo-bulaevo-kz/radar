package kz.bulaevo.radar.audio;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;

import kz.bulaevo.radar.dsp.ResponseAnalyzer;

/**
 * Частотный тест: телефон проигрывает плавный свип 1–23.5 кГц и слушает себя.
 * По записи определяется, какие частоты реально проходят "динамик → микрофон",
 * и выбирается лучший диапазон для сонара.
 */
public final class BandCalibrator {
    public interface Callback {
        void onProgress(int percent, String text);

        void onDone(ResponseAnalyzer.Result result, ResponseAnalyzer.Pick pick, String source);

        void onError(String message);
    }

    private static final double SECONDS = 6.0;
    private static final double LEAD = 0.5;
    private static final double TAIL = 0.8;

    private final Context ctx;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean cancel;
    private volatile boolean busy;

    public BandCalibrator(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    public boolean isBusy() {
        return busy;
    }

    public void cancel() {
        cancel = true;
    }

    public void start(final Callback cb) {
        if (busy) {
            return;
        }
        busy = true;
        cancel = false;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    runTest(cb);
                } catch (final Throwable t) {
                    post(new Runnable() {
                        @Override
                        public void run() {
                            cb.onError(t.getMessage() == null ? t.toString() : t.getMessage());
                        }
                    });
                } finally {
                    busy = false;
                }
            }
        }, "band-test").start();
    }

    private void post(Runnable r) {
        ui.post(r);
    }

    private void progress(final Callback cb, final int pct, final String text) {
        post(new Runnable() {
            @Override
            public void run() {
                cb.onProgress(pct, text);
            }
        });
    }

    private void runTest(final Callback cb) throws Exception {
        final double fs = AudioUtil.FS;
        final int total = (int) (fs * (LEAD + SECONDS + TAIL));
        final short[] rx = new short[total];
        final short[] sweep = ResponseAnalyzer.makeSweep(fs, SECONDS, 1000, 23500, 0.8);

        AudioUtil.Rec rec = AudioUtil.openRecorder(ctx, 4096);
        AudioTrack tr = null;
        try {
            tr = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(AudioUtil.FS)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(sweep.length * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build();
            tr.write(sweep, 0, sweep.length);

            rec.rec.startRecording();
            int got = 0;
            boolean played = false;
            progress(cb, 0, "Слушаю тишину…");
            while (got < total && !cancel) {
                int want = Math.min(4096, total - got);
                int r = rec.rec.read(rx, got, want);
                if (r < 0) {
                    throw new Exception("Ошибка записи звука (" + r + ")");
                }
                got += r;
                if (!played && got >= (int) (LEAD * fs)) {
                    tr.play();
                    played = true;
                    progress(cb, 10, "Играю свип 1–23.5 кГц…");
                }
                if (played) {
                    progress(cb, 10 + (int) (85.0 * got / total), "Играю свип…");
                }
            }
            if (cancel) {
                post(new Runnable() {
                    @Override
                    public void run() {
                        cb.onError("Тест отменён");
                    }
                });
                return;
            }
        } finally {
            if (tr != null) {
                try {
                    tr.stop();
                } catch (Throwable ignored) {
                    // ничего
                }
                tr.release();
            }
            rec.release();
        }
        progress(cb, 97, "Анализ…");
        final ResponseAnalyzer.Result res = ResponseAnalyzer.analyze(rx, total, fs);
        final ResponseAnalyzer.Pick pick = ResponseAnalyzer.pick(res);
        final String src = rec.source;
        post(new Runnable() {
            @Override
            public void run() {
                cb.onDone(res, pick, src);
            }
        });
    }
}
