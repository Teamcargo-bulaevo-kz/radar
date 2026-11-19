package kz.bulaevo.radar.audio;

import android.content.Context;
import android.os.Process;

import kz.bulaevo.radar.dsp.SpectrumAnalyzer;

/** Спектр микрофона в реальном времени (без излучения звука). */
public final class SpectrumEngine {
    public interface Listener {
        /** Вызывается из потока записи; массив нужно скопировать. */
        void onSpectrum(float[] db, double binHz, double peakHz, double peakDb, double levelDb);
    }

    public static final int N = 4096;

    private final Context ctx;
    private volatile boolean running;
    private Thread thread;
    private AudioUtil.Rec rec;
    private volatile String error;
    private volatile String source = "";
    private volatile Listener listener;

    public SpectrumEngine(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public boolean isRunning() {
        return running;
    }

    public String lastError() {
        return error;
    }

    public String source() {
        return source;
    }

    public synchronized boolean start() {
        stop();
        error = null;
        try {
            rec = AudioUtil.openRecorder(ctx, N);
            source = rec.source;
            running = true;
            rec.rec.startRecording();
            final AudioUtil.Rec rc = rec;
            thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
                    SpectrumAnalyzer sa = new SpectrumAnalyzer(N, AudioUtil.FS);
                    short[] ring = new short[N];
                    short[] blk = new short[N / 2];
                    int have = 0;
                    try {
                        while (running) {
                            int got = 0;
                            while (got < blk.length && running) {
                                int r = rc.rec.read(blk, got, blk.length - got);
                                if (r < 0) {
                                    error = "Ошибка записи звука (" + r + ")";
                                    running = false;
                                    break;
                                }
                                got += r;
                            }
                            if (!running) {
                                break;
                            }
                            System.arraycopy(ring, blk.length, ring, 0, N - blk.length);
                            System.arraycopy(blk, 0, ring, N - blk.length, blk.length);
                            have += blk.length;
                            if (have < N) {
                                continue;
                            }
                            sa.analyze(ring, 0);
                            double sum = 0;
                            for (int i = 0; i < blk.length; i++) {
                                double v = blk[i] / 32768.0;
                                sum += v * v;
                            }
                            double rms = Math.sqrt(sum / blk.length);
                            double levelDb = 20.0 * Math.log10(Math.max(rms, 1e-7));
                            Listener l = listener;
                            if (l != null) {
                                l.onSpectrum(sa.db, sa.binHz(), sa.peakHz, sa.peakDb, levelDb);
                            }
                        }
                    } catch (Throwable t) {
                        error = "Спектр: " + t.getMessage();
                    }
                }
            }, "spectrum");
            thread.start();
            return true;
        } catch (Throwable t) {
            error = t.getMessage() == null ? t.toString() : t.getMessage();
            running = false;
            if (rec != null) {
                rec.release();
                rec = null;
            }
            return false;
        }
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
        if (rec != null) {
            rec.release();
            rec = null;
        }
    }
}
